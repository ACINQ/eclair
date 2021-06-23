/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.channel

import akka.event.LoggingAdapter
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey, sha256}
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto, Satoshi, SatoshiLong}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.fee.{FeeratePerKw, OnChainFeeConf}
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel.Monitoring.Metrics
import fr.acinq.eclair.crypto.keymanager.ChannelKeyManager
import fr.acinq.eclair.crypto.{Generators, ShaChain}
import fr.acinq.eclair.payment.OutgoingPacket
import fr.acinq.eclair.payment.relay.Relayer
import fr.acinq.eclair.transactions.DirectedHtlc._
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.protocol._
import scodec.bits.ByteVector

// @formatter:off
case class LocalChanges(proposed: List[UpdateMessage], signed: List[UpdateMessage], acked: List[UpdateMessage]) {
  def all: List[UpdateMessage] = proposed ++ signed ++ acked
}
case class RemoteChanges(proposed: List[UpdateMessage], acked: List[UpdateMessage], signed: List[UpdateMessage]) {
  def all: List[UpdateMessage] = proposed ++ signed ++ acked
}
case class Changes(ourChanges: LocalChanges, theirChanges: RemoteChanges)
case class HtlcTxAndRemoteSig(htlcTx: HtlcTx, remoteSig: ByteVector64)
case class CommitTxAndRemoteSig(commitTx: CommitTx, remoteSig: ByteVector64)
case class LocalCommit(index: Long, spec: CommitmentSpec, commitTxAndRemoteSig: CommitTxAndRemoteSig, htlcTxsAndRemoteSigs: List[HtlcTxAndRemoteSig])
case class RemoteCommit(index: Long, spec: CommitmentSpec, txid: ByteVector32, remotePerCommitmentPoint: PublicKey)
case class WaitingForRevocation(nextRemoteCommit: RemoteCommit, sent: CommitSig, sentAfterLocalCommitIndex: Long, reSignAsap: Boolean = false)
// @formatter:on

// @formatter:off
trait AbstractCommitments {
  def getOutgoingHtlcCrossSigned(htlcId: Long): Option[UpdateAddHtlc]
  def getIncomingHtlcCrossSigned(htlcId: Long): Option[UpdateAddHtlc]
  def localNodeId: PublicKey
  def remoteNodeId: PublicKey
  def capacity: Satoshi
  def availableBalanceForReceive: MilliSatoshi
  def availableBalanceForSend: MilliSatoshi
  def originChannels: Map[Long, Origin]
  def channelId: ByteVector32
  def announceChannel: Boolean
}
// @formatter:on

/**
 * about remoteNextCommitInfo:
 * we either:
 * - have built and signed their next commit tx with their next revocation hash which can now be discarded
 * - have their next per-commitment point
 * So, when we've signed and sent a commit message and are waiting for their revocation message,
 * theirNextCommitInfo is their next commit tx. The rest of the time, it is their next per-commitment point
 */
