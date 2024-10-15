package fr.acinq.eclair.channel

import akka.event.{DiagnosticLoggingAdapter, LoggingAdapter}
import fr.acinq.bitcoin.crypto.musig2.{IndividualNonce, SecretNonce}
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, Crypto, Musig2, Satoshi, SatoshiLong, Script, Transaction, TxId}
import fr.acinq.eclair.blockchain.fee.{FeeratePerByte, FeeratePerKw, FeeratesPerKw, OnChainFeeConf}
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.fsm.Channel.ChannelConf
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder.SharedTransaction
import fr.acinq.eclair.crypto.keymanager.ChannelKeyManager
import fr.acinq.eclair.crypto.{Generators, ShaChain}
import fr.acinq.eclair.payment.OutgoingPaymentPacket
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{BlockHeight, CltvExpiry, CltvExpiryDelta, Features, MilliSatoshi, MilliSatoshiLong, payment}
import grizzled.slf4j.Logging
import scodec.bits.ByteVector

/** Static channel parameters shared by all commitments. */
case class ChannelParams(channelId: ByteVector32,
                         channelConfig: ChannelConfig,
                         channelFeatures: ChannelFeatures,
                         localParams: LocalParams, remoteParams: RemoteParams,
                         channelFlags: ChannelFlags) {

  require(channelFeatures.paysDirectlyToWallet == localParams.walletStaticPaymentBasepoint.isDefined, s"localParams.walletStaticPaymentBasepoint must be defined only for commitments that pay directly to our wallet (channel features: $channelFeatures")
  require(channelFeatures.hasFeature(Features.DualFunding) == localParams.initialRequestedChannelReserve_opt.isEmpty, "custom local channel reserve is incompatible with dual-funded channels")
  require(channelFeatures.hasFeature(Features.DualFunding) == remoteParams.initialRequestedChannelReserve_opt.isEmpty, "custom remote channel reserve is incompatible with dual-funded channels")

  val commitmentFormat: CommitmentFormat = channelFeatures.commitmentFormat
  val announceChannel: Boolean = channelFlags.announceChannel

  val localNodeId: PublicKey = localParams.nodeId
  val remoteNodeId: PublicKey = remoteParams.nodeId

  // We can safely cast to millisatoshis since we verify that it's less than a valid millisatoshi amount.
  val maxHtlcAmount: MilliSatoshi = remoteParams.maxHtlcValueInFlightMsat.toBigInt.min(localParams.maxHtlcValueInFlightMsat.toLong).toLong.msat

  /**
   * We update local/global features at reconnection
   */
  def updateFeatures(localInit: Init, remoteInit: Init): ChannelParams = copy(
    localParams = localParams.copy(initFeatures = localInit.features),
    remoteParams = remoteParams.copy(initFeatures = remoteInit.features)
  )

  /**
   * As funder we trust ourselves to not double spend funding txs: we could always use a zero-confirmation watch,
   * but we need a scid to send the initial channel_update and remote may not provide an alias. That's why we always
   * wait for one conf, except if the channel has the zero-conf feature (because presumably the peer will send an
   * alias in that case).
   */
  def minDepthFunder: Option[Long] = {
    if (localParams.initFeatures.hasFeature(Features.ZeroConf)) {
      None
    } else {
      Some(1)
    }
  }

  /**
   * Returns the number of confirmations needed to safely handle a funding transaction with remote inputs. We make sure
   * the cumulative block reward largely exceeds the channel size, because an attacker that could create a reorg would
   * be able to steal the entire channel funding, but would likely miss block rewards during that process, making it
   * economically irrational for them.
   *
   * @param fundingAmount funding amount of the channel
   * @return number of confirmations needed, if any
   */
  def minDepthFundee(defaultMinDepth: Int, fundingAmount: Satoshi): Option[Long] =
    if (localParams.initFeatures.hasFeature(Features.ZeroConf)) {
      None // zero-conf stay zero-conf, whatever the funding amount is
    } else {
      Some(ChannelParams.minDepthScaled(defaultMinDepth, fundingAmount))
    }

  /**
   * When using dual funding or splices, we wait for multiple confirmations even if we're the initiator because:
   *  - our peer may also contribute to the funding transaction, even if they don't contribute to the channel funding amount
   *  - even if they don't, we may RBF the transaction and don't want to handle reorgs
   */
  def minDepthDualFunding(defaultMinDepth: Int, sharedTx: SharedTransaction): Option[Long] = {
    if (localParams.initFeatures.hasFeature(Features.ZeroConf)) {
      None
    } else {
      Some(ChannelParams.minDepthScaled(defaultMinDepth, sharedTx.sharedOutput.amount))
    }
  }

  /** Channel reserve that applies to our funds. */
  def localChannelReserveForCapacity(capacity: Satoshi, isSplice: Boolean): Satoshi = if (channelFeatures.hasFeature(Features.DualFunding) || isSplice) {
    (capacity / 100).max(remoteParams.dustLimit)
  } else {
    remoteParams.initialRequestedChannelReserve_opt.get // this is guarded by a require() in Params
  }

  /** Channel reserve that applies to our peer's funds. */
  def remoteChannelReserveForCapacity(capacity: Satoshi, isSplice: Boolean): Satoshi = if (channelFeatures.hasFeature(Features.DualFunding) || isSplice) {
    (capacity / 100).max(localParams.dustLimit)
  } else {
    localParams.initialRequestedChannelReserve_opt.get // this is guarded by a require() in Params
  }

  /**
   * @param localScriptPubKey local script pubkey (provided in CMD_CLOSE, as an upfront shutdown script, or set to the current final onchain script)
   * @return an exception if the provided script is not valid
   */
  def validateLocalShutdownScript(localScriptPubKey: ByteVector): Either[ChannelException, ByteVector] = {
    // to check whether shutdown_any_segwit is active we check features in local and remote parameters, which are negotiated each time we connect to our peer.
    // README: if we set our bitcoin node to generate taproot addresses and our peer does not support option_shutdown_anysegwit, we will not be able to mutual-close
    // channels as the isValidFinalScriptPubkey() check would fail.
    val allowAnySegwit = Features.canUseFeature(localParams.initFeatures, remoteParams.initFeatures, Features.ShutdownAnySegwit)
    val mustUseUpfrontShutdownScript = channelFeatures.hasFeature(Features.UpfrontShutdownScript)
    // we only enforce using the pre-generated shutdown script if option_upfront_shutdown_script is set
    if (mustUseUpfrontShutdownScript && localParams.upfrontShutdownScript_opt.exists(_ != localScriptPubKey)) Left(InvalidFinalScript(channelId))
    else if (!Closing.MutualClose.isValidFinalScriptPubkey(localScriptPubKey, allowAnySegwit)) Left(InvalidFinalScript(channelId))
    else Right(localScriptPubKey)
  }

  /**
   * @param remoteScriptPubKey remote script included in a Shutdown message
   * @return an exception if the provided script is not valid
   */
  def validateRemoteShutdownScript(remoteScriptPubKey: ByteVector): Either[ChannelException, ByteVector] = {
    // to check whether shutdown_any_segwit is active we check features in local and remote parameters, which are negotiated each time we connect to our peer.
    val allowAnySegwit = Features.canUseFeature(localParams.initFeatures, remoteParams.initFeatures, Features.ShutdownAnySegwit)
    val mustUseUpfrontShutdownScript = channelFeatures.hasFeature(Features.UpfrontShutdownScript)
    // we only enforce using the pre-generated shutdown script if option_upfront_shutdown_script is set
    if (mustUseUpfrontShutdownScript && remoteParams.upfrontShutdownScript_opt.exists(_ != remoteScriptPubKey)) Left(InvalidFinalScript(channelId))
    else if (!Closing.MutualClose.isValidFinalScriptPubkey(remoteScriptPubKey, allowAnySegwit)) Left(InvalidFinalScript(channelId))
    else Right(remoteScriptPubKey)
  }

}

object ChannelParams {
  def minDepthScaled(defaultMinDepth: Int, amount: Satoshi): Int = {
    if (amount <= Channel.MAX_FUNDING_WITHOUT_WUMBO) {
      // small amount: not scaled
      defaultMinDepth
    } else {
      val blockReward = 6.25 // this is true as of ~May 2020, but will be too large after 2024
      val scalingFactor = 15
      val blocksToReachFunding = (((scalingFactor * amount.toBtc.toDouble) / blockReward).ceil + 1).toInt
      defaultMinDepth.max(blocksToReachFunding)
    }
  }
}

// @formatter:off
case class LocalChanges(proposed: List[UpdateMessage], signed: List[UpdateMessage], acked: List[UpdateMessage]) {
  def all: List[UpdateMessage] = proposed ++ signed ++ acked
}
case class RemoteChanges(proposed: List[UpdateMessage], acked: List[UpdateMessage], signed: List[UpdateMessage]) {
  def all: List[UpdateMessage] = proposed ++ signed ++ acked
}
// @formatter:on

/** Changes are applied to all commitments, and must be be valid for all commitments. */
case class CommitmentChanges(localChanges: LocalChanges, remoteChanges: RemoteChanges, localNextHtlcId: Long, remoteNextHtlcId: Long) {

  import CommitmentChanges._

  val localHasChanges: Boolean = remoteChanges.acked.nonEmpty || localChanges.proposed.nonEmpty
  val remoteHasChanges: Boolean = localChanges.acked.nonEmpty || remoteChanges.proposed.nonEmpty
  val localHasUnsignedOutgoingHtlcs: Boolean = localChanges.proposed.collectFirst { case u: UpdateAddHtlc => u }.isDefined
  val remoteHasUnsignedOutgoingHtlcs: Boolean = remoteChanges.proposed.collectFirst { case u: UpdateAddHtlc => u }.isDefined
  val localHasUnsignedOutgoingUpdateFee: Boolean = localChanges.proposed.collectFirst { case u: UpdateFee => u }.isDefined
  val remoteHasUnsignedOutgoingUpdateFee: Boolean = remoteChanges.proposed.collectFirst { case u: UpdateFee => u }.isDefined

  def addLocalProposal(proposal: UpdateMessage): CommitmentChanges = copy(localChanges = localChanges.copy(proposed = localChanges.proposed :+ proposal))

  def addRemoteProposal(proposal: UpdateMessage): CommitmentChanges = copy(remoteChanges = remoteChanges.copy(proposed = remoteChanges.proposed :+ proposal))

  /** When reconnecting, we drop all unsigned changes. */
  def discardUnsignedUpdates()(implicit log: LoggingAdapter): CommitmentChanges = {
    log.debug("discarding proposed OUT: {}", localChanges.proposed.map(msg2String(_)).mkString(","))
    log.debug("discarding proposed IN: {}", remoteChanges.proposed.map(msg2String(_)).mkString(","))
    val changes1 = copy(
      localChanges = localChanges.copy(proposed = Nil),
      remoteChanges = remoteChanges.copy(proposed = Nil),
      localNextHtlcId = localNextHtlcId - localChanges.proposed.collect { case u: UpdateAddHtlc => u }.size,
      remoteNextHtlcId = remoteNextHtlcId - remoteChanges.proposed.collect { case u: UpdateAddHtlc => u }.size)
    log.debug(s"localNextHtlcId=$localNextHtlcId->${changes1.localNextHtlcId}")
    log.debug(s"remoteNextHtlcId=$remoteNextHtlcId->${changes1.remoteNextHtlcId}")
    changes1
  }
}

