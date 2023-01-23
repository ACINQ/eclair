package fr.acinq.eclair.channel

import akka.event.LoggingAdapter
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, Satoshi, SatoshiLong}
import fr.acinq.eclair.blockchain.fee.{FeeratePerKw, OnChainFeeConf}
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel.Monitoring.Metrics
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.crypto.keymanager.ChannelKeyManager
import fr.acinq.eclair.crypto.{Generators, ShaChain}
import fr.acinq.eclair.payment.OutgoingPaymentPacket
import fr.acinq.eclair.transactions.Transactions.{CommitmentFormat, InputInfo, TransactionWithInputInfo}
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.protocol.CommitSigTlv.{AlternativeCommitSig, AlternativeCommitSigsTlv}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{BlockHeight, CltvExpiry, CltvExpiryDelta, Features, MilliSatoshi, MilliSatoshiLong, payment}
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

  import Common._

  val nextRemoteCommitIndex = remoteCommitIndex + 1

  val localHasChanges: Boolean = remoteChanges.acked.nonEmpty || localChanges.proposed.nonEmpty
  val remoteHasChanges: Boolean = localChanges.acked.nonEmpty || remoteChanges.proposed.nonEmpty
  val localHasUnsignedOutgoingHtlcs: Boolean = localChanges.proposed.collectFirst { case u: UpdateAddHtlc => u }.isDefined
  val remoteHasUnsignedOutgoingHtlcs: Boolean = remoteChanges.proposed.collectFirst { case u: UpdateAddHtlc => u }.isDefined
  val localHasUnsignedOutgoingUpdateFee: Boolean = localChanges.proposed.collectFirst { case u: UpdateFee => u }.isDefined
  val remoteHasUnsignedOutgoingUpdateFee: Boolean = remoteChanges.proposed.collectFirst { case u: UpdateFee => u }.isDefined

  def addLocalProposal(proposal: UpdateMessage): Common = copy(localChanges = localChanges.copy(proposed = localChanges.proposed :+ proposal))

  def addRemoteProposal(proposal: UpdateMessage): Common = copy(remoteChanges = remoteChanges.copy(proposed = remoteChanges.proposed :+ proposal))

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

object Common {
  def alreadyProposed(changes: List[UpdateMessage], id: Long): Boolean = changes.exists {
    case u: UpdateFulfillHtlc => id == u.id
    case u: UpdateFailHtlc => id == u.id
    case u: UpdateFailMalformedHtlc => id == u.id
    case _ => false
  }

  def msg2String(msg: LightningMessage): String = msg match {
    case u: UpdateAddHtlc => s"add-${u.id}"
    case u: UpdateFulfillHtlc => s"ful-${u.id}"
    case u: UpdateFailHtlc => s"fail-${u.id}"
    case _: UpdateFee => s"fee"
    case _: CommitSig => s"sig"
    case _: RevokeAndAck => s"rev"
    case _: Error => s"err"
    case _: ChannelReady => s"channel_ready"
    case _ => "???"
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

  def hasNoPendingHtlcs: Boolean = localCommit.spec.htlcs.isEmpty && remoteCommit.spec.htlcs.isEmpty && nextRemoteCommit_opt.isEmpty

  def hasNoPendingHtlcsOrFeeUpdate(common: Common): Boolean =
    nextRemoteCommit_opt.isEmpty &&
      localCommit.spec.htlcs.isEmpty &&
      remoteCommit.spec.htlcs.isEmpty &&
      (common.localChanges.signed ++ common.localChanges.acked ++ common.remoteChanges.signed ++ common.remoteChanges.acked).collectFirst { case _: UpdateFee => true }.isEmpty

  def hasPendingOrProposedHtlcs(common: Common): Boolean = !hasNoPendingHtlcs ||
    common.localChanges.all.exists(_.isInstanceOf[UpdateAddHtlc]) ||
    common.remoteChanges.all.exists(_.isInstanceOf[UpdateAddHtlc])

  def timedOutOutgoingHtlcs(currentHeight: BlockHeight): Set[UpdateAddHtlc] = {
    def expired(add: UpdateAddHtlc): Boolean = currentHeight >= add.cltvExpiry.blockHeight

    localCommit.spec.htlcs.collect(DirectedHtlc.outgoing).filter(expired) ++
      remoteCommit.spec.htlcs.collect(DirectedHtlc.incoming).filter(expired) ++
      nextRemoteCommit_opt.toSeq.flatMap(_.spec.htlcs.collect(DirectedHtlc.incoming).filter(expired).toSet)
  }

  /**
   * Return the outgoing HTLC with the given id if it is:
   *  - signed by us in their commitment transaction (remote)
   *  - signed by them in our commitment transaction (local)
   *
   * NB: if we're in the middle of fulfilling or failing that HTLC, it will not be returned by this function.
   */
  def getOutgoingHtlcCrossSigned(htlcId: Long): Option[UpdateAddHtlc] = for {
    localSigned <- nextRemoteCommit_opt.getOrElse(remoteCommit).spec.findIncomingHtlcById(htlcId)
    remoteSigned <- localCommit.spec.findOutgoingHtlcById(htlcId)
  } yield {
    require(localSigned.add == remoteSigned.add)
    localSigned.add
  }

  /**
   * Return the incoming HTLC with the given id if it is:
   *  - signed by us in their commitment transaction (remote)
   *  - signed by them in our commitment transaction (local)
   *
   * NB: if we're in the middle of fulfilling or failing that HTLC, it will not be returned by this function.
   */
  def getIncomingHtlcCrossSigned(htlcId: Long): Option[UpdateAddHtlc] = for {
    localSigned <- nextRemoteCommit_opt.getOrElse(remoteCommit).spec.findOutgoingHtlcById(htlcId)
    remoteSigned <- localCommit.spec.findIncomingHtlcById(htlcId)
  } yield {
    require(localSigned.add == remoteSigned.add)
    localSigned.add
  }

  /**
   * HTLCs that are close to timing out upstream are potentially dangerous. If we received the preimage for those HTLCs,
   * we need to get a remote signed updated commitment that removes those HTLCs.
   * Otherwise when we get close to the upstream timeout, we risk an on-chain race condition between their HTLC timeout
   * and our HTLC success in case of a force-close.
   */
  def almostTimedOutIncomingHtlcs(currentHeight: BlockHeight, fulfillSafety: CltvExpiryDelta): Set[UpdateAddHtlc] = {
    def nearlyExpired(add: UpdateAddHtlc): Boolean = currentHeight >= (add.cltvExpiry - fulfillSafety).blockHeight

    localCommit.spec.htlcs.collect(DirectedHtlc.incoming).filter(nearlyExpired)
  }

  /**
   * Return a fully signed commit tx, that can be published as-is.
   */
  def fullySignedLocalCommitTx(params: Params, keyManager: ChannelKeyManager): Transactions.CommitTx = {
    val unsignedCommitTx = localCommit.commitTxAndRemoteSig.commitTx
    val localSig = keyManager.sign(unsignedCommitTx, keyManager.fundingPublicKey(params.localParams.fundingKeyPath), Transactions.TxOwner.Local, params.commitmentFormat)
    val remoteSig = localCommit.commitTxAndRemoteSig.remoteSig
    val commitTx = Transactions.addSigs(unsignedCommitTx, keyManager.fundingPublicKey(params.localParams.fundingKeyPath).publicKey, params.remoteParams.fundingPubKey, localSig, remoteSig)
    // We verify the remote signature when receiving their commit_sig, so this check should always pass.
    require(Transactions.checkSpendable(commitTx).isSuccess, "commit signatures are invalid")
    commitTx
  }

}

object Commitment {
  def makeLocalTxs(keyManager: ChannelKeyManager,
                   channelConfig: ChannelConfig,
                   channelFeatures: ChannelFeatures,
                   commitTxNumber: Long,
                   localParams: LocalParams,
                   remoteParams: RemoteParams,
                   commitmentInput: InputInfo,
                   localPerCommitmentPoint: PublicKey,
                   spec: CommitmentSpec): (Transactions.CommitTx, Seq[Transactions.HtlcTx]) = {
    val channelKeyPath = keyManager.keyPath(localParams, channelConfig)
    val localFundingPubkey = keyManager.fundingPublicKey(localParams.fundingKeyPath).publicKey
    val localDelayedPaymentPubkey = Generators.derivePubKey(keyManager.delayedPaymentPoint(channelKeyPath).publicKey, localPerCommitmentPoint)
    val localHtlcPubkey = Generators.derivePubKey(keyManager.htlcPoint(channelKeyPath).publicKey, localPerCommitmentPoint)
    val remotePaymentPubkey = if (channelFeatures.hasFeature(Features.StaticRemoteKey)) {
      remoteParams.paymentBasepoint
    } else {
      Generators.derivePubKey(remoteParams.paymentBasepoint, localPerCommitmentPoint)
    }
    val remoteHtlcPubkey = Generators.derivePubKey(remoteParams.htlcBasepoint, localPerCommitmentPoint)
    val localRevocationPubkey = Generators.revocationPubKey(remoteParams.revocationBasepoint, localPerCommitmentPoint)
    val localPaymentBasepoint = localParams.walletStaticPaymentBasepoint.getOrElse(keyManager.paymentPoint(channelKeyPath).publicKey)
    val outputs = Transactions.makeCommitTxOutputs(localParams.isInitiator, localParams.dustLimit, localRevocationPubkey, remoteParams.toSelfDelay, localDelayedPaymentPubkey, remotePaymentPubkey, localHtlcPubkey, remoteHtlcPubkey, localFundingPubkey, remoteParams.fundingPubKey, spec, channelFeatures.commitmentFormat)
    val commitTx = Transactions.makeCommitTx(commitmentInput, commitTxNumber, localPaymentBasepoint, remoteParams.paymentBasepoint, localParams.isInitiator, outputs)
    val htlcTxs = Transactions.makeHtlcTxs(commitTx.tx, localParams.dustLimit, localRevocationPubkey, remoteParams.toSelfDelay, localDelayedPaymentPubkey, spec.htlcTxFeerate(channelFeatures.commitmentFormat), outputs, channelFeatures.commitmentFormat)
    (commitTx, htlcTxs)
  }