case class Commitments(channelId: ByteVector32,
                       channelConfig: ChannelConfig,
                       channelFeatures: ChannelFeatures,
                       localParams: LocalParams, remoteParams: RemoteParams,
                       channelFlags: Byte,
                       localCommit: LocalCommit, remoteCommit: RemoteCommit,
                       localChanges: LocalChanges, remoteChanges: RemoteChanges,
                       localNextHtlcId: Long, remoteNextHtlcId: Long,
                       originChannels: Map[Long, Origin], // for outgoing htlcs relayed through us, details about the corresponding incoming htlcs
                       remoteNextCommitInfo: Either[WaitingForRevocation, PublicKey],
                       commitInput: InputInfo,
                       remotePerCommitmentSecrets: ShaChain) extends AbstractCommitments {

  require(channelFeatures.paysDirectlyToWallet == localParams.walletStaticPaymentBasepoint.isDefined, s"localParams.walletStaticPaymentBasepoint must be defined only for commitments that pay directly to our wallet (channel features: $channelFeatures")

  /**
   *
   * @param scriptPubKey optional local script pubkey provided in CMD_CLOSE
   * @return the actual local shutdown script that we should use
   */
  def getLocalShutdownScript(scriptPubKey: Option[ByteVector]): Either[ChannelException, ByteVector] = {
    // to check whether shutdown_any_segwit is active we check features in local and remote parameters, which are negotiated each time we connect to our peer.
    val allowAnySegwit = Features.canUseFeature(localParams.initFeatures, remoteParams.initFeatures, Features.ShutdownAnySegwit)
    (channelFeatures.hasFeature(Features.OptionUpfrontShutdownScript), scriptPubKey) match {
      case (true, Some(script)) if script != localParams.defaultFinalScriptPubKey => Left(InvalidFinalScript(channelId))
      case (false, Some(script)) if !Closing.isValidFinalScriptPubkey(script, allowAnySegwit) => Left(InvalidFinalScript(channelId))
      case (false, Some(script)) => Right(script)
      case _ => Right(localParams.defaultFinalScriptPubKey)
    }
  }

  /**
   *
   * @param remoteScriptPubKey remote script included in a Shutdown message
   * @return the actual remote script that we should use
   */
  def getRemoteShutdownScript(remoteScriptPubKey: ByteVector): Either[ChannelException, ByteVector] = {
    // to check whether shutdown_any_segwit is active we check features in local and remote parameters, which are negotiated each time we connect to our peer.
    val allowAnySegwit = Features.canUseFeature(localParams.initFeatures, remoteParams.initFeatures, Features.ShutdownAnySegwit)
    (channelFeatures.hasFeature(Features.OptionUpfrontShutdownScript), remoteParams.shutdownScript) match {
      case (false, _) if !Closing.isValidFinalScriptPubkey(remoteScriptPubKey, allowAnySegwit )=> Left(InvalidFinalScript(channelId))
      case (false, _)  => Right(remoteScriptPubKey)
      case (true, None) if !Closing.isValidFinalScriptPubkey(remoteScriptPubKey, allowAnySegwit )=> {
        // this is a special case: they set option_upfront_shutdown_script but did not provide a script in their open/accept message
        Left(InvalidFinalScript(channelId))
      }
      case (true, None) => Right(remoteScriptPubKey)
      case (true, Some(script)) if script != remoteScriptPubKey => Left(InvalidFinalScript(channelId))
      case (true, Some(script)) => Right(script)
    }
  }

  def hasNoPendingHtlcs: Boolean = localCommit.spec.htlcs.isEmpty && remoteCommit.spec.htlcs.isEmpty && remoteNextCommitInfo.isRight

  def hasNoPendingHtlcsOrFeeUpdate: Boolean =
    remoteNextCommitInfo.isRight &&
      localCommit.spec.htlcs.isEmpty &&
      remoteCommit.spec.htlcs.isEmpty &&
      (localChanges.signed ++ localChanges.acked ++ remoteChanges.signed ++ remoteChanges.acked).collectFirst { case _: UpdateFee => true }.isEmpty

  def hasPendingOrProposedHtlcs: Boolean = !hasNoPendingHtlcs ||
    localChanges.all.exists(_.isInstanceOf[UpdateAddHtlc]) ||
    remoteChanges.all.exists(_.isInstanceOf[UpdateAddHtlc])

  def timedOutOutgoingHtlcs(blockheight: Long): Set[UpdateAddHtlc] = {
    def expired(add: UpdateAddHtlc) = blockheight >= add.cltvExpiry.toLong

    localCommit.spec.htlcs.collect(outgoing).filter(expired) ++
      remoteCommit.spec.htlcs.collect(incoming).filter(expired) ++
      remoteNextCommitInfo.left.toSeq.flatMap(_.nextRemoteCommit.spec.htlcs.collect(incoming).filter(expired).toSet)
  }

  /**
   * Return the outgoing HTLC with the given id if it is:
   *  - signed by us in their commitment transaction (remote)
   *  - signed by them in our commitment transaction (local)
   *
   * NB: if we're in the middle of fulfilling or failing that HTLC, it will not be returned by this function.
   */
  def getOutgoingHtlcCrossSigned(htlcId: Long): Option[UpdateAddHtlc] = for {
    localSigned <- remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit).getOrElse(remoteCommit).spec.findIncomingHtlcById(htlcId)
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
    localSigned <- remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit).getOrElse(remoteCommit).spec.findOutgoingHtlcById(htlcId)
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
  def almostTimedOutIncomingHtlcs(blockheight: Long, fulfillSafety: CltvExpiryDelta): Set[UpdateAddHtlc] = {
    def nearlyExpired(add: UpdateAddHtlc) = blockheight >= (add.cltvExpiry - fulfillSafety).toLong

    localCommit.spec.htlcs.collect(incoming).filter(nearlyExpired)
  }

  /**
   * Return a fully signed commit tx, that can be published as-is.
   */
  def fullySignedLocalCommitTx(keyManager: ChannelKeyManager): CommitTx = {
    val unsignedCommitTx = localCommit.commitTxAndRemoteSig.commitTx
    val localSig = keyManager.sign(unsignedCommitTx, keyManager.fundingPublicKey(localParams.fundingKeyPath), TxOwner.Local, commitmentFormat)
    val remoteSig = localCommit.commitTxAndRemoteSig.remoteSig
    val commitTx = Transactions.addSigs(unsignedCommitTx, keyManager.fundingPublicKey(localParams.fundingKeyPath).publicKey, remoteParams.fundingPubKey, localSig, remoteSig)
    // We verify the remote signature when receiving their commit_sig, so this check should always pass.
    require(Transactions.checkSpendable(commitTx).isSuccess, "commit signatures are invalid")
    commitTx
  }

  val commitmentFormat: CommitmentFormat = channelFeatures.commitmentFormat

  val localNodeId: PublicKey = localParams.nodeId

  val remoteNodeId: PublicKey = remoteParams.nodeId

  val announceChannel: Boolean = (channelFlags & 0x01) != 0

  val capacity: Satoshi = commitInput.txOut.amount

  // NB: when computing availableBalanceForSend and availableBalanceForReceive, the funder keeps an extra buffer on top
  // of its usual channel reserve to avoid getting channels stuck in case the on-chain feerate increases (see
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

  lazy val availableBalanceForSend: MilliSatoshi = {
    // we need to base the next current commitment on the last sig we sent, even if we didn't yet receive their revocation
    val remoteCommit1 = remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit).getOrElse(remoteCommit)
    val reduced = CommitmentSpec.reduce(remoteCommit1.spec, remoteChanges.acked, localChanges.proposed)
    val balanceNoFees = (reduced.toRemote - remoteParams.channelReserve).max(0 msat)
    if (localParams.isFunder) {
      // The funder always pays the on-chain fees, so we must subtract that from the amount we can send.
      val commitFees = commitTxTotalCostMsat(remoteParams.dustLimit, reduced, commitmentFormat)
      // the funder needs to keep a "funder fee buffer" (see explanation above)
      val funderFeeBuffer = commitTxTotalCostMsat(remoteParams.dustLimit, reduced.copy(feeratePerKw = reduced.feeratePerKw * 2), commitmentFormat) + htlcOutputFee(reduced.feeratePerKw * 2, commitmentFormat)
      val amountToReserve = commitFees.max(funderFeeBuffer)
      if (balanceNoFees - amountToReserve < offeredHtlcTrimThreshold(remoteParams.dustLimit, reduced, commitmentFormat)) {
        // htlc will be trimmed
        (balanceNoFees - amountToReserve).max(0 msat)
      } else {
        // htlc will have an output in the commitment tx, so there will be additional fees.
        val commitFees1 = commitFees + htlcOutputFee(reduced.feeratePerKw, commitmentFormat)
        // we take the additional fees for that htlc output into account in the fee buffer at a x2 feerate increase
        val funderFeeBuffer1 = funderFeeBuffer + htlcOutputFee(reduced.feeratePerKw * 2, commitmentFormat)
        val amountToReserve1 = commitFees1.max(funderFeeBuffer1)
        (balanceNoFees - amountToReserve1).max(0 msat)
      }
    } else {
      // The fundee doesn't pay on-chain fees.
      balanceNoFees
    }
  }

  lazy val availableBalanceForReceive: MilliSatoshi = {
    val reduced = CommitmentSpec.reduce(localCommit.spec, localChanges.acked, remoteChanges.proposed)
    val balanceNoFees = (reduced.toRemote - localParams.channelReserve).max(0 msat)
    if (localParams.isFunder) {
      // The fundee doesn't pay on-chain fees so we don't take those into account when receiving.
      balanceNoFees
    } else {
      // The funder always pays the on-chain fees, so we must subtract that from the amount we can receive.
      val commitFees = commitTxTotalCostMsat(localParams.dustLimit, reduced, commitmentFormat)
      // we expected the funder to keep a "funder fee buffer" (see explanation above)
      val funderFeeBuffer = commitTxTotalCostMsat(localParams.dustLimit, reduced.copy(feeratePerKw = reduced.feeratePerKw * 2), commitmentFormat) + htlcOutputFee(reduced.feeratePerKw * 2, commitmentFormat)
      val amountToReserve = commitFees.max(funderFeeBuffer)
      if (balanceNoFees - amountToReserve < receivedHtlcTrimThreshold(localParams.dustLimit, reduced, commitmentFormat)) {
        // htlc will be trimmed
        (balanceNoFees - amountToReserve).max(0 msat)
      } else {
        // htlc will have an output in the commitment tx, so there will be additional fees.
        val commitFees1 = commitFees + htlcOutputFee(reduced.feeratePerKw, commitmentFormat)
        // we take the additional fees for that htlc output into account in the fee buffer at a x2 feerate increase
        val funderFeeBuffer1 = funderFeeBuffer + htlcOutputFee(reduced.feeratePerKw * 2, commitmentFormat)
        val amountToReserve1 = commitFees1.max(funderFeeBuffer1)
        (balanceNoFees - amountToReserve1).max(0 msat)
      }
    }
  }
}

