package fr.acinq.eclair.channel

import akka.event.LoggingAdapter
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi}
import fr.acinq.eclair.blockchain.fee.OnChainFeeConf
import fr.acinq.eclair.channel.Commitments.{PostRevocationAction, msg2String}
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.crypto.keymanager.ChannelKeyManager
import fr.acinq.eclair.transactions.Transactions.InputInfo
import fr.acinq.eclair.wire.protocol.CommitSigTlv.{AlternativeCommitSig, AlternativeCommitSigsTlv}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{BlockHeight, Features}
import scodec.bits.ByteVector

/** Static parameters shared by all commitments. */
case class Params(channelId: ByteVector32,
                  channelConfig: ChannelConfig,
                  channelFeatures: ChannelFeatures,
                  localParams: LocalParams, remoteParams: RemoteParams,
                  channelFlags: ChannelFlags) {

  require(channelFeatures.paysDirectlyToWallet == localParams.walletStaticPaymentBasepoint.isDefined, s"localParams.walletStaticPaymentBasepoint must be defined only for commitments that pay directly to our wallet (channel features: $channelFeatures")
  require(channelFeatures.hasFeature(Features.DualFunding) == localParams.requestedChannelReserve_opt.isEmpty, "custom local channel reserve is incompatible with dual-funded channels")
  require(channelFeatures.hasFeature(Features.DualFunding) == remoteParams.requestedChannelReserve_opt.isEmpty, "custom remote channel reserve is incompatible with dual-funded channels")

  /**
   * We update local/global features at reconnection
   */
  def updateFeatures(localInit: Init, remoteInit: Init): Params = copy(
    localParams = localParams.copy(initFeatures = localInit.features),
    remoteParams = remoteParams.copy(initFeatures = remoteInit.features)
  )

  /**
   * @param scriptPubKey optional local script pubkey provided in CMD_CLOSE
   * @return the actual local shutdown script that we should use
   */
  def getLocalShutdownScript(scriptPubKey: Option[ByteVector]): Either[ChannelException, ByteVector] = {
    // to check whether shutdown_any_segwit is active we check features in local and remote parameters, which are negotiated each time we connect to our peer.
    val allowAnySegwit = Features.canUseFeature(localParams.initFeatures, remoteParams.initFeatures, Features.ShutdownAnySegwit)
    (channelFeatures.hasFeature(Features.UpfrontShutdownScript), scriptPubKey) match {
      case (true, Some(script)) if script != localParams.defaultFinalScriptPubKey => Left(InvalidFinalScript(channelId))
      case (false, Some(script)) if !Closing.MutualClose.isValidFinalScriptPubkey(script, allowAnySegwit) => Left(InvalidFinalScript(channelId))
      case (false, Some(script)) => Right(script)
      case _ => Right(localParams.defaultFinalScriptPubKey)
    }
  }

  /**
   * @param remoteScriptPubKey remote script included in a Shutdown message
   * @return the actual remote script that we should use
   */
  def getRemoteShutdownScript(remoteScriptPubKey: ByteVector): Either[ChannelException, ByteVector] = {
    // to check whether shutdown_any_segwit is active we check features in local and remote parameters, which are negotiated each time we connect to our peer.
    val allowAnySegwit = Features.canUseFeature(localParams.initFeatures, remoteParams.initFeatures, Features.ShutdownAnySegwit)
    (channelFeatures.hasFeature(Features.UpfrontShutdownScript), remoteParams.shutdownScript) match {
      case (false, _) if !Closing.MutualClose.isValidFinalScriptPubkey(remoteScriptPubKey, allowAnySegwit) => Left(InvalidFinalScript(channelId))
      case (false, _) => Right(remoteScriptPubKey)
      case (true, None) if !Closing.MutualClose.isValidFinalScriptPubkey(remoteScriptPubKey, allowAnySegwit) =>
        // this is a special case: they set option_upfront_shutdown_script but did not provide a script in their open/accept message
        Left(InvalidFinalScript(channelId))
      case (true, None) => Right(remoteScriptPubKey)
      case (true, Some(script)) if script != remoteScriptPubKey => Left(InvalidFinalScript(channelId))
      case (true, Some(script)) => Right(script)
    }
  }
}

