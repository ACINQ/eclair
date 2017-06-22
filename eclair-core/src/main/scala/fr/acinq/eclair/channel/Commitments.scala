package fr.acinq.eclair.channel

import fr.acinq.bitcoin.Crypto.{Point, PrivateKey, sha256}
import fr.acinq.bitcoin.{BinaryData, Crypto, Satoshi, Transaction}
import fr.acinq.eclair.Globals
import fr.acinq.eclair.crypto.{Generators, ShaChain, Sphinx}
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire._
import grizzled.slf4j.Logging

// @formatter:off
case class LocalChanges(proposed: List[UpdateMessage], signed: List[UpdateMessage], acked: List[UpdateMessage]) {
  def all: List[UpdateMessage] = proposed ++ signed ++ acked
}
case class RemoteChanges(proposed: List[UpdateMessage], acked: List[UpdateMessage], signed: List[UpdateMessage])
case class Changes(ourChanges: LocalChanges, theirChanges: RemoteChanges)
case class HtlcTxAndSigs(txinfo: TransactionWithInputInfo, localSig: BinaryData, remoteSig: BinaryData)
case class PublishableTxs(commitTx: CommitTx, htlcTxsAndSigs: List[HtlcTxAndSigs])
case class LocalCommit(index: Long, spec: CommitmentSpec, publishableTxs: PublishableTxs)
case class RemoteCommit(index: Long, spec: CommitmentSpec, txid: BinaryData, remotePerCommitmentPoint: Point)
case class WaitingForRevocation(nextRemoteCommit: RemoteCommit, sent: CommitSig, reSignAsap: Boolean = false)
// @formatter:on

/**
  * about remoteNextCommitInfo:
  * we either:
  * - have built and signed their next commit tx with their next revocation hash which can now be discarded
  * - have their next per-commitment point
  * So, when we've signed and sent a commit message and are waiting for their revocation message,
  * theirNextCommitInfo is their next commit tx. The rest of the time, it is their next per-commitment point
  */
case class Commitments(localParams: LocalParams, remoteParams: RemoteParams,
                       localCommit: LocalCommit, remoteCommit: RemoteCommit,
                       localChanges: LocalChanges, remoteChanges: RemoteChanges,
                       localNextHtlcId: Long, remoteNextHtlcId: Long,
                       remoteNextCommitInfo: Either[WaitingForRevocation, Point],
                       commitInput: InputInfo,
                       remotePerCommitmentSecrets: ShaChain, channelId: BinaryData) {

  def hasNoPendingHtlcs: Boolean = localCommit.spec.htlcs.isEmpty && remoteCommit.spec.htlcs.isEmpty

  def hasTimedoutOutgoingHtlcs(blockheight: Long): Boolean =
    localCommit.spec.htlcs.exists(htlc => htlc.direction == OUT && blockheight >= htlc.add.expiry) ||
      remoteCommit.spec.htlcs.exists(htlc => htlc.direction == IN && blockheight >= htlc.add.expiry)

  def addLocalProposal(proposal: UpdateMessage): Commitments = Commitments.addLocalProposal(this, proposal)

  def addRemoteProposal(proposal: UpdateMessage): Commitments = Commitments.addRemoteProposal(this, proposal)

  //def addToUnackedMessages(message: LightningMessage): Commitments = this.copy(unackedMessages = unackedMessages :+ message)

  //def unackedShutdown(): Option[Shutdown] = this.unackedMessages.collectFirst { case d: Shutdown => d }
}

object Commitments extends Logging {
  /**
    * add a change to our proposed change list
    *
    * @param commitments
    * @param proposal
    * @return an updated commitment instance
    */
  private def addLocalProposal(commitments: Commitments, proposal: UpdateMessage): Commitments =
    commitments.copy(localChanges = commitments.localChanges.copy(proposed = commitments.localChanges.proposed :+ proposal))

  private def addRemoteProposal(commitments: Commitments, proposal: UpdateMessage): Commitments =
    commitments.copy(remoteChanges = commitments.remoteChanges.copy(proposed = commitments.remoteChanges.proposed :+ proposal))

