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
import fr.acinq.eclair.transactions.Transactions.{CommitmentFormat, InputInfo}
import fr.acinq.eclair.transactions.{CommitmentSpec, Transactions}
import fr.acinq.eclair.wire.protocol.CommitSigTlv.{AlternativeCommitSig, AlternativeCommitSigsTlv}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{BlockHeight, Features, MilliSatoshi, MilliSatoshiLong}
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

  val commitmentFormat: CommitmentFormat = channelFeatures.commitmentFormat
  val channelType: SupportedChannelType = channelFeatures.channelType
  val announceChannel: Boolean = channelFlags.announceChannel

  val localNodeId: PublicKey = localParams.nodeId
  val remoteNodeId: PublicKey = remoteParams.nodeId

  // We can safely cast to millisatoshis since we verify that it's less than a valid millisatoshi amount.
  val maxHtlcAmount: MilliSatoshi = remoteParams.maxHtlcValueInFlightMsat.toBigInt.min(localParams.maxHtlcValueInFlightMsat.toLong).toLong.msat

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
  val capacity: Satoshi = commitInput.txOut.amount

  /** Channel reserve that applies to our funds. */
  def localChannelReserve(params: Params): Satoshi = if (params.channelFeatures.hasFeature(Features.DualFunding)) {
    (capacity / 100).max(params.remoteParams.dustLimit)
  } else {
    params.remoteParams.requestedChannelReserve_opt.get // this is guarded by a require() in Commitments
  }

  /** Channel reserve that applies to our peer's funds. */
  def remoteChannelReserve(params: Params): Satoshi = if (params.channelFeatures.hasFeature(Features.DualFunding)) {
    (capacity / 100).max(params.localParams.dustLimit)
  } else {
    params.localParams.requestedChannelReserve_opt.get // this is guarded by a require() in Commitments
  }

  // NB: when computing availableBalanceForSend and availableBalanceForReceive, the initiator keeps an extra buffer on
  // top of its usual channel reserve to avoid getting channels stuck in case the on-chain feerate increases (see
  // https://github.com/lightningnetwork/lightning-rfc/issues/728 for details).
  //
  // This extra buffer (which we call "funder fee buffer") is calculated as follows:
  //  1) Simulate a x2 feerate increase and compute the corresponding commit tx fee (note that it may trim some HTLCs)
  //  2) Add the cost of adding a new untrimmed HTLC at that increased feerate. This ensures that we'll be able to
  //     actually use the channel to add new HTLCs if the feerate doubles.
  //
  // If for example the current feerate is 1000 sat/kw, the dust limit 546 sat, and we have 3 pending outgoing HTLCs for
  // respectively 1250 sat, 2000 sat and 2500 sat.
  // commit tx fee = commitWeight * feerate + 3 * htlcOutputWeight * feerate = 724 * 1000 + 3 * 172 * 1000 = 1240 sat
  // To calculate the funder fee buffer, we first double the feerate and calculate the corresponding commit tx fee.
  // By doubling the feerate, the first HTLC becomes trimmed so the result is: 724 * 2000 + 2 * 172 * 2000 = 2136 sat
  // We then add the additional fee for a potential new untrimmed HTLC: 172 * 2000 = 344 sat
  // The funder fee buffer is 2136 + 344 = 2480 sat
  //
  // If there are many pending HTLCs that are only slightly above the trim threshold, the funder fee buffer may be
  // smaller than the current commit tx fee because those HTLCs will be trimmed and the commit tx weight will decrease.
  // For example if we have 10 outgoing HTLCs of 1250 sat:
  //  - commit tx fee = 724 * 1000 + 10 * 172 * 1000 = 2444 sat
  //  - commit tx fee at twice the feerate = 724 * 2000 = 1448 sat (all HTLCs have been trimmed)
  //  - cost of an additional untrimmed HTLC = 172 * 2000 = 344 sat
  //  - funder fee buffer = 1448 + 344 = 1792 sat
  // In that case the current commit tx fee is higher than the funder fee buffer and will dominate the balance restrictions.

  def availableBalanceForSend(params: Params, common: Common): MilliSatoshi = {
    import params._
    // we need to base the next current commitment on the last sig we sent, even if we didn't yet receive their revocation
    val remoteCommit1 = nextRemoteCommit_opt.getOrElse(remoteCommit)
    val reduced = CommitmentSpec.reduce(remoteCommit1.spec, common.remoteChanges.acked, common.localChanges.proposed)
    val balanceNoFees = (reduced.toRemote - localChannelReserve(params)).max(0 msat)
    if (localParams.isInitiator) {
      // The initiator always pays the on-chain fees, so we must subtract that from the amount we can send.
      val commitFees = Transactions.commitTxTotalCostMsat(remoteParams.dustLimit, reduced, commitmentFormat)
      // the initiator needs to keep a "funder fee buffer" (see explanation above)
      val funderFeeBuffer = Transactions.commitTxTotalCostMsat(remoteParams.dustLimit, reduced.copy(commitTxFeerate = reduced.commitTxFeerate * 2), commitmentFormat) + Transactions.htlcOutputFee(reduced.commitTxFeerate * 2, commitmentFormat)
      val amountToReserve = commitFees.max(funderFeeBuffer)
      if (balanceNoFees - amountToReserve < Transactions.offeredHtlcTrimThreshold(remoteParams.dustLimit, reduced, commitmentFormat)) {
        // htlc will be trimmed
        (balanceNoFees - amountToReserve).max(0 msat)
      } else {
        // htlc will have an output in the commitment tx, so there will be additional fees.
        val commitFees1 = commitFees + Transactions.htlcOutputFee(reduced.commitTxFeerate, commitmentFormat)
        // we take the additional fees for that htlc output into account in the fee buffer at a x2 feerate increase
        val funderFeeBuffer1 = funderFeeBuffer + Transactions.htlcOutputFee(reduced.commitTxFeerate * 2, commitmentFormat)
        val amountToReserve1 = commitFees1.max(funderFeeBuffer1)
        (balanceNoFees - amountToReserve1).max(0 msat)
      }
    } else {
      // The non-initiator doesn't pay on-chain fees.
      balanceNoFees
    }
  }

  def availableBalanceForReceive(params: Params, common: Common): MilliSatoshi = {
    import params._
    val reduced = CommitmentSpec.reduce(localCommit.spec, common.localChanges.acked, common.remoteChanges.proposed)
    val balanceNoFees = (reduced.toRemote - remoteChannelReserve(params)).max(0 msat)
    if (localParams.isInitiator) {
      // The non-initiator doesn't pay on-chain fees so we don't take those into account when receiving.
      balanceNoFees
    } else {
      // The initiator always pays the on-chain fees, so we must subtract that from the amount we can receive.
      val commitFees = Transactions.commitTxTotalCostMsat(localParams.dustLimit, reduced, commitmentFormat)
      // we expected the initiator to keep a "funder fee buffer" (see explanation above)
      val funderFeeBuffer = Transactions.commitTxTotalCostMsat(localParams.dustLimit, reduced.copy(commitTxFeerate = reduced.commitTxFeerate * 2), commitmentFormat) + Transactions.htlcOutputFee(reduced.commitTxFeerate * 2, commitmentFormat)
      val amountToReserve = commitFees.max(funderFeeBuffer)
      if (balanceNoFees - amountToReserve < Transactions.receivedHtlcTrimThreshold(localParams.dustLimit, reduced, commitmentFormat)) {
        // htlc will be trimmed
        (balanceNoFees - amountToReserve).max(0 msat)
      } else {
        // htlc will have an output in the commitment tx, so there will be additional fees.
        val commitFees1 = commitFees + Transactions.htlcOutputFee(reduced.commitTxFeerate, commitmentFormat)
        // we take the additional fees for that htlc output into account in the fee buffer at a x2 feerate increase
        val funderFeeBuffer1 = funderFeeBuffer + Transactions.htlcOutputFee(reduced.commitTxFeerate * 2, commitmentFormat)
        val amountToReserve1 = commitFees1.max(funderFeeBuffer1)
        (balanceNoFees - amountToReserve1).max(0 msat)
      }
    }
  }
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

  lazy val availableBalanceForSend: MilliSatoshi = commitments.map(_.availableBalanceForSend(params, common)).min
  lazy val availableBalanceForReceive: MilliSatoshi = commitments.map(_.availableBalanceForReceive(params, common)).min

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