object CommitmentChanges {
  def init(): CommitmentChanges = CommitmentChanges(LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil, Nil), 0, 0)

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
    case _: UpdateFee => "fee"
    case _: CommitSig => "sig"
    case _: RevokeAndAck => "rev"
    case _: Error => "err"
    case _: ChannelReady => "channel_ready"
    case _ => "???"
  }
}

case class HtlcTxAndRemoteSig(htlcTx: HtlcTx, remoteSig: ByteVector64)

case class PartialSignatureWithNonce(partialSig: ByteVector32, nonce: IndividualNonce)

/** We don't store the fully signed transaction, otherwise someone with read access to our database could force-close our channels. */
case class CommitTxAndRemoteSig(commitTx: CommitTx, remoteSig: Either[ByteVector64, PartialSignatureWithNonce])

/** The local commitment maps to a commitment transaction that we can sign and broadcast if necessary. */
case class LocalCommit(index: Long, spec: CommitmentSpec, commitTxAndRemoteSig: CommitTxAndRemoteSig, htlcTxsAndRemoteSigs: List[HtlcTxAndRemoteSig])

object LocalCommit {
  def fromCommitSig(keyManager: ChannelKeyManager, params: ChannelParams, fundingTxId: TxId,
                    fundingTxIndex: Long, remoteFundingPubKey: PublicKey, commitInput: InputInfo,
                    commit: CommitSig, localCommitIndex: Long, spec: CommitmentSpec, localPerCommitmentPoint: PublicKey, localNonce_opt: Option[(SecretNonce, IndividualNonce)]): Either[ChannelException, LocalCommit] = {
    val (localCommitTx, htlcTxs) = Commitment.makeLocalTxs(keyManager, params.channelConfig, params.channelFeatures, localCommitIndex, params.localParams, params.remoteParams, fundingTxIndex, remoteFundingPubKey, commitInput, localPerCommitmentPoint, spec)
    if (!localCommitTx.checkSig(commit, remoteFundingPubKey, TxOwner.Remote, params.commitmentFormat)) {
      return Left(InvalidCommitmentSignature(params.channelId, fundingTxId, fundingTxIndex, localCommitTx.tx))
    }
    commit.sigOrPartialSig match {
      case Left(_) =>
        if (!localCommitTx.checkSig(commit, remoteFundingPubKey, TxOwner.Remote, params.commitmentFormat)) {
          return Left(InvalidCommitmentSignature(params.channelId, fundingTxId, fundingTxIndex, localCommitTx.tx))
        }
      case Right(psig) =>
        val fundingPubkey = keyManager.fundingPublicKey(params.localParams.fundingKeyPath, fundingTxIndex).publicKey
        val Some(localNonce) = localNonce_opt
        if (!localCommitTx.checkPartialSignature(psig, fundingPubkey, localNonce._2, remoteFundingPubKey)) {
          return Left(InvalidCommitmentSignature(params.channelId, fundingTxId, fundingTxIndex, localCommitTx.tx))
        }
    }
    val sortedHtlcTxs = htlcTxs.sortBy(_.input.outPoint.index)
    if (commit.htlcSignatures.size != sortedHtlcTxs.size) {
      return Left(HtlcSigCountMismatch(params.channelId, sortedHtlcTxs.size, commit.htlcSignatures.size))
    }
    val remoteHtlcPubkey = Generators.derivePubKey(params.remoteParams.htlcBasepoint, localPerCommitmentPoint)
    val htlcTxsAndRemoteSigs = sortedHtlcTxs.zip(commit.htlcSignatures).toList.map {
      case (htlcTx: HtlcTx, remoteSig) =>
        if (!htlcTx.checkSig(remoteSig, remoteHtlcPubkey, TxOwner.Remote, params.commitmentFormat)) {
          return Left(InvalidHtlcSignature(params.channelId, htlcTx.tx.txid))
        }
        HtlcTxAndRemoteSig(htlcTx, remoteSig)
    }
    Right(LocalCommit(localCommitIndex, spec, CommitTxAndRemoteSig(localCommitTx, commit.sigOrPartialSig), htlcTxsAndRemoteSigs))
  }
}

/** The remote commitment maps to a commitment transaction that only our peer can sign and broadcast. */
case class RemoteCommit(index: Long, spec: CommitmentSpec, txid: TxId, remotePerCommitmentPoint: PublicKey) {
  def sign(keyManager: ChannelKeyManager, params: ChannelParams, fundingTxIndex: Long, remoteFundingPubKey: PublicKey, commitInput: InputInfo, remoteNonce_opt: Option[IndividualNonce])(implicit log: LoggingAdapter): CommitSig = {
    val (remoteCommitTx, htlcTxs) = Commitment.makeRemoteTxs(keyManager, params.channelConfig, params.channelFeatures, index, params.localParams, params.remoteParams, fundingTxIndex, remoteFundingPubKey, commitInput, remotePerCommitmentPoint, spec)
    val (sig, tlvStream) = if (params.commitmentFormat.useTaproot) {
      val localNonce = keyManager.verificationNonce(params.localParams.fundingKeyPath, fundingTxIndex, keyManager.keyPath(params.localParams, params.channelConfig), index)
      val Some(remoteNonce) = remoteNonce_opt
      val Right(localPartialSigOfRemoteTx) = keyManager.partialSign(remoteCommitTx, keyManager.fundingPublicKey(params.localParams.fundingKeyPath, fundingTxIndex), remoteFundingPubKey, TxOwner.Remote, localNonce, remoteNonce)
      val tlvStream: TlvStream[CommitSigTlv] = TlvStream(CommitSigTlv.PartialSignatureWithNonceTlv(PartialSignatureWithNonce(localPartialSigOfRemoteTx, localNonce._2)))
      (ByteVector64.Zeroes, tlvStream)
    } else {
      val sig = keyManager.sign(remoteCommitTx, keyManager.fundingPublicKey(params.localParams.fundingKeyPath, fundingTxIndex), TxOwner.Remote, params.commitmentFormat)
      (sig, TlvStream[CommitSigTlv]())
    }
    val channelKeyPath = keyManager.keyPath(params.localParams, params.channelConfig)
    val sortedHtlcTxs = htlcTxs.sortBy(_.input.outPoint.index)
    val htlcSigs = sortedHtlcTxs.map(keyManager.sign(_, keyManager.htlcPoint(channelKeyPath), remotePerCommitmentPoint, TxOwner.Remote, params.commitmentFormat))
    CommitSig(params.channelId, sig, htlcSigs.toList, tlvStream)
  }
}

/** We have the next remote commit when we've sent our commit_sig but haven't yet received their revoke_and_ack. */
case class NextRemoteCommit(sig: CommitSig, commit: RemoteCommit)

/**
 * A minimal commitment for a given funding tx.
 *
 * @param fundingTxIndex         index of the funding tx in the life of the channel:
 *                                - initial funding tx has index 0
 *                                - splice txs have index 1, 2, ...
 *                                - commitments that share the same index are rbfed
 * @param firstRemoteCommitIndex index of the first remote commitment we signed that spends the funding transaction.
 *                               Once the funding transaction confirms, our peer won't be able to publish revoked
 *                               commitments with lower commitment indices.
 */