object Commitments {

  /**
   * Add a change to our proposed change list.
   *
   * @param commitments current commitments.
   * @param proposal    proposed change to add.
   * @return an updated commitment instance.
   */
  private def addLocalProposal(commitments: Commitments, proposal: UpdateMessage): Commitments =
    commitments.copy(localChanges = commitments.localChanges.copy(proposed = commitments.localChanges.proposed :+ proposal))

  private def addRemoteProposal(commitments: Commitments, proposal: UpdateMessage): Commitments =
    commitments.copy(remoteChanges = commitments.remoteChanges.copy(proposed = commitments.remoteChanges.proposed :+ proposal))

  def alreadyProposed(changes: List[UpdateMessage], id: Long): Boolean = changes.exists {
    case u: UpdateFulfillHtlc => id == u.id
    case u: UpdateFailHtlc => id == u.id
    case u: UpdateFailMalformedHtlc => id == u.id
    case _ => false
  }

  /**
   * @param commitments current commitments
   * @param cmd         add HTLC command
   * @return either Left(failure, error message) where failure is a failure message (see BOLT #4 and the Failure Message class) or Right(new commitments, updateAddHtlc)
   */
  def sendAdd(commitments: Commitments, cmd: CMD_ADD_HTLC, blockHeight: Long, feeConf: OnChainFeeConf): Either[ChannelException, (Commitments, UpdateAddHtlc)] = {
    // we must ensure we're not relaying htlcs that are already expired, otherwise the downstream channel will instantly close
    // NB: we add a 3 blocks safety to reduce the probability of running into this when our bitcoin node is slightly outdated
    val minExpiry = CltvExpiry(blockHeight + 3)
    if (cmd.cltvExpiry < minExpiry) {
      return Left(ExpiryTooSmall(commitments.channelId, minimum = minExpiry, actual = cmd.cltvExpiry, blockCount = blockHeight))
    }
    // we don't want to use too high a refund timeout, because our funds will be locked during that time if the payment is never fulfilled
    val maxExpiry = Channel.MAX_CLTV_EXPIRY_DELTA.toCltvExpiry(blockHeight)
    if (cmd.cltvExpiry >= maxExpiry) {
      return Left(ExpiryTooBig(commitments.channelId, maximum = maxExpiry, actual = cmd.cltvExpiry, blockCount = blockHeight))
    }

    // even if remote advertises support for 0 msat htlc, we limit ourselves to values strictly positive, hence the max(1 msat)
    val htlcMinimum = commitments.remoteParams.htlcMinimum.max(1 msat)
    if (cmd.amount < htlcMinimum) {
      return Left(HtlcValueTooSmall(commitments.channelId, minimum = htlcMinimum, actual = cmd.amount))
    }

    // we allowed mismatches between our feerates and our remote's as long as commitments didn't contain any HTLC at risk
    // we need to verify that we're not disagreeing on feerates anymore before offering new HTLCs
    // NB: there may be a pending update_fee that hasn't been applied yet that needs to be taken into account
    val localFeeratePerKw = feeConf.getCommitmentFeerate(commitments.remoteNodeId, commitments.channelFeatures, commitments.capacity, None)
    val remoteFeeratePerKw = commitments.localCommit.spec.feeratePerKw +: commitments.remoteChanges.all.collect { case f: UpdateFee => f.feeratePerKw }
    remoteFeeratePerKw.find(feerate => feeConf.feerateToleranceFor(commitments.remoteNodeId).isFeeDiffTooHigh(commitments.channelFeatures, localFeeratePerKw, feerate)) match {
      case Some(feerate) => return Left(FeerateTooDifferent(commitments.channelId, localFeeratePerKw = localFeeratePerKw, remoteFeeratePerKw = feerate))
      case None =>
    }

    // let's compute the current commitment *as seen by them* with this change taken into account
    val add = UpdateAddHtlc(commitments.channelId, commitments.localNextHtlcId, cmd.amount, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)
    // we increment the local htlc index and add an entry to the origins map
    val commitments1 = addLocalProposal(commitments, add).copy(localNextHtlcId = commitments.localNextHtlcId + 1, originChannels = commitments.originChannels + (add.id -> cmd.origin))
    // we need to base the next current commitment on the last sig we sent, even if we didn't yet receive their revocation
    val remoteCommit1 = commitments1.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit).getOrElse(commitments1.remoteCommit)
    val reduced = CommitmentSpec.reduce(remoteCommit1.spec, commitments1.remoteChanges.acked, commitments1.localChanges.proposed)
    // the HTLC we are about to create is outgoing, but from their point of view it is incoming
    val outgoingHtlcs = reduced.htlcs.collect(incoming)