  def makeRemoteTxs(keyManager: ChannelKeyManager,
                    channelConfig: ChannelConfig,
                    channelFeatures: ChannelFeatures,
                    commitTxNumber: Long,
                    localParams: LocalParams,
                    remoteParams: RemoteParams,
                    commitmentInput: InputInfo,
                    remotePerCommitmentPoint: PublicKey,
                    spec: CommitmentSpec): (Transactions.CommitTx, Seq[Transactions.HtlcTx]) = {
    val channelKeyPath = keyManager.keyPath(localParams, channelConfig)
    val localFundingPubkey = keyManager.fundingPublicKey(localParams.fundingKeyPath).publicKey
    val localPaymentBasepoint = localParams.walletStaticPaymentBasepoint.getOrElse(keyManager.paymentPoint(channelKeyPath).publicKey)
    val localPaymentPubkey = if (channelFeatures.hasFeature(Features.StaticRemoteKey)) {
      localPaymentBasepoint
    } else {
      Generators.derivePubKey(localPaymentBasepoint, remotePerCommitmentPoint)
    }
    val localHtlcPubkey = Generators.derivePubKey(keyManager.htlcPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)
    val remoteDelayedPaymentPubkey = Generators.derivePubKey(remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
    val remoteHtlcPubkey = Generators.derivePubKey(remoteParams.htlcBasepoint, remotePerCommitmentPoint)
    val remoteRevocationPubkey = Generators.revocationPubKey(keyManager.revocationPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)
    val outputs = Transactions.makeCommitTxOutputs(!localParams.isInitiator, remoteParams.dustLimit, remoteRevocationPubkey, localParams.toSelfDelay, remoteDelayedPaymentPubkey, localPaymentPubkey, remoteHtlcPubkey, localHtlcPubkey, remoteParams.fundingPubKey, localFundingPubkey, spec, channelFeatures.commitmentFormat)
    val commitTx = Transactions.makeCommitTx(commitmentInput, commitTxNumber, remoteParams.paymentBasepoint, localPaymentBasepoint, !localParams.isInitiator, outputs)
    val htlcTxs = Transactions.makeHtlcTxs(commitTx.tx, remoteParams.dustLimit, remoteRevocationPubkey, localParams.toSelfDelay, remoteDelayedPaymentPubkey, spec.htlcTxFeerate(channelFeatures.commitmentFormat), outputs, channelFeatures.commitmentFormat)
    (commitTx, htlcTxs)
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

  import MetaCommitments._

  require(commitments.nonEmpty, "there must be at least one commitments")

  val channelId: ByteVector32 = params.channelId
  val localNodeId: PublicKey = params.localNodeId
  val remoteNodeId: PublicKey = params.remoteNodeId

  val all: List[Commitments] = commitments.map(Commitments(params, common, _))

  /** current valid commitments, according to our view of the blockchain */
  val main: Commitments = all.head

  lazy val availableBalanceForSend: MilliSatoshi = commitments.map(_.availableBalanceForSend(params, common)).min
  lazy val availableBalanceForReceive: MilliSatoshi = commitments.map(_.availableBalanceForReceive(params, common)).min

  def hasNoPendingHtlcs: Boolean = commitments.head.hasNoPendingHtlcs

  def hasNoPendingHtlcsOrFeeUpdate: Boolean = commitments.head.hasNoPendingHtlcsOrFeeUpdate(common)

  def hasPendingOrProposedHtlcs: Boolean = commitments.head.hasPendingOrProposedHtlcs(common)

  def timedOutOutgoingHtlcs(currentHeight: BlockHeight): Set[UpdateAddHtlc] = commitments.head.timedOutOutgoingHtlcs(currentHeight)

  def almostTimedOutIncomingHtlcs(currentHeight: BlockHeight, fulfillSafety: CltvExpiryDelta): Set[UpdateAddHtlc] = commitments.head.almostTimedOutIncomingHtlcs(currentHeight, fulfillSafety)

  def getOutgoingHtlcCrossSigned(htlcId: Long): Option[UpdateAddHtlc] = commitments.head.getOutgoingHtlcCrossSigned(htlcId)

  def getIncomingHtlcCrossSigned(htlcId: Long): Option[UpdateAddHtlc] = commitments.head.getIncomingHtlcCrossSigned(htlcId)

  private def sequence[T](collection: List[Either[ChannelException, T]]): Either[ChannelException, List[T]] =
    collection.foldRight[Either[ChannelException, List[T]]](Right(Nil)) {
      case (Right(success), Right(res)) => Right(success +: res)
      case (Right(_), Left(res)) => Left(res)
      case (Left(failure), _) => Left(failure)
    }

  // NB: in the below, some common values are duplicated among all commitments, we only keep the first occurrence

  /**
   * @param cmd add HTLC command
   * @return either Left(failure, error message) where failure is a failure message (see BOLT #4 and the Failure Message class) or Right(new commitments, updateAddHtlc)
   */
  def sendAdd(cmd: CMD_ADD_HTLC, currentHeight: BlockHeight, feeConf: OnChainFeeConf): Either[ChannelException, (MetaCommitments, UpdateAddHtlc)] = {
    // we must ensure we're not relaying htlcs that are already expired, otherwise the downstream channel will instantly close
    // NB: we add a 3 blocks safety to reduce the probability of running into this when our bitcoin node is slightly outdated
    val minExpiry = CltvExpiry(currentHeight + 3)
    if (cmd.cltvExpiry < minExpiry) {
      return Left(ExpiryTooSmall(channelId, minimum = minExpiry, actual = cmd.cltvExpiry, blockHeight = currentHeight))
    }
    // we don't want to use too high a refund timeout, because our funds will be locked during that time if the payment is never fulfilled
    val maxExpiry = Channel.MAX_CLTV_EXPIRY_DELTA.toCltvExpiry(currentHeight)
    if (cmd.cltvExpiry >= maxExpiry) {
      return Left(ExpiryTooBig(channelId, maximum = maxExpiry, actual = cmd.cltvExpiry, blockHeight = currentHeight))
    }

    // even if remote advertises support for 0 msat htlc, we limit ourselves to values strictly positive, hence the max(1 msat)
    val htlcMinimum = params.remoteParams.htlcMinimum.max(1 msat)
    if (cmd.amount < htlcMinimum) {
      return Left(HtlcValueTooSmall(params.channelId, minimum = htlcMinimum, actual = cmd.amount))
    }

    val add = UpdateAddHtlc(channelId, common.localNextHtlcId, cmd.amount, cmd.paymentHash, cmd.cltvExpiry, cmd.onion, cmd.nextBlindingKey_opt)
    // we increment the local htlc index and add an entry to the origins map
    val common1 = common.addLocalProposal(add).copy(localNextHtlcId = common.localNextHtlcId + 1, originChannels = common.originChannels + (add.id -> cmd.origin))

    // let's compute the current commitments *as seen by them* with this change taken into account
    commitments.foreach(commitment => {
      // we allowed mismatches between our feerates and our remote's as long as commitments didn't contain any HTLC at risk
      // we need to verify that we're not disagreeing on feerates anymore before offering new HTLCs
      // NB: there may be a pending update_fee that hasn't been applied yet that needs to be taken into account
      val localFeeratePerKw = feeConf.getCommitmentFeerate(remoteNodeId, params.channelType, commitment.capacity, None)
      val remoteFeeratePerKw = commitment.localCommit.spec.commitTxFeerate +: common1.remoteChanges.all.collect { case f: UpdateFee => f.feeratePerKw }
      remoteFeeratePerKw.find(feerate => feeConf.feerateToleranceFor(remoteNodeId).isFeeDiffTooHigh(params.channelType, localFeeratePerKw, feerate)) match {
        case Some(feerate) => return Left(FeerateTooDifferent(channelId, localFeeratePerKw = localFeeratePerKw, remoteFeeratePerKw = feerate))
        case None =>
      }

      // we need to base the next current commitment on the last sig we sent, even if we didn't yet receive their revocation
      val remoteCommit1 = commitment.nextRemoteCommit_opt.getOrElse(commitment.remoteCommit)
      val reduced = CommitmentSpec.reduce(remoteCommit1.spec, common1.remoteChanges.acked, common1.localChanges.proposed)
      // the HTLC we are about to create is outgoing, but from their point of view it is incoming
      val outgoingHtlcs = reduced.htlcs.collect(DirectedHtlc.incoming)

      // note that the initiator pays the fee, so if sender != initiator, both sides will have to afford this payment
      val fees = Transactions.commitTxTotalCost(params.remoteParams.dustLimit, reduced, params.commitmentFormat)
      // the initiator needs to keep an extra buffer to be able to handle a x2 feerate increase and an additional htlc to avoid
      // getting the channel stuck (see https://github.com/lightningnetwork/lightning-rfc/issues/728).
      val funderFeeBuffer = Transactions.commitTxTotalCostMsat(params.remoteParams.dustLimit, reduced.copy(commitTxFeerate = reduced.commitTxFeerate * 2), params.commitmentFormat) + Transactions.htlcOutputFee(reduced.commitTxFeerate * 2, params.commitmentFormat)
      // NB: increasing the feerate can actually remove htlcs from the commit tx (if they fall below the trim threshold)
      // which may result in a lower commit tx fee; this is why we take the max of the two.
      val missingForSender = reduced.toRemote - commitment.localChannelReserve(params) - (if (params.localParams.isInitiator) fees.max(funderFeeBuffer.truncateToSatoshi) else 0.sat)
      val missingForReceiver = reduced.toLocal - commitment.remoteChannelReserve(params) - (if (params.localParams.isInitiator) 0.sat else fees)
      if (missingForSender < 0.msat) {
        return Left(InsufficientFunds(channelId, amount = cmd.amount, missing = -missingForSender.truncateToSatoshi, reserve = commitment.localChannelReserve(params), fees = if (params.localParams.isInitiator) fees else 0.sat))
      } else if (missingForReceiver < 0.msat) {
        if (params.localParams.isInitiator) {
          // receiver is not the channel initiator; it is ok if it can't maintain its channel_reserve for now, as long as its balance is increasing, which is the case if it is receiving a payment
        } else {
          return Left(RemoteCannotAffordFeesForNewHtlc(channelId, amount = cmd.amount, missing = -missingForReceiver.truncateToSatoshi, reserve = commitment.remoteChannelReserve(params), fees = fees))
        }
      }

      // We apply local *and* remote restrictions, to ensure both peers are happy with the resulting number of HTLCs.
      // NB: we need the `toSeq` because otherwise duplicate amountMsat would be removed (since outgoingHtlcs is a Set).
      val htlcValueInFlight = outgoingHtlcs.toSeq.map(_.amountMsat).sum
      val allowedHtlcValueInFlight = params.maxHtlcAmount
      if (allowedHtlcValueInFlight < htlcValueInFlight) {
        return Left(HtlcValueTooHighInFlight(channelId, maximum = allowedHtlcValueInFlight, actual = htlcValueInFlight))
      }
      if (Seq(params.localParams.maxAcceptedHtlcs, params.remoteParams.maxAcceptedHtlcs).min < outgoingHtlcs.size) {
        return Left(TooManyAcceptedHtlcs(channelId, maximum = Seq(params.localParams.maxAcceptedHtlcs, params.remoteParams.maxAcceptedHtlcs).min))
      }

      // If sending this htlc would overflow our dust exposure, we reject it.
      val maxDustExposure = feeConf.feerateToleranceFor(remoteNodeId).dustTolerance.maxExposure
      val localReduced = DustExposure.reduceForDustExposure(commitment.localCommit.spec, common1.localChanges.all, common1.remoteChanges.all)
      val localDustExposureAfterAdd = DustExposure.computeExposure(localReduced, params.localParams.dustLimit, params.commitmentFormat)
      if (localDustExposureAfterAdd > maxDustExposure) {
        return Left(LocalDustHtlcExposureTooHigh(channelId, maxDustExposure, localDustExposureAfterAdd))
      }
      val remoteReduced = DustExposure.reduceForDustExposure(remoteCommit1.spec, common1.remoteChanges.all, common1.localChanges.all)
      val remoteDustExposureAfterAdd = DustExposure.computeExposure(remoteReduced, params.remoteParams.dustLimit, params.commitmentFormat)
      if (remoteDustExposureAfterAdd > maxDustExposure) {
        return Left(RemoteDustHtlcExposureTooHigh(channelId, maxDustExposure, remoteDustExposureAfterAdd))
      }
    })

    Right(copy(common = common1), add)
  }

  def receiveAdd(add: UpdateAddHtlc, feeConf: OnChainFeeConf): Either[ChannelException, MetaCommitments] = {
    if (add.id != common.remoteNextHtlcId) {
      return Left(UnexpectedHtlcId(channelId, expected = common.remoteNextHtlcId, actual = add.id))
    }

    // we used to not enforce a strictly positive minimum, hence the max(1 msat)
    val htlcMinimum = params.localParams.htlcMinimum.max(1 msat)
    if (add.amountMsat < htlcMinimum) {
      return Left(HtlcValueTooSmall(channelId, minimum = htlcMinimum, actual = add.amountMsat))
    }

    val common1 = common.addRemoteProposal(add).copy(remoteNextHtlcId = common.remoteNextHtlcId + 1)

    // let's compute the current commitment *as seen by us* including this change
    commitments.foreach(commitment => {
      // we allowed mismatches between our feerates and our remote's as long as commitments didn't contain any HTLC at risk
      // we need to verify that we're not disagreeing on feerates anymore before accepting new HTLCs
      // NB: there may be a pending update_fee that hasn't been applied yet that needs to be taken into account
      val localFeeratePerKw = feeConf.getCommitmentFeerate(remoteNodeId, params.channelType, commitment.capacity, None)
      val remoteFeeratePerKw = commitment.localCommit.spec.commitTxFeerate +: common1.remoteChanges.all.collect { case f: UpdateFee => f.feeratePerKw }
      remoteFeeratePerKw.find(feerate => feeConf.feerateToleranceFor(remoteNodeId).isFeeDiffTooHigh(params.channelType, localFeeratePerKw, feerate)) match {
        case Some(feerate) => return Left(FeerateTooDifferent(channelId, localFeeratePerKw = localFeeratePerKw, remoteFeeratePerKw = feerate))
        case None =>
      }

      val reduced = CommitmentSpec.reduce(commitment.localCommit.spec, common1.localChanges.acked, common1.remoteChanges.proposed)
      val incomingHtlcs = reduced.htlcs.collect(DirectedHtlc.incoming)

      // note that the initiator pays the fee, so if sender != initiator, both sides will have to afford this payment
      val fees = Transactions.commitTxTotalCost(params.remoteParams.dustLimit, reduced, params.commitmentFormat)
      // NB: we don't enforce the funderFeeReserve (see sendAdd) because it would confuse a remote initiator that doesn't have this mitigation in place
      // We could enforce it once we're confident a large portion of the network implements it.
      val missingForSender = reduced.toRemote - commitment.remoteChannelReserve(params) - (if (params.localParams.isInitiator) 0.sat else fees)
      val missingForReceiver = reduced.toLocal - commitment.localChannelReserve(params) - (if (params.localParams.isInitiator) fees else 0.sat)
      if (missingForSender < 0.sat) {
        return Left(InsufficientFunds(channelId, amount = add.amountMsat, missing = -missingForSender.truncateToSatoshi, reserve = commitment.remoteChannelReserve(params), fees = if (params.localParams.isInitiator) 0.sat else fees))
      } else if (missingForReceiver < 0.sat) {
        if (params.localParams.isInitiator) {
          return Left(CannotAffordFees(channelId, missing = -missingForReceiver.truncateToSatoshi, reserve = commitment.localChannelReserve(params), fees = fees))
        } else {
          // receiver is not the channel initiator; it is ok if it can't maintain its channel_reserve for now, as long as its balance is increasing, which is the case if it is receiving a payment
        }
      }

      // NB: we need the `toSeq` because otherwise duplicate amountMsat would be removed (since incomingHtlcs is a Set).
      val htlcValueInFlight = incomingHtlcs.toSeq.map(_.amountMsat).sum
      if (params.localParams.maxHtlcValueInFlightMsat < htlcValueInFlight) {
        return Left(HtlcValueTooHighInFlight(channelId, maximum = params.localParams.maxHtlcValueInFlightMsat, actual = htlcValueInFlight))
      }

      if (incomingHtlcs.size > params.localParams.maxAcceptedHtlcs) {
        return Left(TooManyAcceptedHtlcs(channelId, maximum = params.localParams.maxAcceptedHtlcs))
      }
    })

    Right(copy(common = common1))
  }

  def sendFulfill(cmd: CMD_FULFILL_HTLC): Either[ChannelException, (MetaCommitments, UpdateFulfillHtlc)] =
    getIncomingHtlcCrossSigned(cmd.id) match {
      case Some(htlc) if Common.alreadyProposed(common.localChanges.proposed, htlc.id) =>
        // we have already sent a fail/fulfill for this htlc
        Left(UnknownHtlcId(channelId, cmd.id))
      case Some(htlc) if htlc.paymentHash == Crypto.sha256(cmd.r) =>
        val fulfill = UpdateFulfillHtlc(channelId, cmd.id, cmd.r)
        val common1 = common.addLocalProposal(fulfill)
        payment.Monitoring.Metrics.recordIncomingPaymentDistribution(params.remoteNodeId, htlc.amountMsat)
        Right((copy(common = common1), fulfill))
      case Some(_) => Left(InvalidHtlcPreimage(channelId, cmd.id))
      case None => Left(UnknownHtlcId(channelId, cmd.id))
    }

  def receiveFulfill(fulfill: UpdateFulfillHtlc): Either[ChannelException, (MetaCommitments, Origin, UpdateAddHtlc)] =
    getOutgoingHtlcCrossSigned(fulfill.id) match {
      case Some(htlc) if htlc.paymentHash == Crypto.sha256(fulfill.paymentPreimage) => common.originChannels.get(fulfill.id) match {
        case Some(origin) =>
          payment.Monitoring.Metrics.recordOutgoingPaymentDistribution(params.remoteNodeId, htlc.amountMsat)
          val common1 = common.addRemoteProposal(fulfill)
          Right(copy(common = common1), origin, htlc)
        case None => Left(UnknownHtlcId(channelId, fulfill.id))
      }
      case Some(_) => Left(InvalidHtlcPreimage(channelId, fulfill.id))
      case None => Left(UnknownHtlcId(channelId, fulfill.id))
    }

  def sendFail(cmd: CMD_FAIL_HTLC, nodeSecret: PrivateKey): Either[ChannelException, (MetaCommitments, HtlcFailureMessage)] =
    getIncomingHtlcCrossSigned(cmd.id) match {
      case Some(htlc) if Common.alreadyProposed(common.localChanges.proposed, htlc.id) =>
        // we have already sent a fail/fulfill for this htlc
        Left(UnknownHtlcId(channelId, cmd.id))
      case Some(htlc) =>
        // we need the shared secret to build the error packet
        OutgoingPaymentPacket.buildHtlcFailure(nodeSecret, cmd, htlc).map(fail => (copy(common = common.addLocalProposal(fail)), fail))
      case None => Left(UnknownHtlcId(channelId, cmd.id))
    }

  def sendFailMalformed(cmd: CMD_FAIL_MALFORMED_HTLC): Either[ChannelException, (MetaCommitments, UpdateFailMalformedHtlc)] = {
    // BADONION bit must be set in failure_code
    if ((cmd.failureCode & FailureMessageCodecs.BADONION) == 0) {
      Left(InvalidFailureCode(channelId))
    } else {
      getIncomingHtlcCrossSigned(cmd.id) match {
        case Some(htlc) if Common.alreadyProposed(common.localChanges.proposed, htlc.id) =>
          // we have already sent a fail/fulfill for this htlc
          Left(UnknownHtlcId(channelId, cmd.id))
        case Some(_) =>
          val fail = UpdateFailMalformedHtlc(channelId, cmd.id, cmd.onionHash, cmd.failureCode)
          val common1 = common.addLocalProposal(fail)
          Right((copy(common = common1), fail))
        case None => Left(UnknownHtlcId(channelId, cmd.id))
      }
    }
  }

  def receiveFail(fail: UpdateFailHtlc): Either[ChannelException, (MetaCommitments, Origin, UpdateAddHtlc)] =
    getOutgoingHtlcCrossSigned(fail.id) match {
      case Some(htlc) => common.originChannels.get(fail.id) match {
        case Some(origin) => Right(copy(common = common.addRemoteProposal(fail)), origin, htlc)
        case None => Left(UnknownHtlcId(channelId, fail.id))
      }
      case None => Left(UnknownHtlcId(channelId, fail.id))
    }

  def receiveFailMalformed(fail: UpdateFailMalformedHtlc): Either[ChannelException, (MetaCommitments, Origin, UpdateAddHtlc)] = {
    // A receiving node MUST fail the channel if the BADONION bit in failure_code is not set for update_fail_malformed_htlc.
    if ((fail.failureCode & FailureMessageCodecs.BADONION) == 0) {
      Left(InvalidFailureCode(channelId))
    } else {
      getOutgoingHtlcCrossSigned(fail.id) match {
        case Some(htlc) => common.originChannels.get(fail.id) match {
          case Some(origin) => Right(copy(common = common.addRemoteProposal(fail)), origin, htlc)
          case None => Left(UnknownHtlcId(channelId, fail.id))
        }
        case None => Left(UnknownHtlcId(channelId, fail.id))
      }
    }
  }

  def sendFee(cmd: CMD_UPDATE_FEE, feeConf: OnChainFeeConf): Either[ChannelException, (MetaCommitments, UpdateFee)] = {
    if (!params.localParams.isInitiator) {
      Left(NonInitiatorCannotSendUpdateFee(channelId))
    } else {
      // let's compute the current commitment *as seen by them* with this change taken into account
      val fee = UpdateFee(channelId, cmd.feeratePerKw)
      // update_fee replace each other, so we can remove previous ones
      val common1 = common.copy(localChanges = common.localChanges.copy(proposed = common.localChanges.proposed.filterNot(_.isInstanceOf[UpdateFee]) :+ fee))
      commitments.foreach(commitment => {
        val reduced = CommitmentSpec.reduce(commitment.remoteCommit.spec, common1.remoteChanges.acked, common1.localChanges.proposed)
        // a node cannot spend pending incoming htlcs, and need to keep funds above the reserve required by the counterparty, after paying the fee
        // we look from remote's point of view, so if local is initiator remote doesn't pay the fees
        val fees = Transactions.commitTxTotalCost(params.remoteParams.dustLimit, reduced, params.commitmentFormat)
        val missing = reduced.toRemote.truncateToSatoshi - commitment.localChannelReserve(params) - fees
        if (missing < 0.sat) {
          return Left(CannotAffordFees(channelId, missing = -missing, reserve = commitment.localChannelReserve(params), fees = fees))
        }
        // if we would overflow our dust exposure with the new feerate, we avoid sending this fee update
        if (feeConf.feerateToleranceFor(remoteNodeId).dustTolerance.closeOnUpdateFeeOverflow) {
          val maxDustExposure = feeConf.feerateToleranceFor(remoteNodeId).dustTolerance.maxExposure
          // this is the commitment as it would be if our update_fee was immediately signed by both parties (it is only an
          // estimate because there can be concurrent updates)
          val localReduced = DustExposure.reduceForDustExposure(commitment.localCommit.spec, common1.localChanges.all, common1.remoteChanges.all)
          val localDustExposureAfterFeeUpdate = DustExposure.computeExposure(localReduced, cmd.feeratePerKw, params.localParams.dustLimit, params.commitmentFormat)
          if (localDustExposureAfterFeeUpdate > maxDustExposure) {
            return Left(LocalDustHtlcExposureTooHigh(channelId, maxDustExposure, localDustExposureAfterFeeUpdate))
          }
          val remoteReduced = DustExposure.reduceForDustExposure(commitment.remoteCommit.spec, common1.remoteChanges.all, common1.localChanges.all)
          val remoteDustExposureAfterFeeUpdate = DustExposure.computeExposure(remoteReduced, cmd.feeratePerKw, params.remoteParams.dustLimit, params.commitmentFormat)
          if (remoteDustExposureAfterFeeUpdate > maxDustExposure) {
            return Left(RemoteDustHtlcExposureTooHigh(channelId, maxDustExposure, remoteDustExposureAfterFeeUpdate))
          }
        }
      })
      Right(copy(common = common1), fee)
    }
  }

  def receiveFee(fee: UpdateFee, feeConf: OnChainFeeConf)(implicit log: LoggingAdapter): Either[ChannelException, MetaCommitments] = {
    if (params.localParams.isInitiator) {
      Left(NonInitiatorCannotSendUpdateFee(channelId))
    } else if (fee.feeratePerKw < FeeratePerKw.MinimumFeeratePerKw) {
      Left(FeerateTooSmall(channelId, remoteFeeratePerKw = fee.feeratePerKw))
    } else {
      Metrics.RemoteFeeratePerKw.withoutTags().record(fee.feeratePerKw.toLong)
      // let's compute the current commitment *as seen by us* including this change
      // update_fee replace each other, so we can remove previous ones
      val common1 = common.copy(remoteChanges = common.remoteChanges.copy(proposed = common.remoteChanges.proposed.filterNot(_.isInstanceOf[UpdateFee]) :+ fee))
      commitments.foreach(commitment => {
        val localFeeratePerKw = feeConf.getCommitmentFeerate(remoteNodeId, params.channelType, commitment.capacity, None)
        log.info("remote feeratePerKw={}, local feeratePerKw={}, ratio={}", fee.feeratePerKw, localFeeratePerKw, fee.feeratePerKw.toLong.toDouble / localFeeratePerKw.toLong)
        if (feeConf.feerateToleranceFor(remoteNodeId).isFeeDiffTooHigh(params.channelType, localFeeratePerKw, fee.feeratePerKw) && commitment.hasPendingOrProposedHtlcs(common)) {
          return Left(FeerateTooDifferent(channelId, localFeeratePerKw = localFeeratePerKw, remoteFeeratePerKw = fee.feeratePerKw))
        } else {
          // NB: we check that the initiator can afford this new fee even if spec allows to do it at next signature
          // It is easier to do it here because under certain (race) conditions spec allows a lower-than-normal fee to be paid,
          // and it would be tricky to check if the conditions are met at signing
          // (it also means that we need to check the fee of the initial commitment tx somewhere)
          val reduced = CommitmentSpec.reduce(commitment.localCommit.spec, common1.localChanges.acked, common1.remoteChanges.proposed)
          // a node cannot spend pending incoming htlcs, and need to keep funds above the reserve required by the counterparty, after paying the fee
          val fees = Transactions.commitTxTotalCost(params.localParams.dustLimit, reduced, params.commitmentFormat)
          val missing = reduced.toRemote.truncateToSatoshi - commitment.remoteChannelReserve(params) - fees
          if (missing < 0.sat) {
            return Left(CannotAffordFees(channelId, missing = -missing, reserve = commitment.remoteChannelReserve(params), fees = fees))
          }
          // if we would overflow our dust exposure with the new feerate, we reject this fee update
          if (feeConf.feerateToleranceFor(remoteNodeId).dustTolerance.closeOnUpdateFeeOverflow) {
            val maxDustExposure = feeConf.feerateToleranceFor(remoteNodeId).dustTolerance.maxExposure
            val localReduced = DustExposure.reduceForDustExposure(commitment.localCommit.spec, common1.localChanges.all, common1.remoteChanges.all)
            val localDustExposureAfterFeeUpdate = DustExposure.computeExposure(localReduced, fee.feeratePerKw, params.localParams.dustLimit, params.commitmentFormat)
            if (localDustExposureAfterFeeUpdate > maxDustExposure) {
              return Left(LocalDustHtlcExposureTooHigh(channelId, maxDustExposure, localDustExposureAfterFeeUpdate))
            }
            // this is the commitment as it would be if their update_fee was immediately signed by both parties (it is only an
            // estimate because there can be concurrent updates)
            val remoteReduced = DustExposure.reduceForDustExposure(commitment.remoteCommit.spec, common1.remoteChanges.all, common1.localChanges.all)
            val remoteDustExposureAfterFeeUpdate = DustExposure.computeExposure(remoteReduced, fee.feeratePerKw, params.remoteParams.dustLimit, params.commitmentFormat)
            if (remoteDustExposureAfterFeeUpdate > maxDustExposure) {
              return Left(RemoteDustHtlcExposureTooHigh(channelId, maxDustExposure, remoteDustExposureAfterFeeUpdate))
            }
          }
        }
      })
      Right(copy(common = common1))
    }
  }

  def sendCommit(keyManager: ChannelKeyManager)(implicit log: LoggingAdapter): Either[ChannelException, (MetaCommitments, CommitSig)] = {
    common.remoteNextCommitInfo match {
      case Right(_) if !common.localHasChanges =>
        Left(CannotSignWithoutChanges(channelId))
      case Right(remoteNextPerCommitmentPoint) =>
        val (commitments1, commitSigs) = commitments.map(c => {
          // remote commitment will includes all local changes + remote acked changes
          val spec = CommitmentSpec.reduce(c.remoteCommit.spec, common.remoteChanges.acked, common.localChanges.proposed)
          val (remoteCommitTx, htlcTxs) = Commitment.makeRemoteTxs(keyManager, params.channelConfig, params.channelFeatures, c.remoteCommit.index + 1, params.localParams, params.remoteParams, c.commitInput, remoteNextPerCommitmentPoint, spec)
          val sig = keyManager.sign(remoteCommitTx, keyManager.fundingPublicKey(params.localParams.fundingKeyPath), Transactions.TxOwner.Remote, params.commitmentFormat)

          val sortedHtlcTxs: Seq[TransactionWithInputInfo] = htlcTxs.sortBy(_.input.outPoint.index)
          val channelKeyPath = keyManager.keyPath(params.localParams, params.channelConfig)
          val htlcSigs = sortedHtlcTxs.map(keyManager.sign(_, keyManager.htlcPoint(channelKeyPath), remoteNextPerCommitmentPoint, Transactions.TxOwner.Remote, params.commitmentFormat))

          // NB: IN/OUT htlcs are inverted because this is the remote commit
          log.info(s"built remote commit number=${c.remoteCommit.index + 1} toLocalMsat=${spec.toLocal.toLong} toRemoteMsat=${spec.toRemote.toLong} htlc_in={} htlc_out={} feeratePerKw=${spec.commitTxFeerate} txid=${remoteCommitTx.tx.txid} tx={}", spec.htlcs.collect(DirectedHtlc.outgoing).map(_.id).mkString(","), spec.htlcs.collect(DirectedHtlc.incoming).map(_.id).mkString(","), remoteCommitTx.tx)
          Metrics.recordHtlcsInFlight(spec, c.remoteCommit.spec)

          val commitment1 = c.copy(nextRemoteCommit_opt = Some(RemoteCommit(c.remoteCommit.index + 1, spec, remoteCommitTx.tx.txid, remoteNextPerCommitmentPoint)))
          (commitment1, AlternativeCommitSig(commitment1.fundingTxId, sig, htlcSigs.toList))
        }).unzip
        val commitSig = if (commitments1.size > 1) {
          // we set all commit_sigs as tlv of the first commit_sig (the first sigs will be duplicated)
          CommitSig(channelId, commitSigs.head.signature, commitSigs.head.htlcSignatures, TlvStream(AlternativeCommitSigsTlv(commitSigs)))
        } else {
          CommitSig(channelId, commitSigs.head.signature, commitSigs.head.htlcSignatures)
        }
        val metaCommitments1 = copy(
          common = common.copy(
            localChanges = common.localChanges.copy(proposed = Nil, signed = common.localChanges.proposed),
            remoteChanges = common.remoteChanges.copy(acked = Nil, signed = common.remoteChanges.acked),
            remoteNextCommitInfo = Left(WaitForRev(commitSig, common.localCommitIndex)),
          ),
          commitments = commitments1,
        )
        Right(metaCommitments1, commitSig)
      case Left(_) =>
        Left(CannotSignBeforeRevocation(channelId))
    }
  }

  def receiveCommit(commit: CommitSig, keyManager: ChannelKeyManager)(implicit log: LoggingAdapter): Either[ChannelException, (MetaCommitments, RevokeAndAck)] = {
    // they sent us a signature for *their* view of *our* next commit tx
    // so in terms of rev.hashes and indexes we have:
    // ourCommit.index -> our current revocation hash, which is about to become our old revocation hash
    // ourCommit.index + 1 -> our next revocation hash, used by *them* to build the sig we've just received, and which
    // is about to become our current revocation hash
    // ourCommit.index + 2 -> which is about to become our next revocation hash
    // we will reply to this sig with our old revocation hash preimage (at index) and our next revocation hash (at index + 1)
    // and will increment our index

    // lnd sometimes sends a new signature without any changes, which is a (harmless) spec violation
    if (!common.remoteHasChanges) {
      //  throw CannotSignWithoutChanges(channelId)
      log.warning("received a commit sig with no changes (probably coming from lnd)")
    }

    val sigs: Map[ByteVector32, CommitSig] = commit.alternativeCommitSigs match {
      case Nil => Map(commitments.head.fundingTxId -> commit) // no alternative sigs: we use the commit_sig message as-is, we assume it is for the first commitments
      case alternativeCommitSigs =>
        // if there are alternative sigs, then we expand the sigs to build n individual commit_sig that we will apply to the corresponding commitments
        alternativeCommitSigs.map { altSig =>
          altSig.fundingTxId -> commit
            .modify(_.signature).setTo(altSig.signature)
            .modify(_.htlcSignatures).setTo(altSig.htlcSignatures)
            .modify(_.tlvStream.records).using(_.filterNot(_.isInstanceOf[AlternativeCommitSigsTlv]))
        }.toMap
    }

    val channelKeyPath = keyManager.keyPath(params.localParams, params.channelConfig)
    val commitments1 = commitments.map(c => {
      val spec = CommitmentSpec.reduce(c.localCommit.spec, common.localChanges.acked, common.remoteChanges.proposed)
      val localPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, c.localCommit.index + 1)
      val (localCommitTx, htlcTxs) = Commitment.makeLocalTxs(keyManager, params.channelConfig, params.channelFeatures, c.localCommit.index + 1, params.localParams, params.remoteParams, c.commitInput, localPerCommitmentPoint, spec)
      sigs.get(c.fundingTxId) match {
        case Some(sig) =>
          log.info(s"built local commit number=${c.localCommit.index + 1} toLocalMsat=${spec.toLocal.toLong} toRemoteMsat=${spec.toRemote.toLong} htlc_in={} htlc_out={} feeratePerKw=${spec.commitTxFeerate} txid=${localCommitTx.tx.txid} tx={}", spec.htlcs.collect(DirectedHtlc.incoming).map(_.id).mkString(","), spec.htlcs.collect(DirectedHtlc.outgoing).map(_.id).mkString(","), localCommitTx.tx)
          if (!Transactions.checkSig(localCommitTx, sig.signature, params.remoteParams.fundingPubKey, Transactions.TxOwner.Remote, params.commitmentFormat)) {
            return Left(InvalidCommitmentSignature(channelId, localCommitTx.tx.txid))
          }

          val sortedHtlcTxs: Seq[Transactions.HtlcTx] = htlcTxs.sortBy(_.input.outPoint.index)
          if (sig.htlcSignatures.size != sortedHtlcTxs.size) {
            return Left(HtlcSigCountMismatch(channelId, sortedHtlcTxs.size, sig.htlcSignatures.size))
          }

          val remoteHtlcPubkey = Generators.derivePubKey(params.remoteParams.htlcBasepoint, localPerCommitmentPoint)
          val htlcTxsAndRemoteSigs = sortedHtlcTxs.zip(sig.htlcSignatures).toList.map {
            case (htlcTx: Transactions.HtlcTx, remoteSig) =>
              if (!Transactions.checkSig(htlcTx, remoteSig, remoteHtlcPubkey, Transactions.TxOwner.Remote, params.commitmentFormat)) {
                return Left(InvalidHtlcSignature(channelId, htlcTx.tx.txid))
              }
              HtlcTxAndRemoteSig(htlcTx, remoteSig)
          }

          // update our commitment data
          c.copy(localCommit = LocalCommit(
            index = c.localCommit.index + 1,
            spec,
            commitTxAndRemoteSig = CommitTxAndRemoteSig(localCommitTx, sig.signature),
            htlcTxsAndRemoteSigs = htlcTxsAndRemoteSigs
          ))
        case None =>
          return Left(InvalidCommitmentSignature(channelId, localCommitTx.tx.txid))
      }
    })

    // we will send our revocation preimage + our next revocation hash
    val localPerCommitmentSecret = keyManager.commitmentSecret(channelKeyPath, common.localCommitIndex)
    val localNextPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, common.localCommitIndex + 2)
    val revocation = RevokeAndAck(
      channelId = channelId,
      perCommitmentSecret = localPerCommitmentSecret,
      nextPerCommitmentPoint = localNextPerCommitmentPoint
    )

    val metaCommitments1 = copy(
      common = common.copy(
        localChanges = common.localChanges.copy(acked = Nil),
        remoteChanges = common.remoteChanges.copy(proposed = Nil, acked = common.remoteChanges.acked ++ common.remoteChanges.proposed),
        localCommitIndex = common.localCommitIndex + 1,
      ),
      commitments = commitments1
    )

    Right(metaCommitments1, revocation)
  }