  /**
    *
    * @param commitments current commitments
    * @param cmd         add HTLC command
    * @return either Left(failure, error message) where failure is a failure message (see BOLT #4 and the Failure Message class) or Right((new commitments, updateAddHtlc)
    */
  def sendAdd(commitments: Commitments, cmd: CMD_ADD_HTLC): Either[ChannelException, (Commitments, UpdateAddHtlc)] = {

    if (cmd.paymentHash.size != 32) {
      return Left(InvalidPaymentHash)
    }

    val blockCount = Globals.blockCount.get()
    if (cmd.expiry <= blockCount) {
      return Left(ExpiryCannotBeInThePast(cmd.expiry, blockCount))
    }

    if (cmd.amountMsat < commitments.remoteParams.htlcMinimumMsat) {
      return Left(HtlcValueTooSmall(minimum = commitments.remoteParams.htlcMinimumMsat, actual = cmd.amountMsat))
    }

    // let's compute the current commitment *as seen by them* with this change taken into account
    val add = UpdateAddHtlc(commitments.channelId, commitments.localNextHtlcId, cmd.amountMsat, cmd.expiry, cmd.paymentHash, cmd.onion)
    val commitments1 = addLocalProposal(commitments, add).copy(localNextHtlcId = commitments.localNextHtlcId + 1)
    val reduced = CommitmentSpec.reduce(commitments1.remoteCommit.spec, commitments1.remoteChanges.acked, commitments1.localChanges.proposed)

    val htlcValueInFlight = reduced.htlcs.map(_.add.amountMsat).sum
    if (htlcValueInFlight > commitments1.remoteParams.maxHtlcValueInFlightMsat) {
      // TODO: this should be a specific UPDATE error
      return Left(HtlcValueTooHighInFlight(maximum = commitments1.remoteParams.maxHtlcValueInFlightMsat, actual = htlcValueInFlight))
    }

    // the HTLC we are about to create is outgoing, but from their point of view it is incoming
    val acceptedHtlcs = reduced.htlcs.count(_.direction == IN)
    if (acceptedHtlcs > commitments1.remoteParams.maxAcceptedHtlcs) {
      return Left(TooManyAcceptedHtlcs(maximum = commitments1.remoteParams.maxAcceptedHtlcs))
    }

    // a node cannot spend pending incoming htlcs, and need to keep funds above the reserve required by the counterparty, after paying the fee
    // we look from remote's point of view, so if local is funder remote doesn't pay the fees
    val fees = if (commitments1.localParams.isFunder) Transactions.commitTxFee(Satoshi(commitments1.remoteParams.dustLimitSatoshis), reduced).amount else 0
    val missing = reduced.toRemoteMsat / 1000 - commitments1.remoteParams.channelReserveSatoshis - fees
    if (missing < 0) {
      return Left(InsufficientFunds(amountMsat = cmd.amountMsat, missingSatoshis = -1 * missing, reserveSatoshis = commitments1.remoteParams.channelReserveSatoshis, feesSatoshis = fees))
    }

    Right(commitments1, add)
  }

  def receiveAdd(commitments: Commitments, add: UpdateAddHtlc): Commitments = {
    if (add.id != commitments.remoteNextHtlcId) {
      throw UnexpectedHtlcId(expected = commitments.remoteNextHtlcId, actual = add.id)
    }

    if (add.paymentHash.size != 32) {
      throw InvalidPaymentHash
    }

    val blockCount = Globals.blockCount.get()
    // we need a reasonable amount of time to pull the funds before the sender can get refunded
    val minExpiry = blockCount + 3
    if (add.expiry < minExpiry) {
      throw ExpiryTooSmall(minimum = minExpiry, actual = add.expiry, blockCount = blockCount)
    }

    if (add.amountMsat < commitments.localParams.htlcMinimumMsat) {
      throw HtlcValueTooSmall(minimum = commitments.localParams.htlcMinimumMsat, actual = add.amountMsat)
    }

    // let's compute the current commitment *as seen by us* including this change
    val commitments1 = addRemoteProposal(commitments, add).copy(remoteNextHtlcId = commitments.remoteNextHtlcId + 1)
    val reduced = CommitmentSpec.reduce(commitments1.localCommit.spec, commitments1.localChanges.acked, commitments1.remoteChanges.proposed)

    val htlcValueInFlight = reduced.htlcs.map(_.add.amountMsat).sum
    if (htlcValueInFlight > commitments1.localParams.maxHtlcValueInFlightMsat) {
      throw HtlcValueTooHighInFlight(maximum = commitments1.localParams.maxHtlcValueInFlightMsat, actual = htlcValueInFlight)
    }

    val acceptedHtlcs = reduced.htlcs.count(_.direction == IN)
    if (acceptedHtlcs > commitments1.localParams.maxAcceptedHtlcs) {
      throw TooManyAcceptedHtlcs(maximum = commitments1.localParams.maxAcceptedHtlcs)
    }

    // a node cannot spend pending incoming htlcs, and need to keep funds above the reserve required by the counterparty, after paying the fee
    val fees = if (commitments1.localParams.isFunder) 0 else Transactions.commitTxFee(Satoshi(commitments1.localParams.dustLimitSatoshis), reduced).amount
    val missing = reduced.toRemoteMsat / 1000 - commitments1.localParams.channelReserveSatoshis - fees
    if (missing < 0) {
      throw InsufficientFunds(amountMsat = add.amountMsat, missingSatoshis = -1 * missing, reserveSatoshis = commitments1.localParams.channelReserveSatoshis, feesSatoshis = fees)
    }

    commitments1
  }