    // note that the funder pays the fee, so if sender != funder, both sides will have to afford this payment
    val fees = commitTxTotalCost(commitments1.remoteParams.dustLimit, reduced, commitments.commitmentFormat)
    // the funder needs to keep an extra buffer to be able to handle a x2 feerate increase and an additional htlc to avoid
    // getting the channel stuck (see https://github.com/lightningnetwork/lightning-rfc/issues/728).
    val funderFeeBuffer = commitTxTotalCostMsat(commitments1.remoteParams.dustLimit, reduced.copy(feeratePerKw = reduced.feeratePerKw * 2), commitments.commitmentFormat) + htlcOutputFee(reduced.feeratePerKw * 2, commitments.commitmentFormat)
    // NB: increasing the feerate can actually remove htlcs from the commit tx (if they fall below the trim threshold)
    // which may result in a lower commit tx fee; this is why we take the max of the two.
    val missingForSender = reduced.toRemote - commitments1.remoteParams.channelReserve - (if (commitments1.localParams.isFunder) fees.max(funderFeeBuffer.truncateToSatoshi) else 0.sat)
    val missingForReceiver = reduced.toLocal - commitments1.localParams.channelReserve - (if (commitments1.localParams.isFunder) 0.sat else fees)
    if (missingForSender < 0.msat) {
      return Left(InsufficientFunds(commitments.channelId, amount = cmd.amount, missing = -missingForSender.truncateToSatoshi, reserve = commitments1.remoteParams.channelReserve, fees = if (commitments1.localParams.isFunder) fees else 0.sat))
    } else if (missingForReceiver < 0.msat) {
      if (commitments.localParams.isFunder) {
        // receiver is fundee; it is ok if it can't maintain its channel_reserve for now, as long as its balance is increasing, which is the case if it is receiving a payment
      } else {
        return Left(RemoteCannotAffordFeesForNewHtlc(commitments.channelId, amount = cmd.amount, missing = -missingForReceiver.truncateToSatoshi, reserve = commitments1.remoteParams.channelReserve, fees = fees))
      }
    }

    // We apply local *and* remote restrictions, to ensure both peers are happy with the resulting number of HTLCs.
    // NB: we need the `toSeq` because otherwise duplicate amountMsat would be removed (since outgoingHtlcs is a Set).
    val htlcValueInFlight = outgoingHtlcs.toSeq.map(_.amountMsat).sum
    if (Seq(commitments1.localParams.maxHtlcValueInFlightMsat, commitments1.remoteParams.maxHtlcValueInFlightMsat).min < htlcValueInFlight) {
      // TODO: this should be a specific UPDATE error (but it would require a spec change)
      return Left(HtlcValueTooHighInFlight(commitments.channelId, maximum = Seq(commitments1.localParams.maxHtlcValueInFlightMsat, commitments1.remoteParams.maxHtlcValueInFlightMsat).min, actual = htlcValueInFlight))
    }
    if (Seq(commitments1.localParams.maxAcceptedHtlcs, commitments1.remoteParams.maxAcceptedHtlcs).min < outgoingHtlcs.size) {
      return Left(TooManyAcceptedHtlcs(commitments.channelId, maximum = Seq(commitments1.localParams.maxAcceptedHtlcs, commitments1.remoteParams.maxAcceptedHtlcs).min))
    }