case class WaitForRev(sent: CommitSig, sentAfterLocalCommitIndex: Long)

/** Dynamic values shared by all commitments, independently of the funding tx. */
case class Common(localChanges: LocalChanges, remoteChanges: RemoteChanges,
                  localNextHtlcId: Long, remoteNextHtlcId: Long,
                  localCommitIndex: Long, remoteCommitIndex: Long,
                  originChannels: Map[Long, Origin], // for outgoing htlcs relayed through us, details about the corresponding incoming htlcs
                  remoteNextCommitInfo: Either[WaitForRev, PublicKey], // this one is tricky, it must be kept in sync with Commitment.nextRemoteCommit_opt
                  remotePerCommitmentSecrets: ShaChain) {
  val nextRemoteCommitIndex = remoteCommitIndex + 1

  /**
   * When reconnecting, we drop all unsigned changes.
   */
  def discardUnsignedUpdates()(implicit log: LoggingAdapter): Common = {
    log.debug("discarding proposed OUT: {}", localChanges.proposed.map(msg2String(_)).mkString(","))
    log.debug("discarding proposed IN: {}", remoteChanges.proposed.map(msg2String(_)).mkString(","))
    val common1 = copy(
      localChanges = localChanges.copy(proposed = Nil),
      remoteChanges = remoteChanges.copy(proposed = Nil),
      localNextHtlcId = localNextHtlcId - localChanges.proposed.collect { case u: UpdateAddHtlc => u }.size,
      remoteNextHtlcId = remoteNextHtlcId - remoteChanges.proposed.collect { case u: UpdateAddHtlc => u }.size)
    log.debug(s"localNextHtlcId=$localNextHtlcId->${common1.localNextHtlcId}")
    log.debug(s"remoteNextHtlcId=$remoteNextHtlcId->${common1.remoteNextHtlcId}")
    common1
  }
}

/** A minimal commitment for a given funding tx. */
case class Commitment(localFundingStatus: LocalFundingStatus,
                      remoteFundingStatus: RemoteFundingStatus,
                      localCommit: LocalCommit, remoteCommit: RemoteCommit, nextRemoteCommit_opt: Option[RemoteCommit]) {
  val commitInput: InputInfo = localCommit.commitTxAndRemoteSig.commitTx.input
  val fundingTxId: ByteVector32 = commitInput.outPoint.txid
}

/**
 * @param commitments           all potentially valid commitments
 * @param remoteChannelData_opt peer backup
 */