  def getHtlcCrossSigned(commitments: Commitments, directionRelativeToLocal: Direction, htlcId: Long): Option[UpdateAddHtlc] = {
    val remoteSigned = commitments.localCommit.spec.htlcs.find(htlc => htlc.direction == directionRelativeToLocal && htlc.add.id == htlcId)
    val localSigned = commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit).getOrElse(commitments.remoteCommit)
      .spec.htlcs.find(htlc => htlc.direction == directionRelativeToLocal.opposite && htlc.add.id == htlcId)
    for {
      htlc_out <- remoteSigned
      htlc_in <- localSigned
    } yield htlc_in.add
  }

  def sendFulfill(commitments: Commitments, cmd: CMD_FULFILL_HTLC): (Commitments, UpdateFulfillHtlc) =
    getHtlcCrossSigned(commitments, IN, cmd.id) match {
      case Some(htlc) if commitments.localChanges.proposed.exists {
        case u: UpdateFulfillHtlc if htlc.id == u.id => true
        case u: UpdateFailHtlc if htlc.id == u.id => true
        case u: UpdateFailMalformedHtlc if htlc.id == u.id => true
        case _ => false
      } =>
        // we have already sent a fail/fulfill for this htlc
        throw UnknownHtlcId(cmd.id)
      case Some(htlc) if htlc.paymentHash == sha256(cmd.r) =>
        val fulfill = UpdateFulfillHtlc(commitments.channelId, cmd.id, cmd.r)
        val commitments1 = addLocalProposal(commitments, fulfill)
        (commitments1, fulfill)
      case Some(htlc) => throw InvalidHtlcPreimage(cmd.id)
      case None => throw UnknownHtlcId(cmd.id)
    }

  def receiveFulfill(commitments: Commitments, fulfill: UpdateFulfillHtlc): Either[Commitments, Commitments] =
    getHtlcCrossSigned(commitments, OUT, fulfill.id) match {
      case Some(htlc) if htlc.paymentHash == sha256(fulfill.paymentPreimage) => Right(addRemoteProposal(commitments, fulfill))
      case Some(htlc) => throw InvalidHtlcPreimage(fulfill.id)
      case None => throw UnknownHtlcId(fulfill.id)
    }

  def sendFail(commitments: Commitments, cmd: CMD_FAIL_HTLC, nodeSecret: PrivateKey): (Commitments, UpdateFailHtlc) =
    getHtlcCrossSigned(commitments, IN, cmd.id) match {
      case Some(htlc) if commitments.localChanges.proposed.exists {
        case u: UpdateFulfillHtlc if htlc.id == u.id => true
        case u: UpdateFailHtlc if htlc.id == u.id => true
        case u: UpdateFailMalformedHtlc if htlc.id == u.id => true
        case _ => false
      } =>
        // we have already sent a fail/fulfill for this htlc
        throw UnknownHtlcId(cmd.id)
      case Some(htlc) =>
        // we need the shared secret to build the error packet
        val sharedSecret = Sphinx.parsePacket(nodeSecret, htlc.paymentHash, htlc.onionRoutingPacket).sharedSecret
        val reason = cmd.reason match {
          case Left(forwarded) => Sphinx.forwardErrorPacket(forwarded, sharedSecret)
          case Right(failure) => Sphinx.createErrorPacket(sharedSecret, failure)
        }
        val fail = UpdateFailHtlc(commitments.channelId, cmd.id, reason)
        val commitments1 = addLocalProposal(commitments, fail)
        (commitments1, fail)
      case None => throw UnknownHtlcId(cmd.id)
    }

  def sendFailMalformed(commitments: Commitments, cmd: CMD_FAIL_MALFORMED_HTLC): (Commitments, UpdateFailMalformedHtlc) =
    getHtlcCrossSigned(commitments, IN, cmd.id) match {
      case Some(htlc) if commitments.localChanges.proposed.exists {
        case u: UpdateFulfillHtlc if htlc.id == u.id => true
        case u: UpdateFailHtlc if htlc.id == u.id => true
        case u: UpdateFailMalformedHtlc if htlc.id == u.id => true
        case _ => false
      } =>
        // we have already sent a fail/fulfill for this htlc
        throw UnknownHtlcId(cmd.id)
      case Some(htlc) =>
        val fail = UpdateFailMalformedHtlc(commitments.channelId, cmd.id, cmd.onionHash, cmd.failureCode)
        val commitments1 = addLocalProposal(commitments, fail)
        (commitments1, fail)
      case None => throw UnknownHtlcId(cmd.id)
    }

  def receiveFail(commitments: Commitments, fail: UpdateFailHtlc): Either[Commitments, Commitments] =
    getHtlcCrossSigned(commitments, OUT, fail.id) match {
      case Some(htlc) => Right(addRemoteProposal(commitments, fail))
      case None => throw UnknownHtlcId(fail.id)
    }

  def receiveFailMalformed(commitments: Commitments, fail: UpdateFailMalformedHtlc): Either[Commitments, Commitments] =
    getHtlcCrossSigned(commitments, OUT, fail.id) match {
      case Some(htlc) => Right(addRemoteProposal(commitments, fail))
      case None => throw UnknownHtlcId(fail.id)
    }

  def sendFee(commitments: Commitments, cmd: CMD_UPDATE_FEE): (Commitments, UpdateFee) = {
    if (!commitments.localParams.isFunder) {
      throw FundeeCannotSendUpdateFee
    }
    // let's compute the current commitment *as seen by them* with this change taken into account
    val fee = UpdateFee(commitments.channelId, cmd.feeratePerKw)
    val commitments1 = addLocalProposal(commitments, fee)
    val reduced = CommitmentSpec.reduce(commitments1.remoteCommit.spec, commitments1.remoteChanges.acked, commitments1.localChanges.proposed)

    // a node cannot spend pending incoming htlcs, and need to keep funds above the reserve required by the counterparty, after paying the fee
    // we look from remote's point of view, so if local is funder remote doesn't pay the fees
    val fees = Transactions.commitTxFee(Satoshi(commitments1.remoteParams.dustLimitSatoshis), reduced).amount
    val missing = reduced.toRemoteMsat / 1000 - commitments1.remoteParams.channelReserveSatoshis - fees
    if (missing < 0) {
      throw CannotAffordFees(missingSatoshis = -1 * missing, reserveSatoshis = commitments1.localParams.channelReserveSatoshis, feesSatoshis = fees)
    }

    (commitments1, fee)
  }

  def receiveFee(commitments: Commitments, fee: UpdateFee, maxFeerateMismatch: Double): Commitments = {
    if (commitments.localParams.isFunder) {
      throw FundeeCannotSendUpdateFee
    }

    val localFeeratePerKw = Globals.feeratePerKw.get()
    if (Helpers.isFeeDiffTooHigh(fee.feeratePerKw, localFeeratePerKw, maxFeerateMismatch)) {
      throw FeerateTooDifferent(localFeeratePerKw = localFeeratePerKw, remoteFeeratePerKw = fee.feeratePerKw)
    }

    // NB: we check that the funder can afford this new fee even if spec allows to do it at next signature
    // It is easier to do it here because under certain (race) conditions spec allows a lower-than-normal fee to be paid,
    // and it would be tricky to check if the conditions are met at signing
    // (it also means that we need to check the fee of the initial commitment tx somewhere)

    // let's compute the current commitment *as seen by us* including this change
    val commitments1 = addRemoteProposal(commitments, fee)
    val reduced = CommitmentSpec.reduce(commitments1.localCommit.spec, commitments1.localChanges.acked, commitments1.remoteChanges.proposed)

    // a node cannot spend pending incoming htlcs, and need to keep funds above the reserve required by the counterparty, after paying the fee
    val fees = Transactions.commitTxFee(Satoshi(commitments1.remoteParams.dustLimitSatoshis), reduced).amount
    val missing = reduced.toRemoteMsat / 1000 - commitments1.localParams.channelReserveSatoshis - fees
    if (missing < 0) {
      throw CannotAffordFees(missingSatoshis = -1 * missing, reserveSatoshis = commitments1.localParams.channelReserveSatoshis, feesSatoshis = fees)
    }

    commitments1
  }

  def localHasChanges(commitments: Commitments): Boolean = commitments.remoteChanges.acked.size > 0 || commitments.localChanges.proposed.size > 0

  def remoteHasChanges(commitments: Commitments): Boolean = commitments.localChanges.acked.size > 0 || commitments.remoteChanges.proposed.size > 0

  def revocationPreimage(seed: BinaryData, index: Long): BinaryData = ShaChain.shaChainFromSeed(seed, 0xFFFFFFFFFFFFFFFFL - index)

  def revocationHash(seed: BinaryData, index: Long): BinaryData = Crypto.sha256(revocationPreimage(seed, index))

  def sendCommit(commitments: Commitments): (Commitments, CommitSig) = {
    import commitments._
    commitments.remoteNextCommitInfo match {
      case Right(_) if !localHasChanges(commitments) =>
        throw CannotSignWithoutChanges
      case Right(remoteNextPerCommitmentPoint) =>
        // remote commitment will includes all local changes + remote acked changes
        val spec = CommitmentSpec.reduce(remoteCommit.spec, remoteChanges.acked, localChanges.proposed)
        val (remoteCommitTx, htlcTimeoutTxs, htlcSuccessTxs) = makeRemoteTxs(remoteCommit.index + 1, localParams, remoteParams, commitInput, remoteNextPerCommitmentPoint, spec)
        val sig = Transactions.sign(remoteCommitTx, localParams.fundingPrivKey)

        val sortedHtlcTxs: Seq[TransactionWithInputInfo] = (htlcTimeoutTxs ++ htlcSuccessTxs).sortBy(_.input.outPoint.index)
        val paymentKey = Generators.derivePrivKey(localParams.paymentKey, remoteNextPerCommitmentPoint)
        val htlcSigs = sortedHtlcTxs.map(Transactions.sign(_, paymentKey))

        // don't sign if they don't get paid
        val commitSig = CommitSig(
          channelId = commitments.channelId,
          signature = sig,
          htlcSignatures = htlcSigs.toList
        )

        val commitments1 = commitments.copy(
          remoteNextCommitInfo = Left(WaitingForRevocation(RemoteCommit(remoteCommit.index + 1, spec, remoteCommitTx.tx.txid, remoteNextPerCommitmentPoint), commitSig)),
          localChanges = localChanges.copy(proposed = Nil, signed = localChanges.proposed),
          remoteChanges = remoteChanges.copy(acked = Nil, signed = remoteChanges.acked))
        (commitments1, commitSig)
      case Left(_) =>
        throw CannotSignBeforeRevocation
    }
  }

  def receiveCommit(commitments: Commitments, commit: CommitSig): (Commitments, RevokeAndAck) = {
    import commitments._
    // they sent us a signature for *their* view of *our* next commit tx
    // so in terms of rev.hashes and indexes we have:
    // ourCommit.index -> our current revocation hash, which is about to become our old revocation hash
    // ourCommit.index + 1 -> our next revocation hash, used by * them * to build the sig we've just received, and which
    // is about to become our current revocation hash
    // ourCommit.index + 2 -> which is about to become our next revocation hash
    // we will reply to this sig with our old revocation hash preimage (at index) and our next revocation hash (at index + 1)
    // and will increment our index

    if (!remoteHasChanges(commitments))
      throw CannotSignWithoutChanges

    // check that their signature is valid
    // signatures are now optional in the commit message, and will be sent only if the other party is actually
    // receiving money i.e its commit tx has one output for them

    val spec = CommitmentSpec.reduce(localCommit.spec, localChanges.acked, remoteChanges.proposed)
    val localPerCommitmentPoint = Generators.perCommitPoint(localParams.shaSeed, commitments.localCommit.index + 1)
    val (localCommitTx, htlcTimeoutTxs, htlcSuccessTxs) = makeLocalTxs(localCommit.index + 1, localParams, remoteParams, commitInput, localPerCommitmentPoint, spec)
    val sig = Transactions.sign(localCommitTx, localParams.fundingPrivKey)

    // TODO: should we have optional sig? (original comment: this tx will NOT be signed if our output is empty)

    // no need to compute htlc sigs if commit sig doesn't check out
    val signedCommitTx = Transactions.addSigs(localCommitTx, localParams.fundingPrivKey.publicKey, remoteParams.fundingPubKey, sig, commit.signature)
    if (Transactions.checkSpendable(signedCommitTx).isFailure) {
      throw InvalidCommitmentSignature
    }

    val sortedHtlcTxs: Seq[TransactionWithInputInfo] = (htlcTimeoutTxs ++ htlcSuccessTxs).sortBy(_.input.outPoint.index)
    require(commit.htlcSignatures.size == sortedHtlcTxs.size, s"htlc sig count mismatch (received=${commit.htlcSignatures.size}, expected=${sortedHtlcTxs.size})")
    val localPaymentKey = Generators.derivePrivKey(localParams.paymentKey, localPerCommitmentPoint)
    val htlcSigs = sortedHtlcTxs.map(Transactions.sign(_, localPaymentKey))
    val remotePaymentPubkey = Generators.derivePubKey(remoteParams.paymentBasepoint, localPerCommitmentPoint)
    // combine the sigs to make signed txes
    val htlcTxsAndSigs = (sortedHtlcTxs, htlcSigs, commit.htlcSignatures).zipped.toList.collect {
      case (htlcTx: HtlcTimeoutTx, localSig, remoteSig) =>
        require(Transactions.checkSpendable(Transactions.addSigs(htlcTx, localSig, remoteSig)).isSuccess, "bad sig")
        HtlcTxAndSigs(htlcTx, localSig, remoteSig)
      case (htlcTx: HtlcSuccessTx, localSig, remoteSig) =>
        // we can't check that htlc-success tx are spendable because we need the payment preimage; thus we only check the remote sig
        require(Transactions.checkSig(htlcTx, remoteSig, remotePaymentPubkey), "bad sig")
        HtlcTxAndSigs(htlcTx, localSig, remoteSig)
    }

    // we will send our revocation preimage + our next revocation hash
    val localPerCommitmentSecret = Generators.perCommitSecret(localParams.shaSeed, commitments.localCommit.index)
    val localNextPerCommitmentPoint = Generators.perCommitPoint(localParams.shaSeed, commitments.localCommit.index + 2)
    val revocation = RevokeAndAck(
      channelId = commitments.channelId,
      perCommitmentSecret = localPerCommitmentSecret,
      nextPerCommitmentPoint = localNextPerCommitmentPoint
    )

    // update our commitment data
    val ourCommit1 = LocalCommit(
      index = localCommit.index + 1,
      spec,
      publishableTxs = PublishableTxs(signedCommitTx, htlcTxsAndSigs))
    val ourChanges1 = localChanges.copy(acked = Nil)
    val theirChanges1 = remoteChanges.copy(proposed = Nil, acked = remoteChanges.acked ++ remoteChanges.proposed)
    val commitments1 = commitments.copy(localCommit = ourCommit1, localChanges = ourChanges1, remoteChanges = theirChanges1)

    logger.debug(s"current commit: index=${ourCommit1.index} htlc_in=${ourCommit1.spec.htlcs.filter(_.direction == IN).size} htlc_out=${ourCommit1.spec.htlcs.filter(_.direction == OUT).size} txid=${ourCommit1.publishableTxs.commitTx.tx.txid} tx=${Transaction.write(ourCommit1.publishableTxs.commitTx.tx)}")

    (commitments1, revocation)
  }

  def receiveRevocation(commitments: Commitments, revocation: RevokeAndAck): Commitments = {
    import commitments._
    // we receive a revocation because we just sent them a sig for their next commit tx
    remoteNextCommitInfo match {
      case Left(_) if revocation.perCommitmentSecret.toPoint != remoteCommit.remotePerCommitmentPoint =>
        throw InvalidRevocation
      case Left(WaitingForRevocation(theirNextCommit, _, _)) =>
        val commitments1 = commitments.copy(
          localChanges = localChanges.copy(signed = Nil, acked = localChanges.acked ++ localChanges.signed),
          remoteChanges = remoteChanges.copy(signed = Nil),
          remoteCommit = theirNextCommit,
          remoteNextCommitInfo = Right(revocation.nextPerCommitmentPoint),
          remotePerCommitmentSecrets = commitments.remotePerCommitmentSecrets.addHash(revocation.perCommitmentSecret, 0xFFFFFFFFFFFFL - commitments.remoteCommit.index))

        commitments1
      case Right(_) =>
        throw UnexpectedRevocation
    }
  }

  def makeLocalTxs(commitTxNumber: Long, localParams: LocalParams, remoteParams: RemoteParams, commitmentInput: InputInfo, localPerCommitmentPoint: Point, spec: CommitmentSpec): (CommitTx, Seq[HtlcTimeoutTx], Seq[HtlcSuccessTx]) = {
    val localPubkey = Generators.derivePubKey(localParams.paymentKey.toPoint, localPerCommitmentPoint)
    val localDelayedPubkey = Generators.derivePubKey(localParams.delayedPaymentKey.toPoint, localPerCommitmentPoint)
    val remotePubkey = Generators.derivePubKey(remoteParams.paymentBasepoint, localPerCommitmentPoint)
    val localRevocationPubkey = Generators.revocationPubKey(remoteParams.revocationBasepoint, localPerCommitmentPoint)
    val commitTx = Transactions.makeCommitTx(commitmentInput, commitTxNumber, localParams.paymentKey.toPoint, remoteParams.paymentBasepoint, localParams.isFunder, Satoshi(localParams.dustLimitSatoshis), localPubkey, localRevocationPubkey, remoteParams.toSelfDelay, localDelayedPubkey, remotePubkey, spec)
    val (htlcTimeoutTxs, htlcSuccessTxs) = Transactions.makeHtlcTxs(commitTx.tx, Satoshi(localParams.dustLimitSatoshis), localRevocationPubkey, remoteParams.toSelfDelay, localPubkey, localDelayedPubkey, remotePubkey, spec)
    (commitTx, htlcTimeoutTxs, htlcSuccessTxs)
  }

  def makeRemoteTxs(commitTxNumber: Long, localParams: LocalParams, remoteParams: RemoteParams, commitmentInput: InputInfo, remotePerCommitmentPoint: Point, spec: CommitmentSpec): (CommitTx, Seq[HtlcTimeoutTx], Seq[HtlcSuccessTx]) = {
    val localPubkey = Generators.derivePubKey(localParams.paymentKey.toPoint, remotePerCommitmentPoint)
    val remotePubkey = Generators.derivePubKey(remoteParams.paymentBasepoint, remotePerCommitmentPoint)
    val remoteDelayedPubkey = Generators.derivePubKey(remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
    val remoteRevocationPubkey = Generators.revocationPubKey(localParams.revocationSecret.toPoint, remotePerCommitmentPoint)
    val commitTx = Transactions.makeCommitTx(commitmentInput, commitTxNumber, remoteParams.paymentBasepoint, localParams.paymentKey.toPoint, !localParams.isFunder, Satoshi(remoteParams.dustLimitSatoshis), remotePubkey, remoteRevocationPubkey, localParams.toSelfDelay, remoteDelayedPubkey, localPubkey, spec)
    val (htlcTimeoutTxs, htlcSuccessTxs) = Transactions.makeHtlcTxs(commitTx.tx, Satoshi(localParams.dustLimitSatoshis), remoteRevocationPubkey, localParams.toSelfDelay, remotePubkey, remoteDelayedPubkey, localPubkey, spec)
    (commitTx, htlcTimeoutTxs, htlcSuccessTxs)
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
       |  toLocal: ${commitments.localCommit.spec.toLocalMsat}
       |  toRemote: ${commitments.localCommit.spec.toRemoteMsat}
       |  htlcs:
       |${commitments.localCommit.spec.htlcs.map(h => s"    ${h.direction} ${h.add.id} ${h.add.expiry}").mkString("\n")}
       |remotecommit:
       |  toLocal: ${commitments.remoteCommit.spec.toLocalMsat}
       |  toRemote: ${commitments.remoteCommit.spec.toRemoteMsat}
       |  htlcs:
       |${commitments.remoteCommit.spec.htlcs.map(h => s"    ${h.direction} ${h.add.id} ${h.add.expiry}").mkString("\n")}
       |next remotecommit:
       |  toLocal: ${commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.spec.toLocalMsat).getOrElse("N/A")}
       |  toRemote: ${commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.spec.toRemoteMsat).getOrElse("N/A")}
       |  htlcs:
       |${commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.spec.htlcs.map(h => s"    ${h.direction} ${h.add.id} ${h.add.expiry}").mkString("\n")).getOrElse("N/A")}""".stripMargin
  }
}