    Right(commitments1, add)
  }

  def receiveAdd(commitments: Commitments, add: UpdateAddHtlc, feeConf: OnChainFeeConf): Either[ChannelException, Commitments] = {
    if (add.id != commitments.remoteNextHtlcId) {
      return Left(UnexpectedHtlcId(commitments.channelId, expected = commitments.remoteNextHtlcId, actual = add.id))
    }

    // we used to not enforce a strictly positive minimum, hence the max(1 msat)
    val htlcMinimum = commitments.localParams.htlcMinimum.max(1 msat)
    if (add.amountMsat < htlcMinimum) {
      return Left(HtlcValueTooSmall(commitments.channelId, minimum = htlcMinimum, actual = add.amountMsat))
    }

    // we allowed mismatches between our feerates and our remote's as long as commitments didn't contain any HTLC at risk
    // we need to verify that we're not disagreeing on feerates anymore before accepting new HTLCs
    // NB: there may be a pending update_fee that hasn't been applied yet that needs to be taken into account
    val localFeeratePerKw = feeConf.getCommitmentFeerate(commitments.remoteNodeId, commitments.channelFeatures, commitments.capacity, None)
    val remoteFeeratePerKw = commitments.localCommit.spec.feeratePerKw +: commitments.remoteChanges.all.collect { case f: UpdateFee => f.feeratePerKw }
    remoteFeeratePerKw.find(feerate => feeConf.feerateToleranceFor(commitments.remoteNodeId).isFeeDiffTooHigh(commitments.channelFeatures, localFeeratePerKw, feerate)) match {
      case Some(feerate) => return Left(FeerateTooDifferent(commitments.channelId, localFeeratePerKw = localFeeratePerKw, remoteFeeratePerKw = feerate))
      case None =>
    }

    // let's compute the current commitment *as seen by us* including this change
    val commitments1 = addRemoteProposal(commitments, add).copy(remoteNextHtlcId = commitments.remoteNextHtlcId + 1)
    val reduced = CommitmentSpec.reduce(commitments1.localCommit.spec, commitments1.localChanges.acked, commitments1.remoteChanges.proposed)
    val incomingHtlcs = reduced.htlcs.collect(incoming)

    // note that the funder pays the fee, so if sender != funder, both sides will have to afford this payment
    val fees = commitTxTotalCost(commitments1.remoteParams.dustLimit, reduced, commitments.commitmentFormat)
    // NB: we don't enforce the funderFeeReserve (see sendAdd) because it would confuse a remote funder that doesn't have this mitigation in place
    // We could enforce it once we're confident a large portion of the network implements it.
    val missingForSender = reduced.toRemote - commitments1.localParams.channelReserve - (if (commitments1.localParams.isFunder) 0.sat else fees)
    val missingForReceiver = reduced.toLocal - commitments1.remoteParams.channelReserve - (if (commitments1.localParams.isFunder) fees else 0.sat)
    if (missingForSender < 0.sat) {
      return Left(InsufficientFunds(commitments.channelId, amount = add.amountMsat, missing = -missingForSender.truncateToSatoshi, reserve = commitments1.localParams.channelReserve, fees = if (commitments1.localParams.isFunder) 0.sat else fees))
    } else if (missingForReceiver < 0.sat) {
      if (commitments.localParams.isFunder) {
        return Left(CannotAffordFees(commitments.channelId, missing = -missingForReceiver.truncateToSatoshi, reserve = commitments1.remoteParams.channelReserve, fees = fees))
      } else {
        // receiver is fundee; it is ok if it can't maintain its channel_reserve for now, as long as its balance is increasing, which is the case if it is receiving a payment
      }
    }

    // NB: we need the `toSeq` because otherwise duplicate amountMsat would be removed (since incomingHtlcs is a Set).
    val htlcValueInFlight = incomingHtlcs.toSeq.map(_.amountMsat).sum
    if (commitments1.localParams.maxHtlcValueInFlightMsat < htlcValueInFlight) {
      return Left(HtlcValueTooHighInFlight(commitments.channelId, maximum = commitments1.localParams.maxHtlcValueInFlightMsat, actual = htlcValueInFlight))
    }

    if (incomingHtlcs.size > commitments1.localParams.maxAcceptedHtlcs) {
      return Left(TooManyAcceptedHtlcs(commitments.channelId, maximum = commitments1.localParams.maxAcceptedHtlcs))
    }

    Right(commitments1)
  }

  def sendFulfill(commitments: Commitments, cmd: CMD_FULFILL_HTLC): Either[ChannelException, (Commitments, UpdateFulfillHtlc)] =
    commitments.getIncomingHtlcCrossSigned(cmd.id) match {
      case Some(htlc) if alreadyProposed(commitments.localChanges.proposed, htlc.id) =>
        // we have already sent a fail/fulfill for this htlc
        Left(UnknownHtlcId(commitments.channelId, cmd.id))
      case Some(htlc) if htlc.paymentHash == sha256(cmd.r) =>
        val fulfill = UpdateFulfillHtlc(commitments.channelId, cmd.id, cmd.r)
        val commitments1 = addLocalProposal(commitments, fulfill)
        payment.Monitoring.Metrics.recordIncomingPaymentDistribution(commitments.remoteParams.nodeId, htlc.amountMsat)
        Right((commitments1, fulfill))
      case Some(_) => Left(InvalidHtlcPreimage(commitments.channelId, cmd.id))
      case None => Left(UnknownHtlcId(commitments.channelId, cmd.id))
    }

  def receiveFulfill(commitments: Commitments, fulfill: UpdateFulfillHtlc): Either[ChannelException, (Commitments, Origin, UpdateAddHtlc)] =
    commitments.getOutgoingHtlcCrossSigned(fulfill.id) match {
      case Some(htlc) if htlc.paymentHash == sha256(fulfill.paymentPreimage) => commitments.originChannels.get(fulfill.id) match {
        case Some(origin) =>
          payment.Monitoring.Metrics.recordOutgoingPaymentDistribution(commitments.remoteParams.nodeId, htlc.amountMsat)
          Right(addRemoteProposal(commitments, fulfill), origin, htlc)
        case None => Left(UnknownHtlcId(commitments.channelId, fulfill.id))
      }
      case Some(_) => Left(InvalidHtlcPreimage(commitments.channelId, fulfill.id))
      case None => Left(UnknownHtlcId(commitments.channelId, fulfill.id))
    }

  def sendFail(commitments: Commitments, cmd: CMD_FAIL_HTLC, nodeSecret: PrivateKey): Either[ChannelException, (Commitments, UpdateFailHtlc)] =
    commitments.getIncomingHtlcCrossSigned(cmd.id) match {
      case Some(htlc) if alreadyProposed(commitments.localChanges.proposed, htlc.id) =>
        // we have already sent a fail/fulfill for this htlc
        Left(UnknownHtlcId(commitments.channelId, cmd.id))
      case Some(htlc) =>
        // we need the shared secret to build the error packet
        OutgoingPacket.buildHtlcFailure(nodeSecret, cmd, htlc).map(fail => (addLocalProposal(commitments, fail), fail))
      case None => Left(UnknownHtlcId(commitments.channelId, cmd.id))
    }

  def sendFailMalformed(commitments: Commitments, cmd: CMD_FAIL_MALFORMED_HTLC): Either[ChannelException, (Commitments, UpdateFailMalformedHtlc)] = {
    // BADONION bit must be set in failure_code
    if ((cmd.failureCode & FailureMessageCodecs.BADONION) == 0) {
      Left(InvalidFailureCode(commitments.channelId))
    } else {
      commitments.getIncomingHtlcCrossSigned(cmd.id) match {
        case Some(htlc) if alreadyProposed(commitments.localChanges.proposed, htlc.id) =>
          // we have already sent a fail/fulfill for this htlc
          Left(UnknownHtlcId(commitments.channelId, cmd.id))
        case Some(_) =>
          val fail = UpdateFailMalformedHtlc(commitments.channelId, cmd.id, cmd.onionHash, cmd.failureCode)
          val commitments1 = addLocalProposal(commitments, fail)
          Right((commitments1, fail))
        case None => Left(UnknownHtlcId(commitments.channelId, cmd.id))
      }
    }
  }

  def receiveFail(commitments: Commitments, fail: UpdateFailHtlc): Either[ChannelException, (Commitments, Origin, UpdateAddHtlc)] =
    commitments.getOutgoingHtlcCrossSigned(fail.id) match {
      case Some(htlc) => commitments.originChannels.get(fail.id) match {
        case Some(origin) => Right(addRemoteProposal(commitments, fail), origin, htlc)
        case None => Left(UnknownHtlcId(commitments.channelId, fail.id))
      }
      case None => Left(UnknownHtlcId(commitments.channelId, fail.id))
    }

  def receiveFailMalformed(commitments: Commitments, fail: UpdateFailMalformedHtlc): Either[ChannelException, (Commitments, Origin, UpdateAddHtlc)] = {
    // A receiving node MUST fail the channel if the BADONION bit in failure_code is not set for update_fail_malformed_htlc.
    if ((fail.failureCode & FailureMessageCodecs.BADONION) == 0) {
      Left(InvalidFailureCode(commitments.channelId))
    } else {
      commitments.getOutgoingHtlcCrossSigned(fail.id) match {
        case Some(htlc) => commitments.originChannels.get(fail.id) match {
          case Some(origin) => Right(addRemoteProposal(commitments, fail), origin, htlc)
          case None => Left(UnknownHtlcId(commitments.channelId, fail.id))
        }
        case None => Left(UnknownHtlcId(commitments.channelId, fail.id))
      }
    }
  }

  def sendFee(commitments: Commitments, cmd: CMD_UPDATE_FEE): Either[ChannelException, (Commitments, UpdateFee)] = {
    if (!commitments.localParams.isFunder) {
      Left(FundeeCannotSendUpdateFee(commitments.channelId))
    } else {
      // let's compute the current commitment *as seen by them* with this change taken into account
      val fee = UpdateFee(commitments.channelId, cmd.feeratePerKw)
      // update_fee replace each other, so we can remove previous ones
      val commitments1 = commitments.copy(localChanges = commitments.localChanges.copy(proposed = commitments.localChanges.proposed.filterNot(_.isInstanceOf[UpdateFee]) :+ fee))
      val reduced = CommitmentSpec.reduce(commitments1.remoteCommit.spec, commitments1.remoteChanges.acked, commitments1.localChanges.proposed)

      // a node cannot spend pending incoming htlcs, and need to keep funds above the reserve required by the counterparty, after paying the fee
      // we look from remote's point of view, so if local is funder remote doesn't pay the fees
      val fees = commitTxTotalCost(commitments1.remoteParams.dustLimit, reduced, commitments.commitmentFormat)
      val missing = reduced.toRemote.truncateToSatoshi - commitments1.remoteParams.channelReserve - fees
      if (missing < 0.sat) {
        Left(CannotAffordFees(commitments.channelId, missing = -missing, reserve = commitments1.localParams.channelReserve, fees = fees))
      } else {
        Right(commitments1, fee)
      }
    }
  }

  def receiveFee(commitments: Commitments, fee: UpdateFee, feeConf: OnChainFeeConf)(implicit log: LoggingAdapter): Either[ChannelException, Commitments] = {
    if (commitments.localParams.isFunder) {
      Left(FundeeCannotSendUpdateFee(commitments.channelId))
    } else if (fee.feeratePerKw < FeeratePerKw.MinimumFeeratePerKw) {
      Left(FeerateTooSmall(commitments.channelId, remoteFeeratePerKw = fee.feeratePerKw))
    } else {
      Metrics.RemoteFeeratePerKw.withoutTags().record(fee.feeratePerKw.toLong)
      val localFeeratePerKw = feeConf.getCommitmentFeerate(commitments.remoteNodeId, commitments.channelFeatures, commitments.capacity, None)
      log.info("remote feeratePerKw={}, local feeratePerKw={}, ratio={}", fee.feeratePerKw, localFeeratePerKw, fee.feeratePerKw.toLong.toDouble / localFeeratePerKw.toLong)
      if (feeConf.feerateToleranceFor(commitments.remoteNodeId).isFeeDiffTooHigh(commitments.channelFeatures, localFeeratePerKw, fee.feeratePerKw) && commitments.hasPendingOrProposedHtlcs) {
        Left(FeerateTooDifferent(commitments.channelId, localFeeratePerKw = localFeeratePerKw, remoteFeeratePerKw = fee.feeratePerKw))
      } else {
        // NB: we check that the funder can afford this new fee even if spec allows to do it at next signature
        // It is easier to do it here because under certain (race) conditions spec allows a lower-than-normal fee to be paid,
        // and it would be tricky to check if the conditions are met at signing
        // (it also means that we need to check the fee of the initial commitment tx somewhere)

        // let's compute the current commitment *as seen by us* including this change
        // update_fee replace each other, so we can remove previous ones
        val commitments1 = commitments.copy(remoteChanges = commitments.remoteChanges.copy(proposed = commitments.remoteChanges.proposed.filterNot(_.isInstanceOf[UpdateFee]) :+ fee))
        val reduced = CommitmentSpec.reduce(commitments1.localCommit.spec, commitments1.localChanges.acked, commitments1.remoteChanges.proposed)

        // a node cannot spend pending incoming htlcs, and need to keep funds above the reserve required by the counterparty, after paying the fee
        val fees = commitTxTotalCost(commitments1.remoteParams.dustLimit, reduced, commitments.commitmentFormat)
        val missing = reduced.toRemote.truncateToSatoshi - commitments1.localParams.channelReserve - fees
        if (missing < 0.sat) {
          Left(CannotAffordFees(commitments.channelId, missing = -missing, reserve = commitments1.localParams.channelReserve, fees = fees))
        } else {
          Right(commitments1)
        }
      }
    }
  }

  def localHasUnsignedOutgoingHtlcs(commitments: Commitments): Boolean = commitments.localChanges.proposed.collectFirst { case u: UpdateAddHtlc => u }.isDefined

  def remoteHasUnsignedOutgoingHtlcs(commitments: Commitments): Boolean = commitments.remoteChanges.proposed.collectFirst { case u: UpdateAddHtlc => u }.isDefined

  def localHasUnsignedOutgoingUpdateFee(commitments: Commitments): Boolean = commitments.localChanges.proposed.collectFirst { case u: UpdateFee => u }.isDefined

  def remoteHasUnsignedOutgoingUpdateFee(commitments: Commitments): Boolean = commitments.remoteChanges.proposed.collectFirst { case u: UpdateFee => u }.isDefined

  def localHasChanges(commitments: Commitments): Boolean = commitments.remoteChanges.acked.nonEmpty || commitments.localChanges.proposed.nonEmpty

  def remoteHasChanges(commitments: Commitments): Boolean = commitments.localChanges.acked.nonEmpty || commitments.remoteChanges.proposed.nonEmpty

  def revocationPreimage(seed: ByteVector32, index: Long): ByteVector32 = ShaChain.shaChainFromSeed(seed, 0xFFFFFFFFFFFFFFFFL - index)

  def revocationHash(seed: ByteVector32, index: Long): ByteVector32 = Crypto.sha256(revocationPreimage(seed, index))

  def sendCommit(commitments: Commitments, keyManager: ChannelKeyManager)(implicit log: LoggingAdapter): Either[ChannelException, (Commitments, CommitSig)] = {
    import commitments._
    commitments.remoteNextCommitInfo match {
      case Right(_) if !localHasChanges(commitments) =>
        Left(CannotSignWithoutChanges(commitments.channelId))
      case Right(remoteNextPerCommitmentPoint) =>
        // remote commitment will includes all local changes + remote acked changes
        val spec = CommitmentSpec.reduce(remoteCommit.spec, remoteChanges.acked, localChanges.proposed)
        val (remoteCommitTx, htlcTxs) = makeRemoteTxs(keyManager, channelConfig, channelFeatures, remoteCommit.index + 1, localParams, remoteParams, commitInput, remoteNextPerCommitmentPoint, spec)
        val sig = keyManager.sign(remoteCommitTx, keyManager.fundingPublicKey(commitments.localParams.fundingKeyPath), TxOwner.Remote, commitmentFormat)

        val sortedHtlcTxs: Seq[TransactionWithInputInfo] = htlcTxs.sortBy(_.input.outPoint.index)
        val channelKeyPath = keyManager.keyPath(commitments.localParams, channelConfig)
        val htlcSigs = sortedHtlcTxs.map(keyManager.sign(_, keyManager.htlcPoint(channelKeyPath), remoteNextPerCommitmentPoint, TxOwner.Remote, commitmentFormat))

        // NB: IN/OUT htlcs are inverted because this is the remote commit
        log.info(s"built remote commit number=${remoteCommit.index + 1} toLocalMsat=${spec.toLocal.toLong} toRemoteMsat=${spec.toRemote.toLong} htlc_in={} htlc_out={} feeratePerKw=${spec.feeratePerKw} txid=${remoteCommitTx.tx.txid} tx={}", spec.htlcs.collect(outgoing).map(_.id).mkString(","), spec.htlcs.collect(incoming).map(_.id).mkString(","), remoteCommitTx.tx)
        Metrics.recordHtlcsInFlight(spec, remoteCommit.spec)

        val commitSig = CommitSig(
          channelId = commitments.channelId,
          signature = sig,
          htlcSignatures = htlcSigs.toList)
        val commitments1 = commitments.copy(
          remoteNextCommitInfo = Left(WaitingForRevocation(RemoteCommit(remoteCommit.index + 1, spec, remoteCommitTx.tx.txid, remoteNextPerCommitmentPoint), commitSig, commitments.localCommit.index)),
          localChanges = localChanges.copy(proposed = Nil, signed = localChanges.proposed),
          remoteChanges = remoteChanges.copy(acked = Nil, signed = remoteChanges.acked))
        Right(commitments1, commitSig)
      case Left(_) =>
        Left(CannotSignBeforeRevocation(commitments.channelId))
    }
  }

  def receiveCommit(commitments: Commitments, commit: CommitSig, keyManager: ChannelKeyManager)(implicit log: LoggingAdapter): Either[ChannelException, (Commitments, RevokeAndAck)] = {
    import commitments._
    // they sent us a signature for *their* view of *our* next commit tx
    // so in terms of rev.hashes and indexes we have:
    // ourCommit.index -> our current revocation hash, which is about to become our old revocation hash
    // ourCommit.index + 1 -> our next revocation hash, used by *them* to build the sig we've just received, and which
    // is about to become our current revocation hash
    // ourCommit.index + 2 -> which is about to become our next revocation hash
    // we will reply to this sig with our old revocation hash preimage (at index) and our next revocation hash (at index + 1)
    // and will increment our index

    // lnd sometimes sends a new signature without any changes, which is a (harmless) spec violation
    if (!remoteHasChanges(commitments)) {
      //  throw CannotSignWithoutChanges(commitments.channelId)
      log.warning("received a commit sig with no changes (probably coming from lnd)")
    }

    val spec = CommitmentSpec.reduce(localCommit.spec, localChanges.acked, remoteChanges.proposed)
    val channelKeyPath = keyManager.keyPath(localParams, channelConfig)
    val localPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, commitments.localCommit.index + 1)
    val (localCommitTx, htlcTxs) = makeLocalTxs(keyManager, channelConfig, channelFeatures, localCommit.index + 1, localParams, remoteParams, commitInput, localPerCommitmentPoint, spec)

    log.info(s"built local commit number=${localCommit.index + 1} toLocalMsat=${spec.toLocal.toLong} toRemoteMsat=${spec.toRemote.toLong} htlc_in={} htlc_out={} feeratePerKw=${spec.feeratePerKw} txid=${localCommitTx.tx.txid} tx={}", spec.htlcs.collect(incoming).map(_.id).mkString(","), spec.htlcs.collect(outgoing).map(_.id).mkString(","), localCommitTx.tx)

    if (!Transactions.checkSig(localCommitTx, commit.signature, remoteParams.fundingPubKey, TxOwner.Remote, commitmentFormat)) {
      return Left(InvalidCommitmentSignature(commitments.channelId, localCommitTx.tx))
    }

    val sortedHtlcTxs: Seq[HtlcTx] = htlcTxs.sortBy(_.input.outPoint.index)
    if (commit.htlcSignatures.size != sortedHtlcTxs.size) {
      return Left(HtlcSigCountMismatch(commitments.channelId, sortedHtlcTxs.size, commit.htlcSignatures.size))
    }

    val remoteHtlcPubkey = Generators.derivePubKey(remoteParams.htlcBasepoint, localPerCommitmentPoint)
    val htlcTxsAndRemoteSigs = sortedHtlcTxs.zip(commit.htlcSignatures).toList.map {
      case (htlcTx: HtlcTx, remoteSig) =>
        if (!Transactions.checkSig(htlcTx, remoteSig, remoteHtlcPubkey, TxOwner.Remote, commitmentFormat)) {
          return Left(InvalidHtlcSignature(commitments.channelId, htlcTx.tx))
        }
        HtlcTxAndRemoteSig(htlcTx, remoteSig)
    }

    // we will send our revocation preimage + our next revocation hash
    val localPerCommitmentSecret = keyManager.commitmentSecret(channelKeyPath, commitments.localCommit.index)
    val localNextPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, commitments.localCommit.index + 2)
    val revocation = RevokeAndAck(
      channelId = commitments.channelId,
      perCommitmentSecret = localPerCommitmentSecret,
      nextPerCommitmentPoint = localNextPerCommitmentPoint
    )

    // update our commitment data
    val localCommit1 = LocalCommit(
      index = localCommit.index + 1,
      spec,
      commitTxAndRemoteSig = CommitTxAndRemoteSig(localCommitTx, commit.signature),
      htlcTxsAndRemoteSigs = htlcTxsAndRemoteSigs)
    val ourChanges1 = localChanges.copy(acked = Nil)
    val theirChanges1 = remoteChanges.copy(proposed = Nil, acked = remoteChanges.acked ++ remoteChanges.proposed)
    val commitments1 = commitments.copy(localCommit = localCommit1, localChanges = ourChanges1, remoteChanges = theirChanges1)

    Right(commitments1, revocation)
  }

  def receiveRevocation(commitments: Commitments, revocation: RevokeAndAck): Either[ChannelException, (Commitments, Seq[Either[RES_ADD_SETTLED[Origin, HtlcResult], Relayer.RelayForward]])] = {
    import commitments._
    // we receive a revocation because we just sent them a sig for their next commit tx
    remoteNextCommitInfo match {
      case Left(_) if revocation.perCommitmentSecret.publicKey != remoteCommit.remotePerCommitmentPoint =>
        Left(InvalidRevocation(commitments.channelId))
      case Left(WaitingForRevocation(theirNextCommit, _, _, _)) =>
        val forwards = commitments.remoteChanges.signed collect {
          // we forward adds downstream only when they have been committed by both sides
          // it always happen when we receive a revocation, because they send the add, then they sign it, then we sign it
          case add: UpdateAddHtlc => Right(Relayer.RelayForward(add))
          // same for fails: we need to make sure that they are in neither commitment before propagating the fail upstream
          case fail: UpdateFailHtlc =>
            val origin = commitments.originChannels(fail.id)
            val add = commitments.remoteCommit.spec.findIncomingHtlcById(fail.id).map(_.add).get
            Left(RES_ADD_SETTLED(origin, add, HtlcResult.RemoteFail(fail)))
          // same as above
          case fail: UpdateFailMalformedHtlc =>
            val origin = commitments.originChannels(fail.id)
            val add = commitments.remoteCommit.spec.findIncomingHtlcById(fail.id).map(_.add).get
            Left(RES_ADD_SETTLED(origin, add, HtlcResult.RemoteFailMalformed(fail)))
        }
        // the outgoing following htlcs have been completed (fulfilled or failed) when we received this revocation
        // they have been removed from both local and remote commitment
        // (since fulfill/fail are sent by remote, they are (1) signed by them, (2) revoked by us, (3) signed by us, (4) revoked by them
        val completedOutgoingHtlcs = commitments.remoteCommit.spec.htlcs.collect(incoming).map(_.id) -- theirNextCommit.spec.htlcs.collect(incoming).map(_.id)
        // we remove the newly completed htlcs from the origin map
        val originChannels1 = commitments.originChannels -- completedOutgoingHtlcs
        val commitments1 = commitments.copy(
          localChanges = localChanges.copy(signed = Nil, acked = localChanges.acked ++ localChanges.signed),
          remoteChanges = remoteChanges.copy(signed = Nil),
          remoteCommit = theirNextCommit,
          remoteNextCommitInfo = Right(revocation.nextPerCommitmentPoint),
          remotePerCommitmentSecrets = commitments.remotePerCommitmentSecrets.addHash(revocation.perCommitmentSecret.value, 0xFFFFFFFFFFFFL - commitments.remoteCommit.index),
          originChannels = originChannels1)
        Right(commitments1, forwards)
      case Right(_) =>
        Left(UnexpectedRevocation(commitments.channelId))
    }
  }

  def makeLocalTxs(keyManager: ChannelKeyManager,
                   channelConfig: ChannelConfig,
                   channelFeatures: ChannelFeatures,
                   commitTxNumber: Long,
                   localParams: LocalParams,
                   remoteParams: RemoteParams,
                   commitmentInput: InputInfo,
                   localPerCommitmentPoint: PublicKey,
                   spec: CommitmentSpec): (CommitTx, Seq[HtlcTx]) = {
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
    val outputs = makeCommitTxOutputs(localParams.isFunder, localParams.dustLimit, localRevocationPubkey, remoteParams.toSelfDelay, localDelayedPaymentPubkey, remotePaymentPubkey, localHtlcPubkey, remoteHtlcPubkey, localFundingPubkey, remoteParams.fundingPubKey, spec, channelFeatures.commitmentFormat)
    val commitTx = Transactions.makeCommitTx(commitmentInput, commitTxNumber, localPaymentBasepoint, remoteParams.paymentBasepoint, localParams.isFunder, outputs)
    val htlcTxs = Transactions.makeHtlcTxs(commitTx.tx, localParams.dustLimit, localRevocationPubkey, remoteParams.toSelfDelay, localDelayedPaymentPubkey, spec.feeratePerKw, outputs, channelFeatures.commitmentFormat)
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
                    spec: CommitmentSpec): (CommitTx, Seq[HtlcTx]) = {
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
    val outputs = makeCommitTxOutputs(!localParams.isFunder, remoteParams.dustLimit, remoteRevocationPubkey, localParams.toSelfDelay, remoteDelayedPaymentPubkey, localPaymentPubkey, remoteHtlcPubkey, localHtlcPubkey, remoteParams.fundingPubKey, localFundingPubkey, spec, channelFeatures.commitmentFormat)
    val commitTx = Transactions.makeCommitTx(commitmentInput, commitTxNumber, remoteParams.paymentBasepoint, localPaymentBasepoint, !localParams.isFunder, outputs)
    val htlcTxs = Transactions.makeHtlcTxs(commitTx.tx, remoteParams.dustLimit, remoteRevocationPubkey, localParams.toSelfDelay, remoteDelayedPaymentPubkey, spec.feeratePerKw, outputs, channelFeatures.commitmentFormat)
    (commitTx, htlcTxs)
  }

  def msg2String(msg: LightningMessage): String = msg match {
    case u: UpdateAddHtlc => s"add-${u.id}"
    case u: UpdateFulfillHtlc => s"ful-${u.id}"
    case u: UpdateFailHtlc => s"fail-${u.id}"
    case _: UpdateFee => s"fee"
    case _: CommitSig => s"sig"
    case _: RevokeAndAck => s"rev"
    case _: Error => s"err"
    case _: FundingLocked => s"funding_locked"
    case _ => "???"
  }

  def changes2String(commitments: Commitments): String = {
    import commitments._
    s"""commitments:
       |    localChanges:
       |        proposed: ${localChanges.proposed.map(msg2String(_)).mkString(" ")}
       |        signed: ${localChanges.signed.map(msg2String(_)).mkString(" ")}
       |        acked: ${localChanges.acked.map(msg2String(_)).mkString(" ")}
       |    remoteChanges:
       |        proposed: ${remoteChanges.proposed.map(msg2String(_)).mkString(" ")}
       |        acked: ${remoteChanges.acked.map(msg2String(_)).mkString(" ")}
       |        signed: ${remoteChanges.signed.map(msg2String(_)).mkString(" ")}
       |    nextHtlcId:
       |        local: $localNextHtlcId
       |        remote: $remoteNextHtlcId""".stripMargin
  }

  def specs2String(commitments: Commitments): String = {
    s"""specs:
       |localcommit:
       |  toLocal: ${commitments.localCommit.spec.toLocal}
       |  toRemote: ${commitments.localCommit.spec.toRemote}
       |  htlcs:
       |${commitments.localCommit.spec.htlcs.map(h => s"    ${h.direction} ${h.add.id} ${h.add.cltvExpiry}").mkString("\n")}
       |remotecommit:
       |  toLocal: ${commitments.remoteCommit.spec.toLocal}
       |  toRemote: ${commitments.remoteCommit.spec.toRemote}
       |  htlcs:
       |${commitments.remoteCommit.spec.htlcs.map(h => s"    ${h.direction} ${h.add.id} ${h.add.cltvExpiry}").mkString("\n")}
       |next remotecommit:
       |  toLocal: ${commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.spec.toLocal).getOrElse("N/A")}
       |  toRemote: ${commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.spec.toRemote).getOrElse("N/A")}
       |  htlcs:
       |${commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.spec.htlcs.map(h => s"    ${h.direction} ${h.add.id} ${h.add.cltvExpiry}").mkString("\n")).getOrElse("N/A")}""".stripMargin
  }

}
