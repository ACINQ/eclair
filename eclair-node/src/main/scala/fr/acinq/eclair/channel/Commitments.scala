package fr.acinq.eclair.channel

import fr.acinq.bitcoin.Crypto.{Point, sha256}
import fr.acinq.bitcoin.{BinaryData, Crypto, Satoshi, Transaction}
import fr.acinq.eclair.Globals
import fr.acinq.eclair.crypto.{Generators, ShaChain}
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
case class PublishableTxs(commitTx: CommitTx, htlcTxsAndSigs: Seq[HtlcTxAndSigs])
case class LocalCommit(index: Long, spec: CommitmentSpec, publishableTxs: PublishableTxs, commit: CommitSig)
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
                       unackedMessages: Seq[LightningMessage],
                       commitInput: InputInfo,
                       remotePerCommitmentSecrets: ShaChain, channelId: Long) {

  def hasNoPendingHtlcs: Boolean = localCommit.spec.htlcs.isEmpty && remoteCommit.spec.htlcs.isEmpty

  def hasTimedoutHtlcs(blockheight: Long): Boolean =
    localCommit.spec.htlcs.exists(htlc => htlc.direction == OUT && blockheight >= htlc.add.expiry) ||
      remoteCommit.spec.htlcs.exists(htlc => htlc.direction == IN && blockheight >= htlc.add.expiry)

  def addLocalProposal(proposal: UpdateMessage): Commitments = Commitments.addLocalProposal(this, proposal)

  def addRemoteProposal(proposal: UpdateMessage): Commitments = Commitments.addRemoteProposal(this, proposal)

  def addToUnackedMessages(message: LightningMessage) : Commitments = this.copy(unackedMessages = unackedMessages :+ message)

  def unackedShutdown(): Option[Shutdown] = this.unackedMessages.collectFirst{ case d: Shutdown => d}
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
    commitments.copy(
      localChanges = commitments.localChanges.copy(proposed = commitments.localChanges.proposed :+ proposal),
      unackedMessages = commitments.unackedMessages :+ proposal)

  private def addRemoteProposal(commitments: Commitments, proposal: UpdateMessage): Commitments =
    commitments.copy(remoteChanges = commitments.remoteChanges.copy(proposed = commitments.remoteChanges.proposed :+ proposal))

  def sendAdd(commitments: Commitments, cmd: CMD_ADD_HTLC): (Commitments, UpdateAddHtlc) = {
    val blockCount = Globals.blockCount.get()
    require(cmd.expiry > blockCount, s"expiry can't be in the past (expiry=${cmd.expiry} blockCount=$blockCount)")

    if (cmd.amountMsat < commitments.remoteParams.htlcMinimumMsat) {
      throw new RuntimeException(s"counterparty requires a minimum htlc value of ${commitments.remoteParams.htlcMinimumMsat} msat")
    }

    // let's compute the current commitment *as seen by them* with this change taken into account
    val add = UpdateAddHtlc(commitments.channelId, commitments.localNextHtlcId, cmd.amountMsat, cmd.expiry, cmd.paymentHash, cmd.onion)
    val commitments1 = addLocalProposal(commitments, add).copy(localNextHtlcId = commitments.localNextHtlcId + 1)
    val reduced = CommitmentSpec.reduce(commitments1.remoteCommit.spec, commitments1.remoteChanges.acked, commitments1.localChanges.proposed)

    val htlcValueInFlight = reduced.htlcs.map(_.add.amountMsat).sum
    if (htlcValueInFlight > commitments1.remoteParams.maxHtlcValueInFlightMsat) {
      throw new RuntimeException(s"reached counterparty's in-flight htlcs value limit: value=$htlcValueInFlight max=${commitments1.remoteParams.maxHtlcValueInFlightMsat}")
    }

    // the HTLC we are about to create is outgoing, but from their point of view it is incoming
    val acceptedHtlcs = reduced.htlcs.count(_.direction == IN)
    if (acceptedHtlcs > commitments1.remoteParams.maxAcceptedHtlcs) {
      throw new RuntimeException(s"reached counterparty's max accepted htlc count limit: value=$acceptedHtlcs max=${commitments1.remoteParams.maxAcceptedHtlcs}")
    }

    // a node cannot spend pending incoming htlcs, and need to keep funds above the reserve required by the counterparty, after paying the fee
    // we look from remote's point of view, so if local is funder remote doesn't pay the fees
    val fees = if (commitments1.localParams.isFunder) Transactions.commitTxFee(Satoshi(commitments1.remoteParams.dustLimitSatoshis), reduced).amount else 0
    val missing = reduced.toRemoteMsat / 1000 - commitments1.remoteParams.channelReserveSatoshis - fees
    if (missing < 0) {
      throw new RuntimeException(s"insufficient funds: missing=${-1 * missing} reserve=${commitments1.remoteParams.channelReserveSatoshis} fees=$fees")
    }

    (commitments1, add)
  }

  def isOldAdd(commitments: Commitments, add: UpdateAddHtlc): Boolean = {
    add.id < commitments.remoteNextHtlcId
  }

  def receiveAdd(commitments: Commitments, add: UpdateAddHtlc): Commitments = {
    isOldAdd(commitments, add) match {
      case true => commitments
      case false =>

        if (add.id != commitments.remoteNextHtlcId) {
          throw new RuntimeException(s"unexpected htlc id: actual=${add.id} expected=${commitments.remoteNextHtlcId}")
        }

        val blockCount = Globals.blockCount.get()
        // if we are the final payee, we need a reasonable amount of time to pull the funds before the sender can get refunded
        val minExpiry = blockCount + 3
        if (add.expiry < minExpiry) {
          throw new RuntimeException(s"expiry too small: required=$minExpiry actual=${add.expiry} (blockCount=$blockCount)")
        }

        if (add.amountMsat < commitments.localParams.htlcMinimumMsat) {
          throw new RuntimeException(s"htlc value too small: min=${commitments.localParams.htlcMinimumMsat}")
        }

        // let's compute the current commitment *as seen by us* including this change
        val commitments1 = addRemoteProposal(commitments, add).copy(remoteNextHtlcId = commitments.remoteNextHtlcId + 1)
        val reduced = CommitmentSpec.reduce(commitments1.localCommit.spec, commitments1.localChanges.acked, commitments1.remoteChanges.proposed)

        val htlcValueInFlight = reduced.htlcs.map(_.add.amountMsat).sum
        if (htlcValueInFlight > commitments1.localParams.maxHtlcValueInFlightMsat) {
          throw new RuntimeException(s"in-flight htlcs hold too much value: value=$htlcValueInFlight max=${commitments1.localParams.maxHtlcValueInFlightMsat}")
        }

        val acceptedHtlcs = reduced.htlcs.count(_.direction == IN)
        if (acceptedHtlcs > commitments1.localParams.maxAcceptedHtlcs) {
          throw new RuntimeException(s"too many accepted htlcs: value=$acceptedHtlcs max=${commitments1.localParams.maxAcceptedHtlcs}")
        }

        // a node cannot spend pending incoming htlcs, and need to keep funds above the reserve required by the counterparty, after paying the fee
        val fees = if (commitments1.localParams.isFunder) 0 else Transactions.commitTxFee(Satoshi(commitments1.remoteParams.dustLimitSatoshis), reduced).amount
        val missing = reduced.toRemoteMsat / 1000 - commitments1.localParams.channelReserveSatoshis - fees
        if (missing < 0) {
          throw new RuntimeException(s"insufficient funds: missing=${-1 * missing} reserve=${commitments1.localParams.channelReserveSatoshis} fees=$fees")
        }

        commitments1
    }
  }

  def getHtlcCrossSigned(commitments: Commitments, directionRelativeToLocal: Direction, htlcId: Long): Option[UpdateAddHtlc] = {
    val remoteSigned = commitments.localCommit.spec.htlcs.find(htlc => htlc.direction == directionRelativeToLocal && htlc.add.id == htlcId)
    val localSigned = commitments.remoteNextCommitInfo match {
      case Left(waitingForRevocation) => waitingForRevocation.nextRemoteCommit.spec.htlcs.find(htlc => htlc.direction == directionRelativeToLocal.opposite && htlc.add.id == htlcId)
      case Right(_) => commitments.remoteCommit.spec.htlcs.find(htlc => htlc.direction == directionRelativeToLocal.opposite && htlc.add.id == htlcId)
    }
    for {
      htlc_out <- remoteSigned
      htlc_in <- localSigned
    } yield htlc_in.add
  }

  def sendFulfill(commitments: Commitments, cmd: CMD_FULFILL_HTLC): (Commitments, UpdateFulfillHtlc) =
    getHtlcCrossSigned(commitments, IN, cmd.id) match {
      case Some(htlc) if htlc.paymentHash == sha256(cmd.r) =>
        val fulfill = UpdateFulfillHtlc(commitments.channelId, cmd.id, cmd.r)
        val commitments1 = addLocalProposal(commitments, fulfill)
        (commitments1, fulfill)
      case Some(htlc) => throw new RuntimeException(s"invalid htlc preimage for htlc id=${cmd.id}")
      case None => throw new RuntimeException(s"unknown htlc id=${cmd.id}")
    }

  def isOldFulfill(commitments: Commitments, fulfill: UpdateFulfillHtlc): Boolean =
    commitments.remoteChanges.proposed.contains(fulfill) ||
      commitments.remoteChanges.signed.contains(fulfill) ||
      commitments.remoteChanges.acked.contains(fulfill)

  def receiveFulfill(commitments: Commitments, fulfill: UpdateFulfillHtlc): Either[Commitments, Commitments] =
    isOldFulfill(commitments, fulfill) match {
      case true => Left(commitments)
      case false => getHtlcCrossSigned(commitments, OUT, fulfill.id) match {
        case Some(htlc) if htlc.paymentHash == sha256(fulfill.paymentPreimage) => Right(addRemoteProposal(commitments, fulfill))
        case Some(htlc) => throw new RuntimeException(s"invalid htlc preimage for htlc id=${fulfill.id}")
        case None => throw new RuntimeException(s"unknown htlc id=${fulfill.id}")
      }
    }

  def sendFail(commitments: Commitments, cmd: CMD_FAIL_HTLC): (Commitments, UpdateFailHtlc) =
    getHtlcCrossSigned(commitments, IN, cmd.id) match {
      case Some(htlc) =>
        val fail = UpdateFailHtlc(commitments.channelId, cmd.id, BinaryData(cmd.reason.getBytes("UTF-8")))
        val commitments1 = addLocalProposal(commitments, fail)
        (commitments1, fail)
      case None => throw new RuntimeException(s"unknown htlc id=${cmd.id}")
    }

  def isOldFail(commitments: Commitments, fail: UpdateFailHtlc): Boolean =
    commitments.remoteChanges.proposed.contains(fail) ||
      commitments.remoteChanges.signed.contains(fail) ||
      commitments.remoteChanges.acked.contains(fail)

  def receiveFail(commitments: Commitments, fail: UpdateFailHtlc): Either[Commitments, Commitments] =
    isOldFail(commitments, fail) match {
      case true => Left(commitments)
      case false => getHtlcCrossSigned(commitments, OUT, fail.id) match {
        case Some(htlc) => Right(addRemoteProposal(commitments, fail))
        case None => throw new RuntimeException(s"unknown htlc id=${fail.id}")
      }
    }

  def localHasChanges(commitments: Commitments): Boolean = commitments.remoteChanges.acked.size > 0 || commitments.localChanges.proposed.size > 0

  def remoteHasChanges(commitments: Commitments): Boolean = commitments.localChanges.acked.size > 0 || commitments.remoteChanges.proposed.size > 0

  def revocationPreimage(seed: BinaryData, index: Long): BinaryData = ShaChain.shaChainFromSeed(seed, 0xFFFFFFFFFFFFFFFFL - index)

  def revocationHash(seed: BinaryData, index: Long): BinaryData = Crypto.sha256(revocationPreimage(seed, index))

  def sendCommit(commitments: Commitments): (Commitments, CommitSig) = {
    import commitments._
    commitments.remoteNextCommitInfo match {
      case Right(_) if !localHasChanges(commitments) =>
        throw new RuntimeException("cannot sign when there are no changes")
      case Right(remoteNextPerCommitmentPoint) =>
        // remote commitment will includes all local changes + remote acked changes
        if (remoteCommit.spec.htlcs.size == 2 && localChanges.proposed.size >= 1) {
          val a = 1
        }
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
          remoteChanges = remoteChanges.copy(acked = Nil, signed = remoteChanges.acked),
          unackedMessages = unackedMessages :+ commitSig)
        (commitments1, commitSig)
      case Left(_) =>
        throw new RuntimeException("cannot sign until next revocation hash is received")
    }
  }

  def isOldCommit(commitments: Commitments, commit: CommitSig): Boolean = commitments.localCommit.commit == commit

  def receiveCommit(commitments: Commitments, commit: CommitSig): Either[Commitments, (Commitments, RevokeAndAck)] =
    isOldCommit(commitments, commit) match {
      case true => Left(commitments)
      case false =>
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
          throw new RuntimeException("cannot sign when there are no changes")

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
          throw new RuntimeException("invalid sig")
        }

        val sortedHtlcTxs: Seq[TransactionWithInputInfo] = (htlcTimeoutTxs ++ htlcSuccessTxs).sortBy(_.input.outPoint.index)
        require(commit.htlcSignatures.size == sortedHtlcTxs.size, s"htlc sig count mismatch (received=${commit.htlcSignatures.size}, expected=${sortedHtlcTxs.size})")
        val localPaymentKey = Generators.derivePrivKey(localParams.paymentKey, localPerCommitmentPoint)
        val htlcSigs = sortedHtlcTxs.map(Transactions.sign(_, localPaymentKey))
        val remotePaymentPubkey = Generators.derivePubKey(remoteParams.paymentBasepoint, localPerCommitmentPoint)
        // combine the sigs to make signed txes
        val htlcTxsAndSigs = sortedHtlcTxs
          .zip(htlcSigs)
          .zip(commit.htlcSignatures) // this is a list of ((tx, localSig), remoteSig)
          .map(e => (e._1._1, e._1._2, e._2)) // this is a list of (tx, localSig, remoteSig)
          .collect {
          case (htlcTx: HtlcTimeoutTx, localSig, remoteSig) =>
            require(Transactions.checkSpendable(Transactions.addSigs(htlcTx, localSig, remoteSig)).isSuccess, "bad sig")
            HtlcTxAndSigs(htlcTx, localSig, remoteSig)
          case (htlcTx: HtlcSuccessTx, localSig, remoteSig) =>
            // we can't check that htlc-success tx are spendable because we need the payment preimage; thus we only check the remote sig
            require(Transactions.checkSig(htlcTx, remoteSig, remotePaymentPubkey), "bad sig")
            HtlcTxAndSigs(htlcTx, localSig, remoteSig)
        }

        val timeoutHtlcSigs = htlcTxsAndSigs.collect {
          case HtlcTxAndSigs(_: HtlcTimeoutTx, localSig, _) => localSig
        }

        // we will send our revocation preimage + our next revocation hash
        val localPerCommitmentSecret = Generators.perCommitSecret(localParams.shaSeed, commitments.localCommit.index)
        val localNextPerCommitmentPoint = Generators.perCommitPoint(localParams.shaSeed, commitments.localCommit.index + 2)
        val revocation = RevokeAndAck(
          channelId = commitments.channelId,
          perCommitmentSecret = localPerCommitmentSecret,
          nextPerCommitmentPoint = localNextPerCommitmentPoint,
          htlcTimeoutSignatures = timeoutHtlcSigs.toList
        )

        // update our commitment data
        val ourCommit1 = LocalCommit(
          index = localCommit.index + 1,
          spec,
          publishableTxs = PublishableTxs(signedCommitTx, htlcTxsAndSigs),
          commit = commit)
        val ourChanges1 = localChanges.copy(acked = Nil)
        val theirChanges1 = remoteChanges.copy(proposed = Nil, acked = remoteChanges.acked ++ remoteChanges.proposed)
        // they have received our previous revocation (otherwise they wouldn't have sent a commit)
        // so we can acknowledge the revocation
        val unackedMessages1 = commitments.unackedMessages.filterNot(_.isInstanceOf[RevokeAndAck]) :+ revocation
        val commitments1 = commitments.copy(localCommit = ourCommit1, localChanges = ourChanges1, remoteChanges = theirChanges1, unackedMessages = unackedMessages1)

        logger.debug(s"current commit: index=${ourCommit1.index} htlc_in=${ourCommit1.spec.htlcs.filter(_.direction == IN).size} htlc_out=${ourCommit1.spec.htlcs.filter(_.direction == OUT).size} txid=${ourCommit1.publishableTxs.commitTx.tx.txid} tx=${Transaction.write(ourCommit1.publishableTxs.commitTx.tx)}")

        Right((commitments1, revocation))
    }

  def isOldRevocation(commitments: Commitments, revocation: RevokeAndAck): Boolean =
    commitments.remoteNextCommitInfo match {
      case Right(point) if point == revocation.nextPerCommitmentPoint => true
      case Left(waitForRevocation) if waitForRevocation.nextRemoteCommit.remotePerCommitmentPoint == revocation.nextPerCommitmentPoint => true
      case _ => false
    }

  def receiveRevocation(commitments: Commitments, revocation: RevokeAndAck): Either[Commitments, Commitments] =
    isOldRevocation(commitments, revocation) match {
      case true => Left(commitments)
      case false =>
        import commitments._
        // we receive a revocation because we just sent them a sig for their next commit tx
        remoteNextCommitInfo match {
          case Left(_) if revocation.perCommitmentSecret.toPoint != remoteCommit.remotePerCommitmentPoint =>
            throw new RuntimeException("invalid preimage")
          case Left(WaitingForRevocation(theirNextCommit, _, _)) =>
            // we rebuild the transactions a 2nd time but we are just interested in HTLC-timeout txs because we need to check their sig
            val (_, htlcTimeoutTxs, _) = makeRemoteTxs(theirNextCommit.index, localParams, remoteParams, commitInput, theirNextCommit.remotePerCommitmentPoint, theirNextCommit.spec)
            // then we sort and sign them
            val sortedHtlcTimeoutTxs = htlcTimeoutTxs.sortBy(_.input.outPoint.index)
            require(revocation.htlcTimeoutSignatures.size == sortedHtlcTimeoutTxs.size, s"htlc-timeout sig count mismatch (received=${
              revocation.htlcTimeoutSignatures.size
            }, expected=${
              sortedHtlcTimeoutTxs.size
            })")
            val paymentKey = Generators.derivePrivKey(localParams.paymentKey, theirNextCommit.remotePerCommitmentPoint)
            val htlcSigs = sortedHtlcTimeoutTxs.map(Transactions.sign(_, paymentKey))
            // combine the sigs to make signed txes
            val signedHtlcTxs = sortedHtlcTimeoutTxs
              .zip(htlcSigs)
              .zip(revocation.htlcTimeoutSignatures) // this is a list of ((tx, localSig), remoteSig)
              .map(e => (e._1._1, e._1._2, e._2)) // this is a list of (tx, localSig, remoteSig)
              .map(x => Transactions.addSigs(x._1, x._3, x._2))

            // and finally whe check the sigs
            require(signedHtlcTxs.forall(Transactions.checkSpendable(_).isSuccess), "bad sig")

            // they have received our last commitsig (otherwise they wouldn't have replied with a revocation)
            // so we can acknowledge all our previous updates and the commitsig
            val unackedMessages1 = commitments.unackedMessages.drop(commitments.unackedMessages.indexWhere(_.isInstanceOf[CommitSig]) + 1)

            val commitments1 = commitments.copy(
              localChanges = localChanges.copy(signed = Nil, acked = localChanges.acked ++ localChanges.signed),
              remoteChanges = remoteChanges.copy(signed = Nil),
              remoteCommit = theirNextCommit,
              remoteNextCommitInfo = Right(revocation.nextPerCommitmentPoint),
              unackedMessages = unackedMessages1,
              remotePerCommitmentSecrets = commitments.remotePerCommitmentSecrets.addHash(revocation.perCommitmentSecret, 0xFFFFFFFFFFFFL - commitments.remoteCommit.index))

            Right(commitments1)
          case Right(_) =>
            throw new RuntimeException("received unexpected RevokeAndAck message")
        }
    }

  def makeLocalTxs(commitTxNumber: Long, localParams: LocalParams, remoteParams: RemoteParams, commitmentInput: InputInfo, localPerCommitmentPoint: Point, spec: CommitmentSpec): (CommitTx, Seq[HtlcTimeoutTx], Seq[HtlcSuccessTx]) = {
    val localPubkey = Generators.derivePubKey(localParams.paymentKey.toPoint, localPerCommitmentPoint)
    val localDelayedPubkey = Generators.derivePubKey(localParams.delayedPaymentKey.toPoint, localPerCommitmentPoint)
    val remotePubkey = Generators.derivePubKey(remoteParams.paymentBasepoint, localPerCommitmentPoint)
    val localRevocationPubkey = Generators.revocationPubKey(remoteParams.revocationBasepoint, localPerCommitmentPoint)
    val commitTx = Transactions.makeCommitTx(commitmentInput, commitTxNumber, localParams.paymentKey.toPoint, remoteParams.paymentBasepoint, localParams.isFunder, Satoshi(localParams.dustLimitSatoshis), localPubkey, localRevocationPubkey, localParams.toSelfDelay, localDelayedPubkey, remotePubkey, spec)
    val (htlcTimeoutTxs, htlcSuccessTxs) = Transactions.makeHtlcTxs(commitTx.tx, Satoshi(localParams.dustLimitSatoshis), localRevocationPubkey, localParams.toSelfDelay, localPubkey, localDelayedPubkey, remotePubkey, spec)
    (commitTx, htlcTimeoutTxs, htlcSuccessTxs)
  }

  def makeRemoteTxs(commitTxNumber: Long, localParams: LocalParams, remoteParams: RemoteParams, commitmentInput: InputInfo, remotePerCommitmentPoint: Point, spec: CommitmentSpec): (CommitTx, Seq[HtlcTimeoutTx], Seq[HtlcSuccessTx]) = {
    val localPubkey = Generators.derivePubKey(localParams.paymentKey.toPoint, remotePerCommitmentPoint)
    val remotePubkey = Generators.derivePubKey(remoteParams.paymentBasepoint, remotePerCommitmentPoint)
    val remoteDelayedPubkey = Generators.derivePubKey(remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
    val remoteRevocationPubkey = Generators.revocationPubKey(localParams.revocationSecret.toPoint, remotePerCommitmentPoint)
    val commitTx = Transactions.makeCommitTx(commitmentInput, commitTxNumber, remoteParams.paymentBasepoint, localParams.paymentKey.toPoint, !localParams.isFunder, Satoshi(remoteParams.dustLimitSatoshis), remotePubkey, remoteRevocationPubkey, remoteParams.toSelfDelay, remoteDelayedPubkey, localPubkey, spec)
    val (htlcTimeoutTxs, htlcSuccessTxs) = Transactions.makeHtlcTxs(commitTx.tx, Satoshi(localParams.dustLimitSatoshis), remoteRevocationPubkey, remoteParams.toSelfDelay, remotePubkey, remoteDelayedPubkey, localPubkey, spec)
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
       |        remote: $remoteNextHtlcId
       |    unackedMessages:
       |        ${unackedMessages.map(msg2String(_)).mkString(" ")}""".stripMargin
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