case class MetaCommitments(params: Params,
                           common: Common,
                           commitments: List[Commitment],
                           remoteChannelData_opt: Option[ByteVector] = None) {

  require(commitments.nonEmpty, "there must be at least one commitments")

  val all: List[Commitments] = commitments.map(Commitments(params, common, _))

  /** current valid commitments, according to our view of the blockchain */
  val main: Commitments = all.head

  private def sequence[T](collection: List[Either[ChannelException, T]]): Either[ChannelException, List[T]] =
    collection.foldRight[Either[ChannelException, List[T]]](Right(Nil)) {
      case (Right(success), Right(res)) => Right(success +: res)
      case (Right(_), Left(res)) => Left(res)
      case (Left(failure), _) => Left(failure)
    }

  // NB: in the below, some common values are duplicated among all commitments, we only keep the first occurrence

  def sendAdd(cmd: CMD_ADD_HTLC, currentHeight: BlockHeight, feeConf: OnChainFeeConf): Either[ChannelException, (MetaCommitments, UpdateAddHtlc)] = {
    sequence(all.map(_.sendAdd(cmd, currentHeight, feeConf)))
      .map { res: List[(Commitments, UpdateAddHtlc)] => (res.head._1.common, res.map(_._1.commitment), res.head._2) }
      .map { case (common, commitments, add) => (this.copy(common = common, commitments = commitments), add) }
  }

  def receiveAdd(add: UpdateAddHtlc, feeConf: OnChainFeeConf): Either[ChannelException, MetaCommitments] = {
    sequence(all.map(_.receiveAdd(add, feeConf)))
      .map { res: List[Commitments] => (res.head.common, res.map(_.commitment)) }
      .map { case (common, commitments) => this.copy(common = common, commitments = commitments) }
  }

  def sendFulfill(cmd: CMD_FULFILL_HTLC): Either[ChannelException, (MetaCommitments, UpdateFulfillHtlc)] = {
    sequence(all.map(_.sendFulfill(cmd)))
      .map { res: List[(Commitments, UpdateFulfillHtlc)] => (res.head._1.common, res.map(_._1.commitment), res.head._2) }
      .map { case (common, commitments, fulfill) => (this.copy(common = common, commitments = commitments), fulfill) }
  }

  def receiveFulfill(fulfill: UpdateFulfillHtlc): Either[ChannelException, (MetaCommitments, Origin, UpdateAddHtlc)] = {
    sequence(all.map(_.receiveFulfill(fulfill)))
      .map { res: List[(Commitments, Origin, UpdateAddHtlc)] => (res.head._1.common, res.map(_._1.commitment), res.head._2, res.head._3) }
      .map { case (common, commitments, origin, add) => (this.copy(common = common, commitments = commitments), origin, add) }
  }

  def sendFail(cmd: CMD_FAIL_HTLC, nodeSecret: PrivateKey): Either[ChannelException, (MetaCommitments, HtlcFailureMessage)] = {
    sequence(all.map(_.sendFail(cmd, nodeSecret)))
      .map { res: List[(Commitments, HtlcFailureMessage)] => (res.head._1.common, res.map(_._1.commitment), res.head._2) }
      .map { case (common, commitments, fail) => (this.copy(common = common, commitments = commitments), fail) }
  }

  def sendFailMalformed(cmd: CMD_FAIL_MALFORMED_HTLC): Either[ChannelException, (MetaCommitments, UpdateFailMalformedHtlc)] = {
    sequence(all.map(_.sendFailMalformed(cmd)))
      .map { res: List[(Commitments, UpdateFailMalformedHtlc)] => (res.head._1.common, res.map(_._1.commitment), res.head._2) }
      .map { case (common, commitments, fail) => (this.copy(common = common, commitments = commitments), fail) }
  }

  def receiveFail(fail: UpdateFailHtlc): Either[ChannelException, (MetaCommitments, Origin, UpdateAddHtlc)] = {
    sequence(all.map(_.receiveFail(fail)))
      .map { res: List[(Commitments, Origin, UpdateAddHtlc)] => (res.head._1.common, res.map(_._1.commitment), res.head._2, res.head._3) }
      .map { case (common, commitments, origin, fail) => (this.copy(common = common, commitments = commitments), origin, fail) }
  }

  def receiveFailMalformed(fail: UpdateFailMalformedHtlc): Either[ChannelException, (MetaCommitments, Origin, UpdateAddHtlc)] = {
    sequence(all.map(_.receiveFailMalformed(fail)))
      .map { res: List[(Commitments, Origin, UpdateAddHtlc)] => (res.head._1.common, res.map(_._1.commitment), res.head._2, res.head._3) }
      .map { case (common, commitments, origin, fail) => (this.copy(common = common, commitments = commitments), origin, fail) }
  }

  def sendFee(cmd: CMD_UPDATE_FEE, feeConf: OnChainFeeConf): Either[ChannelException, (MetaCommitments, UpdateFee)] = {
    sequence(all.map(_.sendFee(cmd, feeConf)))
      .map { res: List[(Commitments, UpdateFee)] => (res.head._1.common, res.map(_._1.commitment), res.head._2) }
      .map { case (common, commitments, fee) => (this.copy(common = common, commitments = commitments), fee) }
  }

  def receiveFee(fee: UpdateFee, feeConf: OnChainFeeConf)(implicit log: LoggingAdapter): Either[ChannelException, MetaCommitments] = {
    sequence(all.map(_.receiveFee(fee, feeConf)))
      .map { res: List[Commitments] => (res.head.common, res.map(_.commitment)) }
      .map { case (common, commitments) => this.copy(common = common, commitments = commitments) }
  }

  /** We need to send signatures for each commitments. */
  def sendCommit(keyManager: ChannelKeyManager)(implicit log: LoggingAdapter): Either[ChannelException, (MetaCommitments, CommitSig)] = {
    sequence(all.map(_.sendCommit(keyManager)))
      .map { res: List[(Commitments, CommitSig)] =>
        val tlv = AlternativeCommitSigsTlv(res.foldLeft(List.empty[AlternativeCommitSig]) {
          case (sigs, (commitments, commitSig)) => AlternativeCommitSig(commitments.fundingTxId, commitSig.signature, commitSig.htlcSignatures) +: sigs
        })
        // we set all commit_sigs as tlv of the first commit_sig (the first sigs will be duplicated)
        val commitSig = res.head._2.modify(_.tlvStream.records).usingIf(tlv.commitSigs.size > 1)(tlv +: _.toList)
        val common = res.head._1.common
        val commitments = res.map(_._1.commitment)
        (this.copy(common = common, commitments = commitments), commitSig)
      }
  }

  def receiveCommit(sig: CommitSig, keyManager: ChannelKeyManager)(implicit log: LoggingAdapter): Either[ChannelException, (MetaCommitments, RevokeAndAck)] = {
    val sigs: Map[ByteVector32, CommitSig] = sig.alternativeCommitSigs match {
      case Nil => Map(all.head.fundingTxId -> sig) // no alternative sigs: we use the commit_sig message as-is, we assume it is for the first commitments
      case alternativeCommitSig =>
        // if there are alternative sigs, then we expand the sigs to build n individual commit_sig that we will apply to the corresponding commitments
        alternativeCommitSig.map { altSig =>
          altSig.fundingTxId -> sig
            .modify(_.signature).setTo(altSig.signature)
            .modify(_.htlcSignatures).setTo(altSig.htlcSignatures)
            .modify(_.tlvStream.records).using(_.filterNot(_.isInstanceOf[AlternativeCommitSigsTlv]))
        }.toMap
    }
    sequence(all.map(c => c.receiveCommit(sigs(c.fundingTxId), keyManager)))
      .map { res: List[(Commitments, RevokeAndAck)] => (res.head._1.common, res.map(_._1.commitment), res.head._2) }
      .map { case (common, commitments, rev) => (this.copy(common = common, commitments = commitments), rev) }
  }

  def receiveRevocation(revocation: RevokeAndAck, maxDustExposure: Satoshi): Either[ChannelException, (MetaCommitments, Seq[PostRevocationAction])] = {
    sequence(all.map(c => c.receiveRevocation(revocation, maxDustExposure)))
      .map { res: List[(Commitments, Seq[PostRevocationAction])] => (res.head._1.common, res.map(_._1.commitment), res.head._2) }
      .map { case (common, commitments, actions) => (this.copy(common = common, commitments = commitments), actions) }
  }

  def discardUnsignedUpdates(implicit log: LoggingAdapter): MetaCommitments = {
    this.copy(common = common.discardUnsignedUpdates())
  }

  def localHasChanges: Boolean = main.localHasChanges

}

object MetaCommitments {
  /** A 1:1 conversion helper to facilitate migration, nothing smart here. */
  def apply(commitments: Commitments): MetaCommitments = MetaCommitments(
    params = commitments.params,
    common = commitments.common,
    commitments = commitments.commitment +: Nil
  )
}