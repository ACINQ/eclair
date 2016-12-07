package fr.acinq.eclair.channel

import fr.acinq.bitcoin.{BinaryData, Crypto, ScriptFlags, Transaction, TxOut}
import fr.acinq.eclair.crypto.LightningCrypto.sha256
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.transactions.{CommitTxTemplate, CommitmentSpec, Htlc}
import fr.acinq.eclair.wire._

// @formatter:off
case class LocalChanges(proposed: List[UpdateMessage], signed: List[UpdateMessage], acked: List[UpdateMessage]) {
  def all: List[UpdateMessage] = proposed ++ signed ++ acked
}
case class RemoteChanges(proposed: List[UpdateMessage], acked: List[UpdateMessage])
case class Changes(ourChanges: LocalChanges, theirChanges: RemoteChanges)
case class LocalCommit(index: Long, spec: CommitmentSpec, publishableTx: Transaction)
case class RemoteCommit(index: Long, spec: CommitmentSpec, txid: BinaryData, theirRevocationHash: BinaryData)
// @formatter:on

/**
  * about remoteNextCommitInfo:
  * we either:
  * - have built and signed their next commit tx with their next revocation hash which can now be discarded
  * - have their next revocation hash
  * So, when we've signed and sent a commit message and are waiting for their revocation message,
  * theirNextCommitInfo is their next commit tx. The rest of the time, it is their next revocation hash
  */
case class Commitments(localParams: LocalParams, remoteParams: RemoteParams,
                       localCommit: LocalCommit, remoteCommit: RemoteCommit,
                       localChanges: LocalChanges, remoteChanges: RemoteChanges,
                       localCurrentHtlcId: Long,
                       remoteNextCommitInfo: Either[RemoteCommit, BinaryData],
                       anchorOutput: TxOut,
                       shaSeed: BinaryData, theirPreimages: ShaChain, txDb: TxDb) {
  def anchorId: BinaryData = {
    assert(localCommit.publishableTx.txIn.size == 1, "commitment tx should only have one input")
    localCommit.publishableTx.txIn(0).outPoint.hash
  }

  def hasNoPendingHtlcs: Boolean = localCommit.spec.htlcs.isEmpty && remoteCommit.spec.htlcs.isEmpty

  def addLocalProposal(proposal: UpdateMessage): Commitments = Commitments.addLocalProposal(this, proposal)

  def addRemoteProposal(proposal: UpdateMessage): Commitments = Commitments.addRemoteProposal(this, proposal)
}