  def receiveRevocation(revocation: RevokeAndAck, maxDustExposure: Satoshi): Either[ChannelException, (MetaCommitments, Seq[PostRevocationAction])] = {
    // we receive a revocation because we just sent them a sig for their next commit tx
    common.remoteNextCommitInfo match {
      case Left(_) if revocation.perCommitmentSecret.publicKey != commitments.head.remoteCommit.remotePerCommitmentPoint =>
        Left(InvalidRevocation(channelId))
      case Left(_) =>
        // NB: we are supposed to keep nextRemoteCommit_opt consistent with remoteNextCommitInfo: this should exist.
        val theirFirstNextCommitSpec = commitments.head.nextRemoteCommit_opt.get.spec
        // Since htlcs are shared across all commitments, we generate the actions only once based on the first commitment.
        val receivedHtlcs = common.remoteChanges.signed.collect {
          // we forward adds downstream only when they have been committed by both sides
          // it always happen when we receive a revocation, because they send the add, then they sign it, then we sign it
          case add: UpdateAddHtlc => add
        }
        val failedHtlcs = common.remoteChanges.signed.collect {
          // same for fails: we need to make sure that they are in neither commitment before propagating the fail upstream
          case fail: UpdateFailHtlc =>
            val origin = common.originChannels(fail.id)
            val add = commitments.head.remoteCommit.spec.findIncomingHtlcById(fail.id).map(_.add).get
            RES_ADD_SETTLED(origin, add, HtlcResult.RemoteFail(fail))
          // same as above
          case fail: UpdateFailMalformedHtlc =>
            val origin = common.originChannels(fail.id)
            val add = commitments.head.remoteCommit.spec.findIncomingHtlcById(fail.id).map(_.add).get
            RES_ADD_SETTLED(origin, add, HtlcResult.RemoteFailMalformed(fail))
        }
        val (acceptedHtlcs, rejectedHtlcs) = {
          // the received htlcs have already been added to commitments (they've been signed by our peer), and may already
          // overflow our dust exposure (we cannot prevent them from adding htlcs): we artificially remove them before
          // deciding which we'll keep and relay and which we'll fail without relaying.
          val localSpecWithoutNewHtlcs = commitments.head.localCommit.spec.copy(htlcs = commitments.head.localCommit.spec.htlcs.filter {
            case IncomingHtlc(add) if receivedHtlcs.contains(add) => false
            case _ => true
          })
          val remoteSpecWithoutNewHtlcs = theirFirstNextCommitSpec.copy(htlcs = theirFirstNextCommitSpec.htlcs.filter {
            case OutgoingHtlc(add) if receivedHtlcs.contains(add) => false
            case _ => true
          })
          val localReduced = DustExposure.reduceForDustExposure(localSpecWithoutNewHtlcs, common.localChanges.all, common.remoteChanges.acked)
          val localCommitDustExposure = DustExposure.computeExposure(localReduced, params.localParams.dustLimit, params.commitmentFormat)
          val remoteReduced = DustExposure.reduceForDustExposure(remoteSpecWithoutNewHtlcs, common.remoteChanges.acked, common.localChanges.all)
          val remoteCommitDustExposure = DustExposure.computeExposure(remoteReduced, params.remoteParams.dustLimit, params.commitmentFormat)
          // we sort incoming htlcs by decreasing amount: we want to prioritize higher amounts.
          val sortedReceivedHtlcs = receivedHtlcs.sortBy(_.amountMsat).reverse
          DustExposure.filterBeforeForward(
            maxDustExposure,
            localReduced,
            params.localParams.dustLimit,
            localCommitDustExposure,
            remoteReduced,
            params.remoteParams.dustLimit,
            remoteCommitDustExposure,
            sortedReceivedHtlcs,
            params.commitmentFormat)
        }
        val actions = acceptedHtlcs.map(add => PostRevocationAction.RelayHtlc(add)) ++
          rejectedHtlcs.map(add => PostRevocationAction.RejectHtlc(add)) ++
          failedHtlcs.map(res => PostRevocationAction.RelayFailure(res))
        // the outgoing following htlcs have been completed (fulfilled or failed) when we received this revocation
        // they have been removed from both local and remote commitment
        // (since fulfill/fail are sent by remote, they are (1) signed by them, (2) revoked by us, (3) signed by us, (4) revoked by them
        val completedOutgoingHtlcs = commitments.head.remoteCommit.spec.htlcs.collect(DirectedHtlc.incoming).map(_.id) -- theirFirstNextCommitSpec.htlcs.collect(DirectedHtlc.incoming).map(_.id)
        // we remove the newly completed htlcs from the origin map
        val originChannels1 = common.originChannels -- completedOutgoingHtlcs
        val commitments1 = commitments.map(c => c.copy(
          remoteCommit = c.nextRemoteCommit_opt.get,
          nextRemoteCommit_opt = None,
        ))
        val metaCommitments1 = copy(
          common = common.copy(
            localChanges = common.localChanges.copy(signed = Nil, acked = common.localChanges.acked ++ common.localChanges.signed),
            remoteChanges = common.remoteChanges.copy(signed = Nil),
            remoteCommitIndex = common.remoteCommitIndex + 1,
            remoteNextCommitInfo = Right(revocation.nextPerCommitmentPoint),
            remotePerCommitmentSecrets = common.remotePerCommitmentSecrets.addHash(revocation.perCommitmentSecret.value, 0xFFFFFFFFFFFFL - common.remoteCommitIndex),
            originChannels = originChannels1
          ),
          commitments = commitments1,
        )
        Right(metaCommitments1, actions)
      case Right(_) =>
        Left(UnexpectedRevocation(channelId))
    }
  }

  def discardUnsignedUpdates()(implicit log: LoggingAdapter): MetaCommitments = {
    this.copy(common = common.discardUnsignedUpdates())
  }

}

object MetaCommitments {
  /** A 1:1 conversion helper to facilitate migration, nothing smart here. */
  def apply(commitments: Commitments): MetaCommitments = MetaCommitments(
    params = commitments.params,
    common = commitments.common,
    commitments = commitments.commitment +: Nil
  )

  // @formatter:off
  sealed trait PostRevocationAction
  object PostRevocationAction {
    case class RelayHtlc(incomingHtlc: UpdateAddHtlc) extends PostRevocationAction
    case class RejectHtlc(incomingHtlc: UpdateAddHtlc) extends PostRevocationAction
    case class RelayFailure(result: RES_ADD_SETTLED[Origin, HtlcResult]) extends PostRevocationAction
  }
  // @formatter:on
}