case class Commitment(fundingTxIndex: Long,
                      firstRemoteCommitIndex: Long,
                      remoteFundingPubKey: PublicKey,
                      localFundingStatus: LocalFundingStatus, remoteFundingStatus: RemoteFundingStatus,
                      localCommit: LocalCommit, remoteCommit: RemoteCommit, nextRemoteCommit_opt: Option[NextRemoteCommit]) {
  val commitInput: InputInfo = localCommit.commitTxAndRemoteSig.commitTx.input
  val fundingTxId: TxId = commitInput.outPoint.txid
  val capacity: Satoshi = commitInput.txOut.amount

  /** Channel reserve that applies to our funds. */
  def localChannelReserve(params: ChannelParams): Satoshi = params.localChannelReserveForCapacity(capacity, fundingTxIndex > 0)

  /** Channel reserve that applies to our peer's funds. */
  def remoteChannelReserve(params: ChannelParams): Satoshi = params.remoteChannelReserveForCapacity(capacity, fundingTxIndex > 0)

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

  def availableBalanceForSend(params: ChannelParams, changes: CommitmentChanges): MilliSatoshi = {
    import params._
    // we need to base the next current commitment on the last sig we sent, even if we didn't yet receive their revocation
    val remoteCommit1 = nextRemoteCommit_opt.map(_.commit).getOrElse(remoteCommit)
    val reduced = CommitmentSpec.reduce(remoteCommit1.spec, changes.remoteChanges.acked, changes.localChanges.proposed)
    val balanceNoFees = (reduced.toRemote - localChannelReserve(params)).max(0 msat)
    if (localParams.paysCommitTxFees) {
      // The initiator always pays the on-chain fees, so we must subtract that from the amount we can send.
      val commitFees = commitTxTotalCostMsat(remoteParams.dustLimit, reduced, commitmentFormat)
      // the initiator needs to keep a "funder fee buffer" (see explanation above)
      val funderFeeBuffer = commitTxTotalCostMsat(remoteParams.dustLimit, reduced.copy(commitTxFeerate = reduced.commitTxFeerate * 2), commitmentFormat) + htlcOutputFee(reduced.commitTxFeerate * 2, commitmentFormat)
      val amountToReserve = commitFees.max(funderFeeBuffer)
      if (balanceNoFees - amountToReserve < offeredHtlcTrimThreshold(remoteParams.dustLimit, reduced, commitmentFormat)) {
        // htlc will be trimmed
        (balanceNoFees - amountToReserve).max(0 msat)
      } else {
        // htlc will have an output in the commitment tx, so there will be additional fees.
        val commitFees1 = commitFees + htlcOutputFee(reduced.commitTxFeerate, commitmentFormat)
        // we take the additional fees for that htlc output into account in the fee buffer at a x2 feerate increase
        val funderFeeBuffer1 = funderFeeBuffer + htlcOutputFee(reduced.commitTxFeerate * 2, commitmentFormat)
        val amountToReserve1 = commitFees1.max(funderFeeBuffer1)
        (balanceNoFees - amountToReserve1).max(0 msat)
      }
    } else {
      // The non-initiator doesn't pay on-chain fees.
      balanceNoFees
    }
  }

  def availableBalanceForReceive(params: ChannelParams, changes: CommitmentChanges): MilliSatoshi = {
    import params._
    val reduced = CommitmentSpec.reduce(localCommit.spec, changes.localChanges.acked, changes.remoteChanges.proposed)
    val balanceNoFees = (reduced.toRemote - remoteChannelReserve(params)).max(0 msat)
    if (localParams.paysCommitTxFees) {
      // The non-initiator doesn't pay on-chain fees so we don't take those into account when receiving.
      balanceNoFees
    } else {
      // The initiator always pays the on-chain fees, so we must subtract that from the amount we can receive.
      val commitFees = commitTxTotalCostMsat(localParams.dustLimit, reduced, commitmentFormat)
      // we expected the initiator to keep a "funder fee buffer" (see explanation above)
      val funderFeeBuffer = commitTxTotalCostMsat(localParams.dustLimit, reduced.copy(commitTxFeerate = reduced.commitTxFeerate * 2), commitmentFormat) + htlcOutputFee(reduced.commitTxFeerate * 2, commitmentFormat)
      val amountToReserve = commitFees.max(funderFeeBuffer)
      if (balanceNoFees - amountToReserve < receivedHtlcTrimThreshold(localParams.dustLimit, reduced, commitmentFormat)) {
        // htlc will be trimmed
        (balanceNoFees - amountToReserve).max(0 msat)
      } else {
        // htlc will have an output in the commitment tx, so there will be additional fees.
        val commitFees1 = commitFees + htlcOutputFee(reduced.commitTxFeerate, commitmentFormat)
        // we take the additional fees for that htlc output into account in the fee buffer at a x2 feerate increase
        val funderFeeBuffer1 = funderFeeBuffer + htlcOutputFee(reduced.commitTxFeerate * 2, commitmentFormat)
        val amountToReserve1 = commitFees1.max(funderFeeBuffer1)
        (balanceNoFees - amountToReserve1).max(0 msat)
      }
    }
  }

  def hasNoPendingHtlcs: Boolean = localCommit.spec.htlcs.isEmpty && remoteCommit.spec.htlcs.isEmpty && nextRemoteCommit_opt.isEmpty

  def hasNoPendingHtlcsOrFeeUpdate(changes: CommitmentChanges): Boolean = hasNoPendingHtlcs &&
    (changes.localChanges.signed ++ changes.localChanges.acked ++ changes.remoteChanges.signed ++ changes.remoteChanges.acked).collectFirst { case _: UpdateFee => true }.isEmpty

  def hasPendingOrProposedHtlcs(changes: CommitmentChanges): Boolean = !hasNoPendingHtlcs ||
    changes.localChanges.all.exists(_.isInstanceOf[UpdateAddHtlc]) ||
    changes.remoteChanges.all.exists(_.isInstanceOf[UpdateAddHtlc])

  def timedOutOutgoingHtlcs(currentHeight: BlockHeight): Set[UpdateAddHtlc] = {
    def expired(add: UpdateAddHtlc): Boolean = currentHeight >= add.cltvExpiry.blockHeight

    localCommit.spec.htlcs.collect(DirectedHtlc.outgoing).filter(expired) ++
      remoteCommit.spec.htlcs.collect(DirectedHtlc.incoming).filter(expired) ++
      nextRemoteCommit_opt.toSeq.flatMap(_.commit.spec.htlcs.collect(DirectedHtlc.incoming).filter(expired).toSet)
  }

  /**
   * Return the outgoing HTLC with the given id if it is:
   *  - signed by us in their commitment transaction (remote)
   *  - signed by them in our commitment transaction (local)
   *
   * NB: if we're in the middle of fulfilling or failing that HTLC, it will not be returned by this function.
   */
  def getOutgoingHtlcCrossSigned(htlcId: Long): Option[UpdateAddHtlc] = for {
    localSigned <- nextRemoteCommit_opt.map(_.commit).getOrElse(remoteCommit).spec.findIncomingHtlcById(htlcId)
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
    localSigned <- nextRemoteCommit_opt.map(_.commit).getOrElse(remoteCommit).spec.findOutgoingHtlcById(htlcId)
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

  def canSendAdd(amount: MilliSatoshi, params: ChannelParams, changes: CommitmentChanges, feerates: FeeratesPerKw, feeConf: OnChainFeeConf): Either[ChannelException, Unit] = {
    // we allowed mismatches between our feerates and our remote's as long as commitments didn't contain any HTLC at risk
    // we need to verify that we're not disagreeing on feerates anymore before offering new HTLCs
    // NB: there may be a pending update_fee that hasn't been applied yet that needs to be taken into account
    val localFeerate = feeConf.getCommitmentFeerate(feerates, params.remoteNodeId, params.commitmentFormat, capacity)
    val remoteFeerate = localCommit.spec.commitTxFeerate +: changes.remoteChanges.all.collect { case f: UpdateFee => f.feeratePerKw }
    // What we want to avoid is having an HTLC in a commitment transaction that has a very low feerate, which we won't
    // be able to confirm in time to claim the HTLC, so we only need to check that the feerate isn't too low.
    remoteFeerate.find(feerate => feeConf.feerateToleranceFor(params.remoteNodeId).isProposedFeerateTooLow(params.commitmentFormat, localFeerate, feerate)) match {
      case Some(feerate) => return Left(FeerateTooDifferent(params.channelId, localFeeratePerKw = localFeerate, remoteFeeratePerKw = feerate))
      case None =>
    }

    // let's compute the current commitments *as seen by them* with the additional htlc
    // we need to base the next current commitment on the last sig we sent, even if we didn't yet receive their revocation
    val remoteCommit1 = nextRemoteCommit_opt.map(_.commit).getOrElse(remoteCommit)
    val reduced = CommitmentSpec.reduce(remoteCommit1.spec, changes.remoteChanges.acked, changes.localChanges.proposed)
    // the HTLC we are about to create is outgoing, but from their point of view it is incoming
    val outgoingHtlcs = reduced.htlcs.collect(DirectedHtlc.incoming)

    // note that the initiator pays the fee, so if sender != initiator, both sides will have to afford this payment
    val fees = commitTxTotalCost(params.remoteParams.dustLimit, reduced, params.commitmentFormat)
    // the initiator needs to keep an extra buffer to be able to handle a x2 feerate increase and an additional htlc to avoid
    // getting the channel stuck (see https://github.com/lightningnetwork/lightning-rfc/issues/728).
    val funderFeeBuffer = commitTxTotalCostMsat(params.remoteParams.dustLimit, reduced.copy(commitTxFeerate = reduced.commitTxFeerate * 2), params.commitmentFormat) + htlcOutputFee(reduced.commitTxFeerate * 2, params.commitmentFormat)
    // NB: increasing the feerate can actually remove htlcs from the commit tx (if they fall below the trim threshold)
    // which may result in a lower commit tx fee; this is why we take the max of the two.
    val missingForSender = reduced.toRemote - localChannelReserve(params) - (if (params.localParams.paysCommitTxFees) fees.max(funderFeeBuffer.truncateToSatoshi) else 0.sat)
    val missingForReceiver = reduced.toLocal - remoteChannelReserve(params) - (if (params.localParams.paysCommitTxFees) 0.sat else fees)
    if (missingForSender < 0.msat) {
      return Left(InsufficientFunds(params.channelId, amount = amount, missing = -missingForSender.truncateToSatoshi, reserve = localChannelReserve(params), fees = if (params.localParams.paysCommitTxFees) fees else 0.sat))
    } else if (missingForReceiver < 0.msat) {
      if (params.localParams.paysCommitTxFees) {
        // receiver is not the channel initiator; it is ok if it can't maintain its channel_reserve for now, as long as its balance is increasing, which is the case if it is receiving a payment
      } else if (reduced.toLocal > fees && reduced.htlcs.size < 5 && fundingTxIndex > 0) {
        // Receiver is the channel initiator; we usually don't want to let them dip into their channel reserve, because
        // that may give them a commitment transaction where they have nothing at stake, which would create an incentive
        // for them to force-close using that commitment after it has been revoked.
        // But we let them dip slightly into their channel reserve to pay the fees, to ensure that the channel is not
        // stuck and unusable, because we can end up in that state in the following scenario:
        //  - they were above their channel reserve
        //  - we spliced a lot of funds into the channel, which increased the reserve requirements
        //  - they are now below the new reserve, but if we don't allow htlcs to them, they have no way of increasing their balance
        // Since we only allow a limited number of htlcs, that doesn't let them dip into their reserve much.
        // We could also keep track of the previous channel reserve, but this is additional state that is awkward to
        // store and not trivial to correctly keep up-to-date. This simpler solution has a similar result with less
        // complexity.
      } else {
        return Left(RemoteCannotAffordFeesForNewHtlc(params.channelId, amount = amount, missing = -missingForReceiver.truncateToSatoshi, reserve = remoteChannelReserve(params), fees = fees))
      }
    }

    // We apply local *and* remote restrictions, to ensure both peers are happy with the resulting number of HTLCs.
    // NB: we need the `toSeq` because otherwise duplicate amountMsat would be removed (since outgoingHtlcs is a Set).
    val htlcValueInFlight = outgoingHtlcs.toSeq.map(_.amountMsat).sum
    val allowedHtlcValueInFlight = params.maxHtlcAmount
    if (allowedHtlcValueInFlight < htlcValueInFlight) {
      return Left(HtlcValueTooHighInFlight(params.channelId, maximum = allowedHtlcValueInFlight, actual = htlcValueInFlight))
    }
    if (Seq(params.localParams.maxAcceptedHtlcs, params.remoteParams.maxAcceptedHtlcs).min < outgoingHtlcs.size) {
      return Left(TooManyAcceptedHtlcs(params.channelId, maximum = Seq(params.localParams.maxAcceptedHtlcs, params.remoteParams.maxAcceptedHtlcs).min))
    }

    // If sending this htlc would overflow our dust exposure, we reject it.
    val maxDustExposure = feeConf.feerateToleranceFor(params.remoteNodeId).dustTolerance.maxExposure
    val localReduced = DustExposure.reduceForDustExposure(localCommit.spec, changes.localChanges.all, changes.remoteChanges.all)
    val localDustExposureAfterAdd = DustExposure.computeExposure(localReduced, params.localParams.dustLimit, params.commitmentFormat)
    if (localDustExposureAfterAdd > maxDustExposure) {
      return Left(LocalDustHtlcExposureTooHigh(params.channelId, maxDustExposure, localDustExposureAfterAdd))
    }
    val remoteReduced = DustExposure.reduceForDustExposure(remoteCommit1.spec, changes.remoteChanges.all, changes.localChanges.all)
    val remoteDustExposureAfterAdd = DustExposure.computeExposure(remoteReduced, params.remoteParams.dustLimit, params.commitmentFormat)
    if (remoteDustExposureAfterAdd > maxDustExposure) {
      return Left(RemoteDustHtlcExposureTooHigh(params.channelId, maxDustExposure, remoteDustExposureAfterAdd))
    }

    Right(())
  }

  def canReceiveAdd(amount: MilliSatoshi, params: ChannelParams, changes: CommitmentChanges, feerates: FeeratesPerKw, feeConf: OnChainFeeConf): Either[ChannelException, Unit] = {
    // we allowed mismatches between our feerates and our remote's as long as commitments didn't contain any HTLC at risk
    // we need to verify that we're not disagreeing on feerates anymore before accepting new HTLCs
    // NB: there may be a pending update_fee that hasn't been applied yet that needs to be taken into account
    val localFeerate = feeConf.getCommitmentFeerate(feerates, params.remoteNodeId, params.commitmentFormat, capacity)
    val remoteFeerate = localCommit.spec.commitTxFeerate +: changes.remoteChanges.all.collect { case f: UpdateFee => f.feeratePerKw }
    remoteFeerate.find(feerate => feeConf.feerateToleranceFor(params.remoteNodeId).isProposedFeerateTooLow(params.commitmentFormat, localFeerate, feerate)) match {
      case Some(feerate) => return Left(FeerateTooDifferent(params.channelId, localFeeratePerKw = localFeerate, remoteFeeratePerKw = feerate))
      case None =>
    }

    // let's compute the current commitment *as seen by us* including this additional htlc
    val reduced = CommitmentSpec.reduce(localCommit.spec, changes.localChanges.acked, changes.remoteChanges.proposed)
    val incomingHtlcs = reduced.htlcs.collect(DirectedHtlc.incoming)

    // note that the initiator pays the fee, so if sender != initiator, both sides will have to afford this payment
    val fees = commitTxTotalCost(params.localParams.dustLimit, reduced, params.commitmentFormat)
    // NB: we don't enforce the funderFeeReserve (see sendAdd) because it would confuse a remote initiator that doesn't have this mitigation in place
    // We could enforce it once we're confident a large portion of the network implements it.
    val missingForSender = reduced.toRemote - remoteChannelReserve(params) - (if (params.localParams.paysCommitTxFees) 0.sat else fees)
    // Note that Bolt 2 requires to also meet our channel reserve requirement, but we're more lenient than that because
    // as long as we're able to pay the commit tx fee, it's ok if we dip into our channel reserve: we're receiving an
    // HTLC, which means our balance will increase and meet the channel reserve again.
    val missingForReceiver = reduced.toLocal - (if (params.localParams.paysCommitTxFees) fees else 0.sat)
    if (missingForSender < 0.sat) {
      return Left(InsufficientFunds(params.channelId, amount = amount, missing = -missingForSender.truncateToSatoshi, reserve = remoteChannelReserve(params), fees = if (params.localParams.paysCommitTxFees) 0.sat else fees))
    } else if (missingForReceiver < 0.sat) {
      if (params.localParams.paysCommitTxFees) {
        return Left(CannotAffordFees(params.channelId, missing = -missingForReceiver.truncateToSatoshi, reserve = localChannelReserve(params), fees = fees))
      } else {
        // receiver is not the channel initiator; it is ok if it can't maintain its channel_reserve for now, as long as its balance is increasing, which is the case if it is receiving a payment
      }
    }

    // NB: we need the `toSeq` because otherwise duplicate amountMsat would be removed (since incomingHtlcs is a Set).
    val htlcValueInFlight = incomingHtlcs.toSeq.map(_.amountMsat).sum
    if (params.localParams.maxHtlcValueInFlightMsat < htlcValueInFlight) {
      return Left(HtlcValueTooHighInFlight(params.channelId, maximum = params.localParams.maxHtlcValueInFlightMsat, actual = htlcValueInFlight))
    }

    if (incomingHtlcs.size > params.localParams.maxAcceptedHtlcs) {
      return Left(TooManyAcceptedHtlcs(params.channelId, maximum = params.localParams.maxAcceptedHtlcs))
    }

    Right(())
  }

  def canSendFee(targetFeerate: FeeratePerKw, params: ChannelParams, changes: CommitmentChanges, feeConf: OnChainFeeConf): Either[ChannelException, Unit] = {
    // let's compute the current commitment *as seen by them* with this change taken into account
    val reduced = CommitmentSpec.reduce(remoteCommit.spec, changes.remoteChanges.acked, changes.localChanges.proposed)
    // a node cannot spend pending incoming htlcs, and need to keep funds above the reserve required by the counterparty, after paying the fee
    // we look from remote's point of view, so if local is initiator remote doesn't pay the fees
    val fees = commitTxTotalCost(params.remoteParams.dustLimit, reduced, params.commitmentFormat)
    val missing = reduced.toRemote.truncateToSatoshi - localChannelReserve(params) - fees
    if (missing < 0.sat) {
      return Left(CannotAffordFees(params.channelId, missing = -missing, reserve = localChannelReserve(params), fees = fees))
    }
    // if we would overflow our dust exposure with the new feerate, we avoid sending this fee update
    if (feeConf.feerateToleranceFor(params.remoteNodeId).dustTolerance.closeOnUpdateFeeOverflow) {
      val maxDustExposure = feeConf.feerateToleranceFor(params.remoteNodeId).dustTolerance.maxExposure
      // this is the commitment as it would be if our update_fee was immediately signed by both parties (it is only an
      // estimate because there can be concurrent updates)
      val localReduced = DustExposure.reduceForDustExposure(localCommit.spec, changes.localChanges.all, changes.remoteChanges.all)
      val localDustExposureAfterFeeUpdate = DustExposure.computeExposure(localReduced, targetFeerate, params.localParams.dustLimit, params.commitmentFormat)
      if (localDustExposureAfterFeeUpdate > maxDustExposure) {
        return Left(LocalDustHtlcExposureTooHigh(params.channelId, maxDustExposure, localDustExposureAfterFeeUpdate))
      }
      val remoteReduced = DustExposure.reduceForDustExposure(remoteCommit.spec, changes.remoteChanges.all, changes.localChanges.all)
      val remoteDustExposureAfterFeeUpdate = DustExposure.computeExposure(remoteReduced, targetFeerate, params.remoteParams.dustLimit, params.commitmentFormat)
      if (remoteDustExposureAfterFeeUpdate > maxDustExposure) {
        return Left(RemoteDustHtlcExposureTooHigh(params.channelId, maxDustExposure, remoteDustExposureAfterFeeUpdate))
      }
    }
    Right(())
  }

  def canReceiveFee(targetFeerate: FeeratePerKw, params: ChannelParams, changes: CommitmentChanges, feerates: FeeratesPerKw, feeConf: OnChainFeeConf): Either[ChannelException, Unit] = {
    val localFeerate = feeConf.getCommitmentFeerate(feerates, params.remoteNodeId, params.commitmentFormat, capacity)
    if (feeConf.feerateToleranceFor(params.remoteNodeId).isProposedFeerateTooHigh(params.commitmentFormat, localFeerate, targetFeerate)) {
      return Left(FeerateTooDifferent(params.channelId, localFeeratePerKw = localFeerate, remoteFeeratePerKw = targetFeerate))
    } else if (feeConf.feerateToleranceFor(params.remoteNodeId).isProposedFeerateTooLow(params.commitmentFormat, localFeerate, targetFeerate) && hasPendingOrProposedHtlcs(changes)) {
      // If the proposed feerate is too low, but we don't have any pending HTLC, we temporarily accept it.
      return Left(FeerateTooDifferent(params.channelId, localFeeratePerKw = localFeerate, remoteFeeratePerKw = targetFeerate))
    } else {
      // let's compute the current commitment *as seen by us* including this change
      // NB: we check that the initiator can afford this new fee even if spec allows to do it at next signature
      // It is easier to do it here because under certain (race) conditions spec allows a lower-than-normal fee to be paid,
      // and it would be tricky to check if the conditions are met at signing
      // (it also means that we need to check the fee of the initial commitment tx somewhere)
      val reduced = CommitmentSpec.reduce(localCommit.spec, changes.localChanges.acked, changes.remoteChanges.proposed)
      // a node cannot spend pending incoming htlcs, and need to keep funds above the reserve required by the counterparty, after paying the fee
      val fees = commitTxTotalCost(params.localParams.dustLimit, reduced, params.commitmentFormat)
      val missing = reduced.toRemote.truncateToSatoshi - remoteChannelReserve(params) - fees
      if (missing < 0.sat) {
        return Left(CannotAffordFees(params.channelId, missing = -missing, reserve = remoteChannelReserve(params), fees = fees))
      }
      // if we would overflow our dust exposure with the new feerate, we reject this fee update
      if (feeConf.feerateToleranceFor(params.remoteNodeId).dustTolerance.closeOnUpdateFeeOverflow) {
        val maxDustExposure = feeConf.feerateToleranceFor(params.remoteNodeId).dustTolerance.maxExposure
        val localReduced = DustExposure.reduceForDustExposure(localCommit.spec, changes.localChanges.all, changes.remoteChanges.all)
        val localDustExposureAfterFeeUpdate = DustExposure.computeExposure(localReduced, targetFeerate, params.localParams.dustLimit, params.commitmentFormat)
        if (localDustExposureAfterFeeUpdate > maxDustExposure) {
          return Left(LocalDustHtlcExposureTooHigh(params.channelId, maxDustExposure, localDustExposureAfterFeeUpdate))
        }
        // this is the commitment as it would be if their update_fee was immediately signed by both parties (it is only an
        // estimate because there can be concurrent updates)
        val remoteReduced = DustExposure.reduceForDustExposure(remoteCommit.spec, changes.remoteChanges.all, changes.localChanges.all)
        val remoteDustExposureAfterFeeUpdate = DustExposure.computeExposure(remoteReduced, targetFeerate, params.remoteParams.dustLimit, params.commitmentFormat)
        if (remoteDustExposureAfterFeeUpdate > maxDustExposure) {
          return Left(RemoteDustHtlcExposureTooHigh(params.channelId, maxDustExposure, remoteDustExposureAfterFeeUpdate))
        }
      }
    }
    Right(())
  }

  def sendCommit(keyManager: ChannelKeyManager, params: ChannelParams, changes: CommitmentChanges, remoteNextPerCommitmentPoint: PublicKey, batchSize: Int, nextRemoteNonce_opt: Option[IndividualNonce])(implicit log: LoggingAdapter): (Commitment, CommitSig) = {
    // remote commitment will include all local proposed changes + remote acked changes
    val spec = CommitmentSpec.reduce(remoteCommit.spec, changes.remoteChanges.acked, changes.localChanges.proposed)
    val (remoteCommitTx, htlcTxs) = Commitment.makeRemoteTxs(keyManager, params.channelConfig, params.channelFeatures, remoteCommit.index + 1, params.localParams, params.remoteParams, fundingTxIndex, remoteFundingPubKey, commitInput, remoteNextPerCommitmentPoint, spec)
    val sig = if (params.commitmentFormat.useTaproot) {
      ByteVector64.Zeroes
    } else {
      keyManager.sign(remoteCommitTx, keyManager.fundingPublicKey(params.localParams.fundingKeyPath, fundingTxIndex), TxOwner.Remote, params.commitmentFormat)
    }
    val partialSig: Set[CommitSigTlv] = if (params.commitmentFormat.useTaproot) {
      val localNonce = keyManager.signingNonce(params.localParams.fundingKeyPath, fundingTxIndex)
      val Some(remoteNonce) = nextRemoteNonce_opt
      val Right(psig) = keyManager.partialSign(remoteCommitTx, keyManager.fundingPublicKey(params.localParams.fundingKeyPath, fundingTxIndex), remoteFundingPubKey, TxOwner.Remote, localNonce, remoteNonce)
      log.debug(s"sendCommit: creating partial sig $psig for remote commit tx ${remoteCommitTx.tx.txid} with remote nonce $remoteNonce and remoteNextPerCommitmentPoint = $remoteNextPerCommitmentPoint")
      Set(CommitSigTlv.PartialSignatureWithNonceTlv(PartialSignatureWithNonce(psig, localNonce._2)))
    } else {
      Set.empty
    }
    val sortedHtlcTxs: Seq[TransactionWithInputInfo] = htlcTxs.sortBy(_.input.outPoint.index)
    val channelKeyPath = keyManager.keyPath(params.localParams, params.channelConfig)
    val htlcSigs = sortedHtlcTxs.map(keyManager.sign(_, keyManager.htlcPoint(channelKeyPath), remoteNextPerCommitmentPoint, TxOwner.Remote, params.commitmentFormat))

    // NB: IN/OUT htlcs are inverted because this is the remote commit
    log.info(s"built remote commit number=${remoteCommit.index + 1} toLocalMsat=${spec.toLocal.toLong} toRemoteMsat=${spec.toRemote.toLong} htlc_in={} htlc_out={} feeratePerKw=${spec.commitTxFeerate} txid=${remoteCommitTx.tx.txid} fundingTxId=$fundingTxId", spec.htlcs.collect(DirectedHtlc.outgoing).map(_.id).mkString(","), spec.htlcs.collect(DirectedHtlc.incoming).map(_.id).mkString(","))
    Metrics.recordHtlcsInFlight(spec, remoteCommit.spec)

    val commitSig = CommitSig(params.channelId, sig, htlcSigs.toList, TlvStream(Set(
      if (batchSize > 1) Some(CommitSigTlv.BatchTlv(batchSize)) else None
    ).flatten[CommitSigTlv] ++ partialSig))
    log.debug(s"sendCommit: setting remoteNextPerCommitmentPoint to $remoteNextPerCommitmentPoint")
    val nextRemoteCommit = NextRemoteCommit(commitSig, RemoteCommit(remoteCommit.index + 1, spec, remoteCommitTx.tx.txid, remoteNextPerCommitmentPoint))
    (copy(nextRemoteCommit_opt = Some(nextRemoteCommit)), commitSig)
  }

  def receiveCommit(keyManager: ChannelKeyManager, params: ChannelParams, changes: CommitmentChanges, localPerCommitmentPoint: PublicKey, commit: CommitSig, localNonce_opt: Option[(SecretNonce, IndividualNonce)])(implicit log: LoggingAdapter): Either[ChannelException, Commitment] = {
    // they sent us a signature for *their* view of *our* next commit tx
    // so in terms of rev.hashes and indexes we have:
    // ourCommit.index -> our current revocation hash, which is about to become our old revocation hash
    // ourCommit.index + 1 -> our next revocation hash, used by *them* to build the sig we've just received, and which
    // is about to become our current revocation hash
    // ourCommit.index + 2 -> which is about to become our next revocation hash
    // we will reply to this sig with our old revocation hash preimage (at index) and our next revocation hash (at index + 1)
    // and will increment our index
    val localCommitIndex = localCommit.index + 1
    val spec = CommitmentSpec.reduce(localCommit.spec, changes.localChanges.acked, changes.remoteChanges.proposed)
    LocalCommit.fromCommitSig(keyManager, params, fundingTxId, fundingTxIndex, remoteFundingPubKey, commitInput, commit, localCommitIndex, spec, localPerCommitmentPoint, localNonce_opt).map { localCommit1 =>
      log.info(s"built local commit number=$localCommitIndex toLocalMsat=${spec.toLocal.toLong} toRemoteMsat=${spec.toRemote.toLong} htlc_in={} htlc_out={} feeratePerKw=${spec.commitTxFeerate} txid=${localCommit1.commitTxAndRemoteSig.commitTx.tx.txid} fundingTxId=$fundingTxId", spec.htlcs.collect(DirectedHtlc.incoming).map(_.id).mkString(","), spec.htlcs.collect(DirectedHtlc.outgoing).map(_.id).mkString(","))
      copy(localCommit = localCommit1)
    }
  }

  /** Return a fully signed commit tx, that can be published as-is. */
  def fullySignedLocalCommitTx(params: ChannelParams, keyManager: ChannelKeyManager): CommitTx = {
    val unsignedCommitTx = localCommit.commitTxAndRemoteSig.commitTx
    val commitTx = localCommit.commitTxAndRemoteSig.remoteSig match {
      case Left(remoteSig) =>
        val localSig = keyManager.sign(unsignedCommitTx, keyManager.fundingPublicKey(params.localParams.fundingKeyPath, fundingTxIndex), TxOwner.Local, params.commitmentFormat)
        addSigs(unsignedCommitTx, keyManager.fundingPublicKey(params.localParams.fundingKeyPath, fundingTxIndex).publicKey, remoteFundingPubKey, localSig, remoteSig)
      case Right(remotePartialSigWithNonce) =>
        val fundingPubKey = keyManager.fundingPublicKey(params.localParams.fundingKeyPath, fundingTxIndex)
        val channelKeyPath = ChannelKeyManager.keyPath(keyManager.fundingPublicKey(params.localParams.fundingKeyPath, fundingTxIndex = 0L))
        val localNonce = keyManager.verificationNonce(params.localParams.fundingKeyPath, fundingTxIndex, channelKeyPath, localCommit.index)
        val Right(partialSig) = keyManager.partialSign(unsignedCommitTx,
          keyManager.fundingPublicKey(params.localParams.fundingKeyPath, fundingTxIndex), remoteFundingPubKey,
          TxOwner.Local,
          localNonce, remotePartialSigWithNonce.nonce)
        val Right(aggSig) = Musig2.aggregateTaprootSignatures(
          Seq(partialSig, remotePartialSigWithNonce.partialSig), unsignedCommitTx.tx, unsignedCommitTx.tx.txIn.indexWhere(_.outPoint == unsignedCommitTx.input.outPoint),
          Seq(unsignedCommitTx.input.txOut),
          Scripts.sort(Seq(fundingPubKey.publicKey, remoteFundingPubKey)),
          Seq(localNonce._2, remotePartialSigWithNonce.nonce),
          None)
        unsignedCommitTx.copy(tx = unsignedCommitTx.tx.updateWitness(0, Script.witnessKeyPathPay2tr(aggSig)))
    }
    // We verify the remote signature when receiving their commit_sig, so this check should always pass.
    require(checkSpendable(commitTx).isSuccess, "commit signatures are invalid")
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
                   fundingTxIndex: Long,
                   remoteFundingPubKey: PublicKey,
                   commitmentInput: InputInfo,
                   localPerCommitmentPoint: PublicKey,
                   spec: CommitmentSpec): (CommitTx, Seq[HtlcTx]) = {
    val channelKeyPath = keyManager.keyPath(localParams, channelConfig)
    val localFundingPubkey = keyManager.fundingPublicKey(localParams.fundingKeyPath, fundingTxIndex).publicKey
    val localDelayedPaymentPubkey = Generators.derivePubKey(keyManager.delayedPaymentPoint(channelKeyPath).publicKey, localPerCommitmentPoint)
    val localHtlcPubkey = Generators.derivePubKey(keyManager.htlcPoint(channelKeyPath).publicKey, localPerCommitmentPoint)
    val remotePaymentPubkey = if (channelFeatures.hasFeature(Features.StaticRemoteKey) || channelFeatures.hasFeature(Features.SimpleTaprootStaging)) {
      remoteParams.paymentBasepoint
    } else {
      Generators.derivePubKey(remoteParams.paymentBasepoint, localPerCommitmentPoint)
    }
    val remoteHtlcPubkey = Generators.derivePubKey(remoteParams.htlcBasepoint, localPerCommitmentPoint)
    val localRevocationPubkey = Generators.revocationPubKey(remoteParams.revocationBasepoint, localPerCommitmentPoint)
    val localPaymentBasepoint = localParams.walletStaticPaymentBasepoint.getOrElse(keyManager.paymentPoint(channelKeyPath).publicKey)
    val outputs = makeCommitTxOutputs(localParams.paysCommitTxFees, localParams.dustLimit, localRevocationPubkey, remoteParams.toSelfDelay, localDelayedPaymentPubkey, remotePaymentPubkey, localHtlcPubkey, remoteHtlcPubkey, localFundingPubkey, remoteFundingPubKey, spec, channelFeatures.commitmentFormat)
    val commitTx = makeCommitTx(commitmentInput, commitTxNumber, localPaymentBasepoint, remoteParams.paymentBasepoint, localParams.isChannelOpener, outputs)
    val htlcTxs = makeHtlcTxs(commitTx.tx, localParams.dustLimit, localRevocationPubkey, remoteParams.toSelfDelay, localDelayedPaymentPubkey, spec.htlcTxFeerate(channelFeatures.commitmentFormat), outputs, channelFeatures.commitmentFormat)
    (commitTx, htlcTxs)
  }

  def makeRemoteTxs(keyManager: ChannelKeyManager,
                    channelConfig: ChannelConfig,
                    channelFeatures: ChannelFeatures,
                    commitTxNumber: Long,
                    localParams: LocalParams,
                    remoteParams: RemoteParams,
                    fundingTxIndex: Long,
                    remoteFundingPubKey: PublicKey,
                    commitmentInput: InputInfo,
                    remotePerCommitmentPoint: PublicKey,
                    spec: CommitmentSpec): (CommitTx, Seq[HtlcTx]) = {
    val channelKeyPath = keyManager.keyPath(localParams, channelConfig)
    val localFundingPubkey = keyManager.fundingPublicKey(localParams.fundingKeyPath, fundingTxIndex).publicKey
    val localPaymentBasepoint = localParams.walletStaticPaymentBasepoint.getOrElse(keyManager.paymentPoint(channelKeyPath).publicKey)
    val localPaymentPubkey = if (channelFeatures.hasFeature(Features.StaticRemoteKey) || channelFeatures.hasFeature(Features.SimpleTaprootStaging)) {
      localPaymentBasepoint
    } else {
      Generators.derivePubKey(localPaymentBasepoint, remotePerCommitmentPoint)
    }
    val localHtlcPubkey = Generators.derivePubKey(keyManager.htlcPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)
    val remoteDelayedPaymentPubkey = Generators.derivePubKey(remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
    val remoteHtlcPubkey = Generators.derivePubKey(remoteParams.htlcBasepoint, remotePerCommitmentPoint)
    val remoteRevocationPubkey = Generators.revocationPubKey(keyManager.revocationPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)
    val outputs = makeCommitTxOutputs(!localParams.paysCommitTxFees, remoteParams.dustLimit, remoteRevocationPubkey, localParams.toSelfDelay, remoteDelayedPaymentPubkey, localPaymentPubkey, remoteHtlcPubkey, localHtlcPubkey, remoteFundingPubKey, localFundingPubkey, spec, channelFeatures.commitmentFormat)
    val commitTx = makeCommitTx(commitmentInput, commitTxNumber, remoteParams.paymentBasepoint, localPaymentBasepoint, !localParams.isChannelOpener, outputs)
    val htlcTxs = makeHtlcTxs(commitTx.tx, remoteParams.dustLimit, remoteRevocationPubkey, localParams.toSelfDelay, remoteDelayedPaymentPubkey, spec.htlcTxFeerate(channelFeatures.commitmentFormat), outputs, channelFeatures.commitmentFormat)
    (commitTx, htlcTxs)
  }
}

/** Subset of Commitments when we want to work with a single, specific commitment. */
case class FullCommitment(params: ChannelParams, changes: CommitmentChanges,
                          fundingTxIndex: Long,
                          firstRemoteCommitIndex: Long,
                          remoteFundingPubKey: PublicKey,
                          localFundingStatus: LocalFundingStatus, remoteFundingStatus: RemoteFundingStatus,
                          localCommit: LocalCommit, remoteCommit: RemoteCommit, nextRemoteCommit_opt: Option[NextRemoteCommit]) {
  val channelId = params.channelId
  val localParams = params.localParams
  val remoteParams = params.remoteParams
  val commitInput = localCommit.commitTxAndRemoteSig.commitTx.input
  val fundingTxId = commitInput.outPoint.txid
  val capacity = commitInput.txOut.amount
  val commitment = Commitment(fundingTxIndex, firstRemoteCommitIndex, remoteFundingPubKey, localFundingStatus, remoteFundingStatus, localCommit, remoteCommit, nextRemoteCommit_opt)

  def localChannelReserve: Satoshi = commitment.localChannelReserve(params)

  def remoteChannelReserve: Satoshi = commitment.remoteChannelReserve(params)

  def fullySignedLocalCommitTx(keyManager: ChannelKeyManager): CommitTx = commitment.fullySignedLocalCommitTx(params, keyManager)

  def specs2String: String = {
    s"""specs:
       |localcommit:
       |  toLocal: ${localCommit.spec.toLocal}
       |  toRemote: ${localCommit.spec.toRemote}
       |  htlcs:
       |${localCommit.spec.htlcs.map(h => s"    ${h.direction} ${h.add.id} ${h.add.cltvExpiry}").mkString("\n")}
       |remotecommit:
       |  toLocal: ${remoteCommit.spec.toLocal}
       |  toRemote: ${remoteCommit.spec.toRemote}
       |  htlcs:
       |${remoteCommit.spec.htlcs.map(h => s"    ${h.direction} ${h.add.id} ${h.add.cltvExpiry}").mkString("\n")}
       |next remotecommit:
       |  toLocal: ${nextRemoteCommit_opt.map(_.commit.spec.toLocal).getOrElse("N/A")}
       |  toRemote: ${nextRemoteCommit_opt.map(_.commit.spec.toRemote).getOrElse("N/A")}
       |  htlcs:
       |${nextRemoteCommit_opt.map(_.commit.spec.htlcs.map(h => s"    ${h.direction} ${h.add.id} ${h.add.cltvExpiry}").mkString("\n")).getOrElse("N/A")}""".stripMargin
  }
}

case class WaitForRev(sentAfterLocalCommitIndex: Long)

/**
 * @param active                all currently valid commitments
 * @param inactive              commitments that can potentially end up on-chain, but shouldn't be taken into account
 *                              when updating the channel state; they are zero-conf and have been superseded by a newer
 *                              commitment, which funding tx is not yet confirmed, and will be pruned when it confirms
 * @param remoteChannelData_opt peer backup
 */
case class Commitments(params: ChannelParams,
                       changes: CommitmentChanges,
                       active: Seq[Commitment],
                       inactive: Seq[Commitment] = Nil,
                       remoteNextCommitInfo: Either[WaitForRev, PublicKey], // this one is tricky, it must be kept in sync with Commitment.nextRemoteCommit_opt
                       remotePerCommitmentSecrets: ShaChain,
                       originChannels: Map[Long, Origin], // for outgoing htlcs relayed through us, details about the corresponding incoming htlcs
                       remoteChannelData_opt: Option[ByteVector] = None) {

  import Commitments._

  require(active.nonEmpty, "there must be at least one active commitment")

  val channelId: ByteVector32 = params.channelId
  val localNodeId: PublicKey = params.localNodeId
  val remoteNodeId: PublicKey = params.remoteNodeId
  val announceChannel: Boolean = params.announceChannel

  // Commitment numbers are the same for all active commitments.
  val localCommitIndex = active.head.localCommit.index
  val remoteCommitIndex = active.head.remoteCommit.index
  val nextRemoteCommitIndex = remoteCommitIndex + 1

  lazy val availableBalanceForSend: MilliSatoshi = active.map(_.availableBalanceForSend(params, changes)).min
  lazy val availableBalanceForReceive: MilliSatoshi = active.map(_.availableBalanceForReceive(params, changes)).min

  // We always use the last commitment that was created, to make sure we never go back in time.
  val latest = FullCommitment(params, changes, active.head.fundingTxIndex, active.head.firstRemoteCommitIndex, active.head.remoteFundingPubKey, active.head.localFundingStatus, active.head.remoteFundingStatus, active.head.localCommit, active.head.remoteCommit, active.head.nextRemoteCommit_opt)

  val all: Seq[Commitment] = active ++ inactive

  def add(commitment: Commitment): Commitments = copy(active = commitment +: active)

  // @formatter:off
  def localIsQuiescent: Boolean = changes.localChanges.all.isEmpty
  def remoteIsQuiescent: Boolean = changes.remoteChanges.all.isEmpty
  // HTLCs and pending changes are the same for all active commitments, so we don't need to loop through all of them.
  def isQuiescent: Boolean = localIsQuiescent && remoteIsQuiescent
  def hasNoPendingHtlcsOrFeeUpdate: Boolean = active.head.hasNoPendingHtlcsOrFeeUpdate(changes)
  def hasPendingOrProposedHtlcs: Boolean = active.head.hasPendingOrProposedHtlcs(changes)
  def timedOutOutgoingHtlcs(currentHeight: BlockHeight): Set[UpdateAddHtlc] = active.head.timedOutOutgoingHtlcs(currentHeight)
  def almostTimedOutIncomingHtlcs(currentHeight: BlockHeight, fulfillSafety: CltvExpiryDelta): Set[UpdateAddHtlc] = active.head.almostTimedOutIncomingHtlcs(currentHeight, fulfillSafety)
  def getOutgoingHtlcCrossSigned(htlcId: Long): Option[UpdateAddHtlc] = active.head.getOutgoingHtlcCrossSigned(htlcId)
  def getIncomingHtlcCrossSigned(htlcId: Long): Option[UpdateAddHtlc] = active.head.getIncomingHtlcCrossSigned(htlcId)
  // @formatter:on

  /**
   * @param cmd add HTLC command
   * @return either Left(failure, error message) where failure is a failure message (see BOLT #4 and the Failure Message class) or Right(new commitments, updateAddHtlc)
   */
  def sendAdd(cmd: CMD_ADD_HTLC, currentHeight: BlockHeight, channelConf: ChannelConf, feerates: FeeratesPerKw, feeConf: OnChainFeeConf): Either[ChannelException, (Commitments, UpdateAddHtlc)] = {
    // we must ensure we're not relaying htlcs that are already expired, otherwise the downstream channel will instantly close
    // NB: we add a 3 blocks safety to reduce the probability of running into this when our bitcoin node is slightly outdated
    val minExpiry = CltvExpiry(currentHeight + 3)
    if (cmd.cltvExpiry < minExpiry) {
      return Left(ExpiryTooSmall(channelId, minimum = minExpiry, actual = cmd.cltvExpiry, blockHeight = currentHeight))
    }
    // we don't want to use too high a refund timeout, because our funds will be locked during that time if the payment is never fulfilled
    val maxExpiry = channelConf.maxExpiryDelta.toCltvExpiry(currentHeight)
    if (cmd.cltvExpiry >= maxExpiry) {
      return Left(ExpiryTooBig(channelId, maximum = maxExpiry, actual = cmd.cltvExpiry, blockHeight = currentHeight))
    }

    // even if remote advertises support for 0 msat htlc, we limit ourselves to values strictly positive, hence the max(1 msat)
    val htlcMinimum = params.remoteParams.htlcMinimum.max(1 msat)
    if (cmd.amount < htlcMinimum) {
      return Left(HtlcValueTooSmall(params.channelId, minimum = htlcMinimum, actual = cmd.amount))
    }

    val add = UpdateAddHtlc(channelId, changes.localNextHtlcId, cmd.amount, cmd.paymentHash, cmd.cltvExpiry, cmd.onion, cmd.nextBlindingKey_opt, cmd.confidence, cmd.fundingFee_opt)
    // we increment the local htlc index and add an entry to the origins map
    val changes1 = changes.addLocalProposal(add).copy(localNextHtlcId = changes.localNextHtlcId + 1)
    val originChannels1 = originChannels + (add.id -> cmd.origin)
    // we verify that this htlc is allowed in every active commitment
    active.map(_.canSendAdd(add.amountMsat, params, changes1, feerates, feeConf))
      .collectFirst { case Left(f) => Left(f) }
      .getOrElse(Right(copy(changes = changes1, originChannels = originChannels1), add))
  }

  def receiveAdd(add: UpdateAddHtlc, feerates: FeeratesPerKw, feeConf: OnChainFeeConf): Either[ChannelException, Commitments] = {
    if (add.id != changes.remoteNextHtlcId) {
      return Left(UnexpectedHtlcId(channelId, expected = changes.remoteNextHtlcId, actual = add.id))
    }

    // we used to not enforce a strictly positive minimum, hence the max(1 msat)
    val htlcMinimum = params.localParams.htlcMinimum.max(1 msat)
    if (add.amountMsat < htlcMinimum) {
      return Left(HtlcValueTooSmall(channelId, minimum = htlcMinimum, actual = add.amountMsat))
    }

    val changes1 = changes.addRemoteProposal(add).copy(remoteNextHtlcId = changes.remoteNextHtlcId + 1)
    // we verify that this htlc is allowed in every active commitment
    active.map(_.canReceiveAdd(add.amountMsat, params, changes1, feerates, feeConf))
      .collectFirst { case Left(f) => Left(f) }
      .getOrElse(Right(copy(changes = changes1)))
  }

  def sendFulfill(cmd: CMD_FULFILL_HTLC): Either[ChannelException, (Commitments, UpdateFulfillHtlc)] =
    getIncomingHtlcCrossSigned(cmd.id) match {
      case Some(htlc) if CommitmentChanges.alreadyProposed(changes.localChanges.proposed, htlc.id) =>
        // we have already sent a fail/fulfill for this htlc
        Left(UnknownHtlcId(channelId, cmd.id))
      case Some(htlc) if htlc.paymentHash == Crypto.sha256(cmd.r) =>
        payment.Monitoring.Metrics.recordIncomingPaymentDistribution(params.remoteNodeId, htlc.amountMsat)
        val fulfill = UpdateFulfillHtlc(channelId, cmd.id, cmd.r)
        Right((copy(changes = changes.addLocalProposal(fulfill)), fulfill))
      case Some(_) => Left(InvalidHtlcPreimage(channelId, cmd.id))
      case None => Left(UnknownHtlcId(channelId, cmd.id))
    }

  def receiveFulfill(fulfill: UpdateFulfillHtlc): Either[ChannelException, (Commitments, Origin, UpdateAddHtlc)] =
    getOutgoingHtlcCrossSigned(fulfill.id) match {
      case Some(htlc) if htlc.paymentHash == Crypto.sha256(fulfill.paymentPreimage) => originChannels.get(fulfill.id) match {
        case Some(origin) =>
          payment.Monitoring.Metrics.recordOutgoingPaymentDistribution(params.remoteNodeId, htlc.amountMsat)
          Right(copy(changes = changes.addRemoteProposal(fulfill)), origin, htlc)
        case None => Left(UnknownHtlcId(channelId, fulfill.id))
      }
      case Some(_) => Left(InvalidHtlcPreimage(channelId, fulfill.id))
      case None => Left(UnknownHtlcId(channelId, fulfill.id))
    }

  def sendFail(cmd: CMD_FAIL_HTLC, nodeSecret: PrivateKey): Either[ChannelException, (Commitments, HtlcFailureMessage)] =
    getIncomingHtlcCrossSigned(cmd.id) match {
      case Some(htlc) if CommitmentChanges.alreadyProposed(changes.localChanges.proposed, htlc.id) =>
        // we have already sent a fail/fulfill for this htlc
        Left(UnknownHtlcId(channelId, cmd.id))
      case Some(htlc) =>
        // we need the shared secret to build the error packet
        OutgoingPaymentPacket.buildHtlcFailure(nodeSecret, cmd, htlc).map(fail => (copy(changes = changes.addLocalProposal(fail)), fail))
      case None => Left(UnknownHtlcId(channelId, cmd.id))
    }

  def sendFailMalformed(cmd: CMD_FAIL_MALFORMED_HTLC): Either[ChannelException, (Commitments, UpdateFailMalformedHtlc)] = {
    // BADONION bit must be set in failure_code
    if ((cmd.failureCode & FailureMessageCodecs.BADONION) == 0) {
      Left(InvalidFailureCode(channelId))
    } else {
      getIncomingHtlcCrossSigned(cmd.id) match {
        case Some(htlc) if CommitmentChanges.alreadyProposed(changes.localChanges.proposed, htlc.id) =>
          // we have already sent a fail/fulfill for this htlc
          Left(UnknownHtlcId(channelId, cmd.id))
        case Some(_) =>
          val fail = UpdateFailMalformedHtlc(channelId, cmd.id, cmd.onionHash, cmd.failureCode)
          Right(copy(changes = changes.addLocalProposal(fail)), fail)
        case None => Left(UnknownHtlcId(channelId, cmd.id))
      }
    }
  }

  def receiveFail(fail: UpdateFailHtlc): Either[ChannelException, (Commitments, Origin, UpdateAddHtlc)] =
    getOutgoingHtlcCrossSigned(fail.id) match {
      case Some(htlc) => originChannels.get(fail.id) match {
        case Some(origin) => Right(copy(changes = changes.addRemoteProposal(fail)), origin, htlc)
        case None => Left(UnknownHtlcId(channelId, fail.id))
      }
      case None => Left(UnknownHtlcId(channelId, fail.id))
    }

  def receiveFailMalformed(fail: UpdateFailMalformedHtlc): Either[ChannelException, (Commitments, Origin, UpdateAddHtlc)] = {
    // A receiving node MUST fail the channel if the BADONION bit in failure_code is not set for update_fail_malformed_htlc.
    if ((fail.failureCode & FailureMessageCodecs.BADONION) == 0) {
      Left(InvalidFailureCode(channelId))
    } else {
      getOutgoingHtlcCrossSigned(fail.id) match {
        case Some(htlc) => originChannels.get(fail.id) match {
          case Some(origin) => Right(copy(changes = changes.addRemoteProposal(fail)), origin, htlc)
          case None => Left(UnknownHtlcId(channelId, fail.id))
        }
        case None => Left(UnknownHtlcId(channelId, fail.id))
      }
    }
  }

  def sendFee(cmd: CMD_UPDATE_FEE, feeConf: OnChainFeeConf): Either[ChannelException, (Commitments, UpdateFee)] = {
    if (!params.localParams.paysCommitTxFees) {
      Left(NonInitiatorCannotSendUpdateFee(channelId))
    } else {
      val fee = UpdateFee(channelId, cmd.feeratePerKw)
      // update_fee replace each other, so we can remove previous ones
      val changes1 = changes.copy(localChanges = changes.localChanges.copy(proposed = changes.localChanges.proposed.filterNot(_.isInstanceOf[UpdateFee]) :+ fee))
      active.map(_.canSendFee(cmd.feeratePerKw, params, changes1, feeConf))
        .collectFirst { case Left(f) => Left(f) }
        .getOrElse {
          Metrics.LocalFeeratePerByte.withTag(Tags.CommitmentFormat, params.commitmentFormat.toString).record(FeeratePerByte(cmd.feeratePerKw).feerate.toLong)
          Right(copy(changes = changes1), fee)
        }
    }
  }

  def receiveFee(fee: UpdateFee, feerates: FeeratesPerKw, feeConf: OnChainFeeConf)(implicit log: LoggingAdapter): Either[ChannelException, Commitments] = {
    if (params.localParams.paysCommitTxFees) {
      Left(NonInitiatorCannotSendUpdateFee(channelId))
    } else if (fee.feeratePerKw < FeeratePerKw.MinimumFeeratePerKw) {
      Left(FeerateTooSmall(channelId, remoteFeeratePerKw = fee.feeratePerKw))
    } else {
      val localFeeratePerKw = feeConf.getCommitmentFeerate(feerates, params.remoteNodeId, params.commitmentFormat, active.head.capacity)
      log.info("remote feeratePerKw={}, local feeratePerKw={}, ratio={}", fee.feeratePerKw, localFeeratePerKw, fee.feeratePerKw.toLong.toDouble / localFeeratePerKw.toLong)
      // update_fee replace each other, so we can remove previous ones
      val changes1 = changes.copy(remoteChanges = changes.remoteChanges.copy(proposed = changes.remoteChanges.proposed.filterNot(_.isInstanceOf[UpdateFee]) :+ fee))
      active.map(_.canReceiveFee(fee.feeratePerKw, params, changes1, feerates, feeConf))
        .collectFirst { case Left(f) => Left(f) }
        .getOrElse {
          Metrics.RemoteFeeratePerByte.withTag(Tags.CommitmentFormat, params.commitmentFormat.toString).record(FeeratePerByte(fee.feeratePerKw).feerate.toLong)
          Right(copy(changes = changes1))
        }
    }
  }

  def sendCommit(keyManager: ChannelKeyManager, nextRemoteNonces: List[IndividualNonce] = List.empty)(implicit log: LoggingAdapter): Either[ChannelException, (Commitments, Seq[CommitSig])] = {
    remoteNextCommitInfo match {
      case Right(_) if !changes.localHasChanges => Left(CannotSignWithoutChanges(channelId))
      case Right(remoteNextPerCommitmentPoint) =>
        val (active1, sigs) = if (this.params.commitmentFormat.useTaproot) {
          require(active.size <= nextRemoteNonces.size, s"we have ${active.size} commitments but ${nextRemoteNonces.size} remote musig2 nonces")
          active.zip(nextRemoteNonces).map { case (c, n) => c.sendCommit(keyManager, params, changes, remoteNextPerCommitmentPoint, active.size, Some(n)) } unzip
        } else {
          active.map(_.sendCommit(keyManager, params, changes, remoteNextPerCommitmentPoint, active.size, None)).unzip
        }
        val commitments1 = copy(
          changes = changes.copy(
            localChanges = changes.localChanges.copy(proposed = Nil, signed = changes.localChanges.proposed),
            remoteChanges = changes.remoteChanges.copy(acked = Nil, signed = changes.remoteChanges.acked),
          ),
          active = active1,
          remoteNextCommitInfo = Left(WaitForRev(localCommitIndex))
        )
        Right(commitments1, sigs)
      case Left(_) => Left(CannotSignBeforeRevocation(channelId))
    }
  }

  def receiveCommit(commits: Seq[CommitSig], keyManager: ChannelKeyManager)(implicit log: LoggingAdapter): Either[ChannelException, (Commitments, RevokeAndAck)] = {
    // We may receive more commit_sig than the number of active commitments, because there can be a race where we send
    // splice_locked while our peer is sending us a batch of commit_sig. When that happens, we simply need to discard
    // the commit_sig that belong to commitments we deactivated.
    if (commits.size < active.size) {
      return Left(CommitSigCountMismatch(channelId, active.size, commits.size))
    }
    val channelKeyPath = keyManager.keyPath(params.localParams, params.channelConfig)
    val localPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, localCommitIndex + 1)
    // Signatures are sent in order (most recent first), calling `zip` will drop trailing sigs that are for deactivated/pruned commitments.
    val active1 = active.zip(commits).map { case (commitment, commit) =>
      val localNonce_opt = if (params.commitmentFormat.useTaproot) {
        Some(keyManager.verificationNonce(params.localParams.fundingKeyPath, commitment.fundingTxIndex, channelKeyPath, localCommitIndex + 1))
      } else {
        None
      }
      commitment.receiveCommit(keyManager, params, changes, localPerCommitmentPoint, commit, localNonce_opt) match {
        case Left(f) => return Left(f)
        case Right(commitment1) => commitment1
      }
    }
    // we will send our revocation preimage + our next revocation hash
    val localPerCommitmentSecret = keyManager.commitmentSecret(channelKeyPath, localCommitIndex)
    val localNextPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, localCommitIndex + 2)
    val tlvStream: TlvStream[RevokeAndAckTlv] = if (params.commitmentFormat.useTaproot) {
      val nonces = this.active.map(c => {
        val n = keyManager.verificationNonce(params.localParams.fundingKeyPath, c.fundingTxIndex, channelKeyPath, localCommitIndex + 2)
        log.debug(s"revokeandack: creating verification nonce ${n._2} fundingIndex = ${c.fundingTxIndex} commit index = ${localCommitIndex + 2}")
        n
      })
      TlvStream(RevokeAndAckTlv.NextLocalNoncesTlv(nonces.map(_._2).toList))
    } else {
      TlvStream.empty
    }
    val revocation = RevokeAndAck(
      channelId = channelId,
      perCommitmentSecret = localPerCommitmentSecret,
      nextPerCommitmentPoint = localNextPerCommitmentPoint,
      tlvStream
    )
    val commitments1 = copy(
      changes = changes.copy(
        localChanges = changes.localChanges.copy(acked = Nil),
        remoteChanges = changes.remoteChanges.copy(proposed = Nil, acked = changes.remoteChanges.acked ++ changes.remoteChanges.proposed),
      ),
      active = active1
    )
    Right(commitments1, revocation)
  }

  def receiveRevocation(revocation: RevokeAndAck, maxDustExposure: Satoshi): Either[ChannelException, (Commitments, Seq[PostRevocationAction])] = {
    // we receive a revocation because we just sent them a sig for their next commit tx
    remoteNextCommitInfo match {
      case Right(_) => Left(UnexpectedRevocation(channelId))
      case Left(_) if revocation.perCommitmentSecret.publicKey != active.head.remoteCommit.remotePerCommitmentPoint => Left(InvalidRevocation(channelId))
      case Left(_) if this.params.commitmentFormat.useTaproot && revocation.nexLocalNonces.isEmpty => Left(MissingNextLocalNonce(channelId))
      case Left(_) =>
        // Since htlcs are shared across all commitments, we generate the actions only once based on the first commitment.
        val receivedHtlcs = changes.remoteChanges.signed.collect {
          // we forward adds downstream only when they have been committed by both sides
          // it always happen when we receive a revocation, because they send the add, then they sign it, then we sign it
          case add: UpdateAddHtlc => add
        }
        val remoteSpec = active.head.remoteCommit.spec
        val failedHtlcs = changes.remoteChanges.signed.collect {
          // same for fails: we need to make sure that they are in neither commitment before propagating the fail upstream
          case fail: UpdateFailHtlc =>
            val origin = originChannels(fail.id)
            val add = remoteSpec.findIncomingHtlcById(fail.id).map(_.add).get
            RES_ADD_SETTLED(origin, add, HtlcResult.RemoteFail(fail))
          // same as above
          case fail: UpdateFailMalformedHtlc =>
            val origin = originChannels(fail.id)
            val add = remoteSpec.findIncomingHtlcById(fail.id).map(_.add).get
            RES_ADD_SETTLED(origin, add, HtlcResult.RemoteFailMalformed(fail))
        }
        val (acceptedHtlcs, rejectedHtlcs) = {
          // the received htlcs have already been added to commitments (they've been signed by our peer), and may already
          // overflow our dust exposure (we cannot prevent them from adding htlcs): we artificially remove them before
          // deciding which we'll keep and relay and which we'll fail without relaying.
          val localSpec = active.head.localCommit.spec
          val localSpecWithoutNewHtlcs = localSpec.copy(htlcs = localSpec.htlcs.filter {
            case IncomingHtlc(add) if receivedHtlcs.contains(add) => false
            case _ => true
          })
          // NB: we are supposed to keep nextRemoteCommit_opt consistent with remoteNextCommitInfo: this should exist.
          val nextRemoteSpec = active.head.nextRemoteCommit_opt.get.commit.spec
          val remoteSpecWithoutNewHtlcs = nextRemoteSpec.copy(htlcs = nextRemoteSpec.htlcs.filter {
            case OutgoingHtlc(add) if receivedHtlcs.contains(add) => false
            case _ => true
          })
          val localReduced = DustExposure.reduceForDustExposure(localSpecWithoutNewHtlcs, changes.localChanges.all, changes.remoteChanges.acked)
          val localCommitDustExposure = DustExposure.computeExposure(localReduced, params.localParams.dustLimit, params.commitmentFormat)
          val remoteReduced = DustExposure.reduceForDustExposure(remoteSpecWithoutNewHtlcs, changes.remoteChanges.acked, changes.localChanges.all)
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
        val completedOutgoingHtlcs = changes.remoteChanges.signed.collect {
          case fulfill: UpdateFulfillHtlc => fulfill.id
          case fail: UpdateFailHtlc => fail.id
          case fail: UpdateFailMalformedHtlc => fail.id
        }
        // we remove the newly completed htlcs from the origin map
        val originChannels1 = originChannels -- completedOutgoingHtlcs
        val active1 = active.map(c => c.copy(
          remoteCommit = c.nextRemoteCommit_opt.get.commit,
          nextRemoteCommit_opt = None,
        ))
        val commitments1 = copy(
          changes = changes.copy(
            localChanges = changes.localChanges.copy(signed = Nil, acked = changes.localChanges.acked ++ changes.localChanges.signed),
            remoteChanges = changes.remoteChanges.copy(signed = Nil),
          ),
          active = active1,
          remoteNextCommitInfo = Right(revocation.nextPerCommitmentPoint),
          remotePerCommitmentSecrets = remotePerCommitmentSecrets.addHash(revocation.perCommitmentSecret.value, 0xFFFFFFFFFFFFL - remoteCommitIndex),
          originChannels = originChannels1,
        )
        Right(commitments1, actions)
    }
  }

  def discardUnsignedUpdates()(implicit log: LoggingAdapter): Commitments = {
    this.copy(changes = changes.discardUnsignedUpdates())
  }

  def validateSeed(keyManager: ChannelKeyManager): Boolean = {
    active.forall { commitment =>
      val localFundingKey = keyManager.fundingPublicKey(params.localParams.fundingKeyPath, commitment.fundingTxIndex).publicKey
      val remoteFundingKey = commitment.remoteFundingPubKey
      val fundingScript = if (params.commitmentFormat.useTaproot) {
        Script.write(Scripts.musig2FundingScript(localFundingKey, remoteFundingKey))
      } else {
        Script.write(Scripts.multiSig2of2(localFundingKey, remoteFundingKey))
      }
      commitment.commitInput.redeemScriptOrScriptTree == Left(fundingScript)
    }
  }

  /** This function should be used to ignore a commit_sig that we've already received. */
  def ignoreRetransmittedCommitSig(commitSig: CommitSig): Boolean = {
    val latestRemoteSig = latest.localCommit.commitTxAndRemoteSig.remoteSig
    params.channelFeatures.hasFeature(Features.DualFunding) && commitSig.batchSize == 1 && latestRemoteSig == commitSig.sigOrPartialSig
  }

  def localFundingSigs(fundingTxId: TxId): Option[TxSignatures] = {
    all.find(_.fundingTxId == fundingTxId).flatMap(_.localFundingStatus.localSigs_opt)
  }
  
  def liquidityPurchase(fundingTxId: TxId): Option[LiquidityAds.PurchaseBasicInfo] = {
    all.find(_.fundingTxId == fundingTxId).flatMap(_.localFundingStatus.liquidityPurchase_opt)
  }

  /**
   * Update the local/remote funding status
   *
   * @param updateMethod This method is tricky: it passes the fundingTxIndex of the commitment corresponding to the
   *                     fundingTxId, because in the remote case we may update several commitments.
   */
  private def updateFundingStatus(fundingTxId: TxId, updateMethod: Long => PartialFunction[Commitment, Commitment])(implicit log: LoggingAdapter): Either[Commitments, (Commitments, Commitment)] = {
    all.find(_.fundingTxId == fundingTxId) match {
      case Some(commitment) =>
        val commitments1 = copy(
          active = active.map(updateMethod(commitment.fundingTxIndex)),
          inactive = inactive.map(updateMethod(commitment.fundingTxIndex))
        )
        val commitment1 = commitments1.all.find(_.fundingTxId == fundingTxId).get // NB: this commitment might be pruned at the next line
        val commitments2 = commitments1.deactivateCommitments().pruneCommitments()
        Right(commitments2, commitment1)
      case None =>
        log.warning(s"fundingTxId=$fundingTxId doesn't match any of our funding txs")
        Left(this)
    }
  }

  def updateLocalFundingStatus(fundingTxId: TxId, status: LocalFundingStatus)(implicit log: LoggingAdapter): Either[Commitments, (Commitments, Commitment)] =
    updateFundingStatus(fundingTxId, _ => {
      case c if c.fundingTxId == fundingTxId =>
        log.info(s"setting localFundingStatus=${status.getClass.getSimpleName} for fundingTxId=${c.fundingTxId} fundingTxIndex=${c.fundingTxIndex}")
        c.copy(localFundingStatus = status)
      case c => c
    })

  def updateRemoteFundingStatus(fundingTxId: TxId)(implicit log: LoggingAdapter): Either[Commitments, (Commitments, Commitment)] =
    updateFundingStatus(fundingTxId, fundingTxIndex => {
      // all funding older than this one are considered locked
      case c if c.fundingTxId == fundingTxId || c.fundingTxIndex < fundingTxIndex =>
        log.info(s"setting remoteFundingStatus=${RemoteFundingStatus.Locked.getClass.getSimpleName} for fundingTxId=${c.fundingTxId} fundingTxIndex=${c.fundingTxIndex}")
        c.copy(remoteFundingStatus = RemoteFundingStatus.Locked)
      case c => c
    })

  /**
   * Commitments are considered inactive when they have been superseded by a newer commitment, but can still potentially
   * end up on-chain. This is a consequence of using zero-conf. Inactive commitments will be cleaned up by
   * [[pruneCommitments()]], when the next funding tx confirms.
   */
  private def deactivateCommitments()(implicit log: LoggingAdapter): Commitments = {
    // When a commitment is locked, it implicitly locks all previous commitments.
    // This ensures that we only have to send splice_locked for the latest commitment instead of sending it for every commitment.
    // A side-effect is that previous commitments that are implicitly locked don't necessarily have their status correctly set.
    // That's why we look at locked commitments separately and then select the one with the oldest fundingTxIndex.
    val lastLocalLocked_opt = active.find(_.localFundingStatus.isInstanceOf[LocalFundingStatus.Locked])
    val lastRemoteLocked_opt = active.find(_.remoteFundingStatus == RemoteFundingStatus.Locked)
    val lastLocked_opt = (lastLocalLocked_opt, lastRemoteLocked_opt) match {
      // We select the locked commitment with the smaller value for fundingTxIndex, but both have to be defined.
      // If both have the same fundingTxIndex, they must actually be the same commitment, because:
      //  - we only allow RBF attempts when we're not using zero-conf
      //  - transactions with the same fundingTxIndex double-spend each other, so only one of them can confirm
      //  - we don't allow creating a splice on top of an unconfirmed transaction that has RBF attempts (because it
      //    would become invalid if another of the RBF attempts end up being confirmed)
      case (Some(lastLocalLocked), Some(lastRemoteLocked)) => Some(Seq(lastLocalLocked, lastRemoteLocked).minBy(_.fundingTxIndex))
      // Special case for the initial funding tx, we only require a local lock because channel_ready doesn't explicitly reference a funding tx.
      case (Some(lastLocalLocked), None) if lastLocalLocked.fundingTxIndex == 0 => Some(lastLocalLocked)
      case _ => None
    }
    lastLocked_opt match {
      case Some(lastLocked) =>
        // all commitments older than this one are inactive
        val inactive1 = active.filter(c => c.fundingTxId != lastLocked.fundingTxId && c.fundingTxIndex <= lastLocked.fundingTxIndex)
        inactive1.foreach(c => log.info("deactivating commitment fundingTxIndex={} fundingTxId={}", c.fundingTxIndex, c.fundingTxId))
        copy(
          active = active diff inactive1,
          inactive = inactive1 ++ inactive
        )
      case _ =>
        this
    }
  }

  /**
   * We can prune commitments in two cases:
   * - their funding tx has been permanently double-spent by the funding tx of a concurrent commitment (happens when using RBF)
   * - their funding tx has been permanently spent by a splice tx
   */
  private def pruneCommitments()(implicit log: LoggingAdapter): Commitments = {
    all
      .filter(_.localFundingStatus.isInstanceOf[LocalFundingStatus.ConfirmedFundingTx])
      .sortBy(_.fundingTxIndex)
      .lastOption match {
      case Some(lastConfirmed) =>
        // We can prune all other commitments with the same or lower funding index.
        // NB: we cannot prune active commitments, even if we know that they have been double-spent, because our peer
        // may not yet be aware of it, and will expect us to send commit_sig.
        val pruned = inactive.filter(c => c.fundingTxId != lastConfirmed.fundingTxId && c.fundingTxIndex <= lastConfirmed.fundingTxIndex)
        pruned.foreach(c => log.info("pruning commitment fundingTxIndex={} fundingTxId={}", c.fundingTxIndex, c.fundingTxId))
        copy(inactive = inactive diff pruned)
      case _ =>
        this
    }
  }

  /**
   * Find the corresponding commitment, based on a spending transaction.
   *
   * @param spendingTx A transaction that may spend a current or former funding tx
   */
  def resolveCommitment(spendingTx: Transaction): Option[Commitment] = {
    all.find(c => spendingTx.txIn.map(_.outPoint).contains(c.commitInput.outPoint))
  }
}

object Commitments {
  // @formatter:off
  sealed trait PostRevocationAction
  object PostRevocationAction {
    case class RelayHtlc(incomingHtlc: UpdateAddHtlc) extends PostRevocationAction
    case class RejectHtlc(incomingHtlc: UpdateAddHtlc) extends PostRevocationAction
    case class RelayFailure(result: RES_ADD_SETTLED[Origin, HtlcResult]) extends PostRevocationAction
  }
  // @formatter:on
}