object Commitments {
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
    val available = reduced.to_remote_msat
    if (cmd.amountMsat > available) {
      throw new RuntimeException(s"insufficient funds (available=$available msat)")
    } else {
      val id = cmd.id.getOrElse(commitments.localCurrentHtlcId + 1)
      val add: UpdateAddHtlc = ???
      //AddHtlc(id, cmd.amountMsat, cmd.rHash, cmd.expiry, routing(ByteString.copyFrom(cmd.payment_route.toByteArray)))
      val commitments1 = addLocalProposal(commitments, add).copy(localCurrentHtlcId = id)
      (commitments1, add)
    }
  }

  def receiveAdd(commitments: Commitments, add: UpdateAddHtlc): Commitments = {
    // their available funds as seen by us, including all pending changes
    val reduced = CommitmentSpec.reduce(commitments.localCommit.spec, commitments.localChanges.acked, commitments.remoteChanges.proposed)
    // a node cannot spend pending incoming htlcs
    val available = reduced.to_remote_msat
    if (add.amountMsat > available) {
      throw new RuntimeException("Insufficient funds")
    } else {
      // TODO: nodeIds are ignored
      addRemoteProposal(commitments, add)
    }
  }

  def sendFulfill(commitments: Commitments, cmd: CMD_FULFILL_HTLC, channelId: Long): (Commitments, UpdateFulfillHtlc) = {
    commitments.localCommit.spec.htlcs.collectFirst { case u: Htlc if u.add.id == cmd.id => u.add } match {
      case Some(htlc) if htlc.paymentHash == sha256(cmd.r) =>
        val fulfill = UpdateFulfillHtlc(channelId, cmd.id, cmd.r)
        val commitments1 = addLocalProposal(commitments, fulfill)
        (commitments1, fulfill)
      case Some(htlc) => throw new RuntimeException(s"invalid htlc preimage for htlc id=${cmd.id}")
      case None => throw new RuntimeException(s"unknown htlc id=${cmd.id}")
    }
  }

  def receiveFulfill(commitments: Commitments, fulfill: UpdateFulfillHtlc): (Commitments, UpdateAddHtlc) = {
    commitments.remoteCommit.spec.htlcs.collectFirst { case u: Htlc if u.add.id == fulfill.id => u.add } match {
      case Some(htlc) if htlc.paymentHash == sha256(fulfill.paymentPreimage) => (addRemoteProposal(commitments, fulfill), htlc)
      case Some(htlc) => throw new RuntimeException(s"invalid htlc preimage for htlc id=${fulfill.id}")
      case None => throw new RuntimeException(s"unknown htlc id=${fulfill.id}") // TODO : we should fail the channel
    }
  }

  def sendFail(commitments: Commitments, cmd: CMD_FAIL_HTLC): (Commitments, UpdateFailHtlc) = {
    commitments.localCommit.spec.htlcs.collectFirst { case u: Htlc if u.add.id == cmd.id => u } match {
      case Some(htlc) =>
        val fail: UpdateFailHtlc = ???
        //UpdateFailHtlc(cmd.channelId, cmd.id, fail_reason(ByteString.copyFromUtf8(cmd.reason)))
        val commitments1 = addLocalProposal(commitments, fail)
        (commitments1, fail)
      case None => throw new RuntimeException(s"unknown htlc id=${cmd.id}")
    }
  }

  def receiveFail(commitments: Commitments, fail: UpdateFailHtlc): (Commitments, UpdateAddHtlc) = {
    commitments.remoteCommit.spec.htlcs.collectFirst { case u: Htlc if u.add.id == fail.id => u.add } match {
      case Some(htlc) => (addRemoteProposal(commitments, fail), htlc)
      case None => throw new RuntimeException(s"unknown htlc id=${fail.id}") // TODO : we should fail the channel
    }
  }

  def localHasChanges(commitments: Commitments): Boolean = commitments.remoteChanges.acked.size > 0 || commitments.localChanges.proposed.size > 0

  def remoteHasChanges(commitments: Commitments): Boolean = commitments.localChanges.acked.size > 0 || commitments.remoteChanges.proposed.size > 0

  def sendCommit(commitments: Commitments): (Commitments, CommitSig) = {
    import commitments._
    commitments.remoteNextCommitInfo match {
      case Right(remoteNextRevocationHash) if !localHasChanges(commitments) =>
        throw new RuntimeException("cannot sign when there are no changes")
      case Right(theirNextRevocationHash) =>
        // sign all our proposals + their acked proposals
        // their commitment now includes all our changes  + their acked changes
        val spec = CommitmentSpec.reduce(remoteCommit.spec, remoteChanges.acked, localChanges.proposed)
        val theirTxTemplate = CommitmentSpec.makeRemoteTxTemplate(localParams, remoteParams, localCommit.publishableTx.txIn, theirNextRevocationHash, spec)
        val theirTx = theirTxTemplate.makeTx
        // don't sign if they don't get paid
        val commit: CommitSig = ???
        /*if (theirTxTemplate.weHaveAnOutput) {
                 val ourSig = Helpers.sign(localParams, remoteParams, anchorOutput.amount, theirTx)
                 CommitSig(Some(ourSig))
               } else {
                 CommitSig(None)
               }*/
        val commitments1 = commitments.copy(
          remoteNextCommitInfo = Left(RemoteCommit(remoteCommit.index + 1, spec, theirTx.txid, theirNextRevocationHash)),
          localChanges = localChanges.copy(proposed = Nil, signed = localChanges.proposed),
          remoteChanges = remoteChanges.copy(acked = Nil))
        (commitments1, commit)
      case Left(remoteNextCommit) =>
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
    val ourNextRevocationHash = Helpers.revocationHash(shaSeed, localCommit.index + 1)
    val ourTxTemplate = CommitmentSpec.makeLocalTxTemplate(localParams, remoteParams, localCommit.publishableTx.txIn, ourNextRevocationHash, spec)

    // this tx will NOT be signed if our output is empty
    val ourCommitTx: Transaction = ???
    /*commit.sig match {
          case None if ourTxTemplate.weHaveAnOutput => throw new RuntimeException("expected signature")
          case None => ourTxTemplate.makeTx
          case Some(_) if !ourTxTemplate.weHaveAnOutput => throw new RuntimeException("unexpected signature")
          case Some(theirSig) =>
            val ourTx = ourTxTemplate.makeTx
            val ourSig = Helpers.sign(localParams, remoteParams, anchorOutput.amount, ourTx)
            val signedTx = Helpers.addSigs(localParams, remoteParams, anchorOutput.amount, ourTx, ourSig, theirSig)
            Helpers.checksig(localParams, remoteParams, anchorOutput, signedTx).get
            signedTx
        }*/

    // we will send our revocation preimage + our next revocation hash
    val ourRevocationPreimage = Helpers.revocationPreimage(shaSeed, localCommit.index)
    val ourNextRevocationHash1 = Helpers.revocationHash(shaSeed, localCommit.index + 2)
    val revocation: RevokeAndAck = ??? //RevokeAndAck(ourRevocationPreimage, ourNextRevocationHash1)

    // update our commitment data
    val ourCommit1 = localCommit.copy(index = localCommit.index + 1, spec, publishableTx = ourCommitTx)
    val ourChanges1 = localChanges.copy(acked = Nil)
    val theirChanges1 = remoteChanges.copy(proposed = Nil, acked = remoteChanges.acked ++ remoteChanges.proposed)
    val commitments1 = commitments.copy(localCommit = ourCommit1, localChanges = ourChanges1, remoteChanges = theirChanges1)

    (commitments1, revocation)
  }

  def receiveRevocation(commitments: Commitments, revocation: RevokeAndAck): Commitments = {
    import commitments._
    // we receive a revocation because we just sent them a sig for their next commit tx
    remoteNextCommitInfo match {
      case Left(theirNextCommit) if BinaryData(Crypto.sha256(revocation.perCommitmentSecret)) != BinaryData(remoteCommit.theirRevocationHash) =>
        throw new RuntimeException("invalid preimage")
      case Left(theirNextCommit) =>
        // this is their revoked commit tx
        val theirTxTemplate = CommitmentSpec.makeRemoteTxTemplate(localParams, remoteParams, localCommit.publishableTx.txIn, remoteCommit.theirRevocationHash, remoteCommit.spec)
        val theirTx = theirTxTemplate.makeTx
        val punishTx: Transaction = ??? //Helpers.claimRevokedCommitTx(theirTxTemplate, revocation.revocationPreimage, localParams.finalPrivKey)
        Transaction.correctlySpends(punishTx, Seq(theirTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        txDb.add(theirTx.txid, punishTx)

        commitments.copy(
          localChanges = localChanges.copy(signed = Nil, acked = localChanges.acked ++ localChanges.signed),
          remoteCommit = theirNextCommit,
          remoteNextCommitInfo = Right(revocation.nextPerCommitmentPoint),
          theirPreimages = commitments.theirPreimages.addHash(revocation.perCommitmentSecret, 0xFFFFFFFFFFFFFFFFL - commitments.remoteCommit.index))
      case Right(_) =>
        throw new RuntimeException("received unexpected RevokeAndAck message")
    }
  }

  def makeLocalTxTemplate(commitments: Commitments): CommitTxTemplate = {
    CommitmentSpec.makeLocalTxTemplate(commitments.localParams, commitments.remoteParams, commitments.localCommit.publishableTx.txIn,
      Helpers.revocationHash(commitments.shaSeed, commitments.localCommit.index), commitments.localCommit.spec)
  }

  def makeRemoteTxTemplate(commitments: Commitments): CommitTxTemplate = {
    commitments.remoteNextCommitInfo match {
      case Left(theirNextCommit) =>
        CommitmentSpec.makeRemoteTxTemplate(commitments.localParams, commitments.remoteParams, commitments.localCommit.publishableTx.txIn,
          theirNextCommit.theirRevocationHash, theirNextCommit.spec)
      case Right(revocationHash) =>
        CommitmentSpec.makeRemoteTxTemplate(commitments.localParams, commitments.remoteParams, commitments.localCommit.publishableTx.txIn,
          commitments.remoteCommit.theirRevocationHash, commitments.remoteCommit.spec)
    }
  }
}


