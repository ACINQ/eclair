package fr.acinq.eclair.channel

import fr.acinq.bitcoin.Crypto.{Point, sha256}
import fr.acinq.bitcoin.{BinaryData, Crypto, Satoshi, Transaction}
import fr.acinq.eclair.crypto.{Generators, ShaChain}
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire._
import grizzled.slf4j.Logging

// @formatter:off
case class LocalChanges(proposed: List[UpdateMessage], signed: List[UpdateMessage], acked: List[UpdateMessage]) {
  def all: List[UpdateMessage] = proposed ++ signed ++ acked
}
case class RemoteChanges(proposed: List[UpdateMessage], acked: List[UpdateMessage])
case class Changes(ourChanges: LocalChanges, theirChanges: RemoteChanges)
case class HtlcTxAndSigs(txinfo: TransactionWithInputInfo, localSig: BinaryData, remoteSig: BinaryData)
case class PublishableTxs(commitTx: CommitTx, htlcTxsAndSigs: Seq[HtlcTxAndSigs])
case class LocalCommit(index: Long, spec: CommitmentSpec, publishableTxs: PublishableTxs)
case class RemoteCommit(index: Long, spec: CommitmentSpec, txid: BinaryData, remotePerCommitmentPoint: Point)
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
                       localCurrentHtlcId: Long,
                       remoteNextCommitInfo: Either[RemoteCommit, Point],
                       commitInput: InputInfo,
                       remotePerCommitmentSecrets: ShaChain, channelId: Long) {
  def anchorId: BinaryData = commitInput.outPoint.txid

  def hasNoPendingHtlcs: Boolean = localCommit.spec.htlcs.isEmpty && remoteCommit.spec.htlcs.isEmpty

  def hasTimedoutHtlcs(blockheight: Long): Boolean =
    localCommit.spec.htlcs.exists(htlc => htlc.direction == OUT && blockheight >= htlc.add.expiry) ||
    remoteCommit.spec.htlcs.exists(htlc => htlc.direction == IN && blockheight >= htlc.add.expiry)

  def addLocalProposal(proposal: UpdateMessage): Commitments = Commitments.addLocalProposal(this, proposal)

  def addRemoteProposal(proposal: UpdateMessage): Commitments = Commitments.addRemoteProposal(this, proposal)
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

  def sendAdd(commitments: Commitments, cmd: CMD_ADD_HTLC): (Commitments, UpdateAddHtlc) = {
    // our available funds as seen by them, including all pending changes
    val reduced = CommitmentSpec.reduce(commitments.remoteCommit.spec, commitments.remoteChanges.acked, commitments.localChanges.proposed)
    // a node cannot spend pending incoming htlcs
    val available = reduced.toRemoteMsat
    if (cmd.amountMsat > available) {
      throw new RuntimeException(s"insufficient funds (available=$available msat)")
    } else {
      val id = cmd.id.getOrElse(commitments.localCurrentHtlcId + 1)
      val add = UpdateAddHtlc(commitments.channelId, id, cmd.amountMsat, cmd.expiry, cmd.paymentHash, cmd.onion)
      val commitments1 = addLocalProposal(commitments, add).copy(localCurrentHtlcId = id)
      (commitments1, add)
    }
  }

  def receiveAdd(commitments: Commitments, add: UpdateAddHtlc): Commitments = {
    // their available funds as seen by us, including all pending changes
    val reduced = CommitmentSpec.reduce(commitments.localCommit.spec, commitments.localChanges.acked, commitments.remoteChanges.proposed)
    // a node cannot spend pending incoming htlcs
    val available = reduced.toRemoteMsat
    if (add.amountMsat > available) {
      throw new RuntimeException("Insufficient funds")
    } else {
      // TODO: nodeIds are ignored
      addRemoteProposal(commitments, add)
    }
  }

  def sendFulfill(commitments: Commitments, cmd: CMD_FULFILL_HTLC): (Commitments, UpdateFulfillHtlc) = {
    commitments.localCommit.spec.htlcs.collectFirst { case u: Htlc if u.direction == IN && u.add.id == cmd.id => u.add } match {
      case Some(htlc) if htlc.paymentHash == sha256(cmd.r) =>
        val fulfill = UpdateFulfillHtlc(commitments.channelId, cmd.id, cmd.r)
        val commitments1 = addLocalProposal(commitments, fulfill)
        (commitments1, fulfill)
      case Some(htlc) => throw new RuntimeException(s"invalid htlc preimage for htlc id=${cmd.id}")
      case None => throw new RuntimeException(s"unknown htlc id=${cmd.id}")
    }
  }

  def receiveFulfill(commitments: Commitments, fulfill: UpdateFulfillHtlc): (Commitments, UpdateAddHtlc) = {
    commitments.remoteCommit.spec.htlcs.collectFirst { case u: Htlc if u.direction == IN && u.add.id == fulfill.id => u.add } match {
      case Some(htlc) if htlc.paymentHash == sha256(fulfill.paymentPreimage) => (addRemoteProposal(commitments, fulfill), htlc)
      case Some(htlc) => throw new RuntimeException(s"invalid htlc preimage for htlc id=${fulfill.id}")
      case None => throw new RuntimeException(s"unknown htlc id=${fulfill.id}") // TODO: we should fail the channel
    }
  }

  def sendFail(commitments: Commitments, cmd: CMD_FAIL_HTLC): (Commitments, UpdateFailHtlc) = {
    commitments.localCommit.spec.htlcs.collectFirst { case u: Htlc if u.add.id == cmd.id => u.add } match {
      case Some(htlc) =>
        val fail = UpdateFailHtlc(commitments.channelId, cmd.id, BinaryData(cmd.reason.getBytes("UTF-8")))
        val commitments1 = addLocalProposal(commitments, fail)
        (commitments1, fail)
      case None => throw new RuntimeException(s"unknown htlc id=${cmd.id}")
    }
  }

  def receiveFail(commitments: Commitments, fail: UpdateFailHtlc): (Commitments, UpdateAddHtlc) = {
    commitments.remoteCommit.spec.htlcs.collectFirst { case u: Htlc if u.add.id == fail.id => u.add } match {
      case Some(htlc) => (addRemoteProposal(commitments, fail), htlc)
      case None => throw new RuntimeException(s"unknown htlc id=${fail.id}") // TODO: we should fail the channel
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
          remoteNextCommitInfo = Left(RemoteCommit(remoteCommit.index + 1, spec, remoteCommitTx.tx.txid, remoteNextPerCommitmentPoint)),
          localChanges = localChanges.copy(proposed = Nil, signed = localChanges.proposed),
          remoteChanges = remoteChanges.copy(acked = Nil))
        (commitments1, commitSig)
      case Left(_) =>
        throw new RuntimeException("cannot sign until next revocation hash is received")
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
    if (Transactions.checkSpendable(signedCommitTx).isFailure) throw new RuntimeException("invalid sig")

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
    val ourCommit1 = localCommit.copy(index = localCommit.index + 1, spec, publishableTxs = PublishableTxs(signedCommitTx, htlcTxsAndSigs))
    val ourChanges1 = localChanges.copy(acked = Nil)
    val theirChanges1 = remoteChanges.copy(proposed = Nil, acked = remoteChanges.acked ++ remoteChanges.proposed)
    val commitments1 = commitments.copy(localCommit = ourCommit1, localChanges = ourChanges1, remoteChanges = theirChanges1)

    logger.debug(s"current commit: index=${ourCommit1.index} htlc_in=${ourCommit1.spec.htlcs.filter(_.direction == IN).size} htlc_out=${ourCommit1.spec.htlcs.filter(_.direction == OUT).size} tx=${Transaction.write(ourCommit1.publishableTxs.commitTx.tx)}")

    (commitments1, revocation)
  }

  def receiveRevocation(commitments: Commitments, revocation: RevokeAndAck): Commitments = {
    import commitments._
    // we receive a revocation because we just sent them a sig for their next commit tx
    remoteNextCommitInfo match {
      case Left(_) if revocation.perCommitmentSecret.toPoint != remoteCommit.remotePerCommitmentPoint =>
        throw new RuntimeException("invalid preimage")
      case Left(theirNextCommit) =>
        // we rebuild the transactions a 2nd time but we are just interested in HTLC-timeout txs because we need to check their sig
        val (_, htlcTimeoutTxs, _) = makeRemoteTxs(theirNextCommit.index, localParams, remoteParams, commitInput, theirNextCommit.remotePerCommitmentPoint, theirNextCommit.spec)
        // then we sort and sign them
        val sortedHtlcTimeoutTxs = htlcTimeoutTxs.sortBy(_.input.outPoint.index)
        require(revocation.htlcTimeoutSignatures.size == sortedHtlcTimeoutTxs.size, s"htlc-timeout sig count mismatch (received=${revocation.htlcTimeoutSignatures.size}, expected=${sortedHtlcTimeoutTxs.size})")
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

        commitments.copy(
          localChanges = localChanges.copy(signed = Nil, acked = localChanges.acked ++ localChanges.signed),
          remoteCommit = theirNextCommit,
          remoteNextCommitInfo = Right(revocation.nextPerCommitmentPoint),
          remotePerCommitmentSecrets = commitments.remotePerCommitmentSecrets.addHash(revocation.perCommitmentSecret, 0xFFFFFFFFFFFFL - commitments.remoteCommit.index))
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
}


