package fr.acinq.eclair.channel

import fr.acinq.bitcoin.{BinaryData, Crypto, ScriptFlags, Transaction, TxOut}
import fr.acinq.eclair.transactions.CommitTxTemplate
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.crypto.LightningCrypto.sha256
import fr.acinq.eclair.wire._

// @formatter:off

case class OurChanges(proposed: List[UpdateMessage], signed: List[UpdateMessage], acked: List[UpdateMessage]) {
  def all: List[UpdateMessage] = proposed ++ signed ++ acked
}

case class TheirChanges(proposed: List[UpdateMessage], acked: List[UpdateMessage])

case class Changes(ourChanges: OurChanges, theirChanges: TheirChanges)

case class OurCommit(index: Long, spec: CommitmentSpec, publishableTx: Transaction)

case class TheirCommit(index: Long, spec: CommitmentSpec, txid: BinaryData, theirRevocationHash: BinaryData)

// @formatter:on

/**
  * about theirNextCommitInfo:
  * we either:
  * - have built and signed their next commit tx with their next revocation hash which can now be discarded
  * - have their next revocation hash
  * So, when we've signed and sent a commit message and are waiting for their revocation message,
  * theirNextCommitInfo is their next commit tx. The rest of the time, it is their next revocation hash
  */
case class Commitments(localParams: LocalParams, remoteParams: RemoteParams,
                       ourCommit: OurCommit, theirCommit: TheirCommit,
                       ourChanges: OurChanges, theirChanges: TheirChanges,
                       ourCurrentHtlcId: Long,
                       theirNextCommitInfo: Either[TheirCommit, BinaryData],
                       anchorOutput: TxOut,
                       shaSeed: BinaryData, theirPreimages: ShaChain, txDb: TxDb) {
  def anchorId: BinaryData = {
    assert(ourCommit.publishableTx.txIn.size == 1, "commitment tx should only have one input")
    ourCommit.publishableTx.txIn(0).outPoint.hash
  }

  def hasNoPendingHtlcs: Boolean = ourCommit.spec.htlcs.isEmpty && theirCommit.spec.htlcs.isEmpty

  def addOurProposal(proposal: UpdateMessage): Commitments = Commitments.addOurProposal(this, proposal)

  def addTheirProposal(proposal: UpdateMessage): Commitments = Commitments.addTheirProposal(this, proposal)
}

object Commitments {
  /**
    * add a change to our proposed change list
    *
    * @param commitments
    * @param proposal
    * @return an updated commitment instance
    */
  private def addOurProposal(commitments: Commitments, proposal: UpdateMessage): Commitments =
  commitments.copy(ourChanges = commitments.ourChanges.copy(proposed = commitments.ourChanges.proposed :+ proposal))

  private def addTheirProposal(commitments: Commitments, proposal: UpdateMessage): Commitments =
    commitments.copy(theirChanges = commitments.theirChanges.copy(proposed = commitments.theirChanges.proposed :+ proposal))

  def sendAdd(commitments: Commitments, cmd: CMD_ADD_HTLC): (Commitments, UpdateAddHtlc) = {
    // our available funds as seen by them, including all pending changes
    val reduced = Helpers.reduce(commitments.theirCommit.spec, commitments.theirChanges.acked, commitments.ourChanges.proposed)
    // a node cannot spend pending incoming htlcs
    val available = reduced.amount_them_msat
    if (cmd.amountMsat > available) {
      throw new RuntimeException(s"insufficient funds (available=$available msat)")
    } else {
      val id = cmd.id.getOrElse(commitments.ourCurrentHtlcId + 1)
      val add: UpdateAddHtlc = ??? //AddHtlc(id, cmd.amountMsat, cmd.rHash, cmd.expiry, routing(ByteString.copyFrom(cmd.payment_route.toByteArray)))
      val commitments1 = addOurProposal(commitments, add).copy(ourCurrentHtlcId = id)
      (commitments1, add)
    }
  }

  def receiveAdd(commitments: Commitments, add: UpdateAddHtlc): Commitments = {
    // their available funds as seen by us, including all pending changes
    val reduced = Helpers.reduce(commitments.ourCommit.spec, commitments.ourChanges.acked, commitments.theirChanges.proposed)
    // a node cannot spend pending incoming htlcs
    val available = reduced.amount_them_msat
    if (add.amountMsat > available) {
      throw new RuntimeException("Insufficient funds")
    } else {
      // TODO: nodeIds are ignored
      addTheirProposal(commitments, add)
    }
  }

  def sendFulfill(commitments: Commitments, cmd: CMD_FULFILL_HTLC, channelId: Long): (Commitments, UpdateFulfillHtlc) = {
    commitments.ourCommit.spec.htlcs.collectFirst { case u: Htlc if u.add.id == cmd.id => u.add } match {
      case Some(htlc) if htlc.paymentHash == sha256(cmd.r) =>
        val fulfill = UpdateFulfillHtlc(channelId, cmd.id, cmd.r)
        val commitments1 = addOurProposal(commitments, fulfill)
        (commitments1, fulfill)
      case Some(htlc) => throw new RuntimeException(s"invalid htlc preimage for htlc id=${cmd.id}")
      case None => throw new RuntimeException(s"unknown htlc id=${cmd.id}")
    }
  }

  def receiveFulfill(commitments: Commitments, fulfill: UpdateFulfillHtlc): (Commitments, UpdateAddHtlc) = {
    commitments.theirCommit.spec.htlcs.collectFirst { case u: Htlc if u.add.id == fulfill.id => u.add } match {
      case Some(htlc) if htlc.paymentHash == sha256(fulfill.paymentPreimage) => (addTheirProposal(commitments, fulfill), htlc)
      case Some(htlc) => throw new RuntimeException(s"invalid htlc preimage for htlc id=${fulfill.id}")
      case None => throw new RuntimeException(s"unknown htlc id=${fulfill.id}") // TODO : we should fail the channel
    }
  }

  def sendFail(commitments: Commitments, cmd: CMD_FAIL_HTLC): (Commitments, UpdateFailHtlc) = {
    commitments.ourCommit.spec.htlcs.collectFirst { case u: Htlc if u.add.id == cmd.id => u } match {
      case Some(htlc) =>
        val fail: UpdateFailHtlc = ??? //UpdateFailHtlc(cmd.channelId, cmd.id, fail_reason(ByteString.copyFromUtf8(cmd.reason)))
        val commitments1 = addOurProposal(commitments, fail)
        (commitments1, fail)
      case None => throw new RuntimeException(s"unknown htlc id=${cmd.id}")
    }
  }

  def receiveFail(commitments: Commitments, fail: UpdateFailHtlc): (Commitments, UpdateAddHtlc) = {
    commitments.theirCommit.spec.htlcs.collectFirst { case u: Htlc if u.add.id == fail.id => u.add } match {
      case Some(htlc) => (addTheirProposal(commitments, fail), htlc)
      case None => throw new RuntimeException(s"unknown htlc id=${fail.id}") // TODO : we should fail the channel
    }
  }

  def weHaveChanges(commitments: Commitments): Boolean = commitments.theirChanges.acked.size > 0 || commitments.ourChanges.proposed.size > 0

  def theyHaveChanges(commitments: Commitments): Boolean = commitments.ourChanges.acked.size > 0 || commitments.theirChanges.proposed.size > 0

  def sendCommit(commitments: Commitments): (Commitments, CommitSig) = {
    import commitments._
    commitments.theirNextCommitInfo match {
      case Right(theirNextRevocationHash) if !weHaveChanges(commitments) =>
        throw new RuntimeException("cannot sign when there are no changes")
      case Right(theirNextRevocationHash) =>
        // sign all our proposals + their acked proposals
        // their commitment now includes all our changes  + their acked changes
        val spec = Helpers.reduce(theirCommit.spec, theirChanges.acked, ourChanges.proposed)
        val theirTxTemplate = Helpers.makeRemoteTxTemplate(localParams, remoteParams, ourCommit.publishableTx.txIn, theirNextRevocationHash, spec)
        val theirTx = theirTxTemplate.makeTx
        // don't sign if they don't get paid
        val commit: CommitSig = ??? /*if (theirTxTemplate.weHaveAnOutput) {
          val ourSig = Helpers.sign(localParams, remoteParams, anchorOutput.amount, theirTx)
          CommitSig(Some(ourSig))
        } else {
          CommitSig(None)
        }*/
        val commitments1 = commitments.copy(
          theirNextCommitInfo = Left(TheirCommit(theirCommit.index + 1, spec, theirTx.txid, theirNextRevocationHash)),
          ourChanges = ourChanges.copy(proposed = Nil, signed = ourChanges.proposed),
          theirChanges = theirChanges.copy(acked = Nil))
        (commitments1, commit)
      case Left(theirNextCommit) =>
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

    if (!theyHaveChanges(commitments))
      throw new RuntimeException("cannot sign when there are no changes")

    // check that their signature is valid
    // signatures are now optional in the commit message, and will be sent only if the other party is actually
    // receiving money i.e its commit tx has one output for them

    val spec = Helpers.reduce(ourCommit.spec, ourChanges.acked, theirChanges.proposed)
    val ourNextRevocationHash = Helpers.revocationHash(shaSeed, ourCommit.index + 1)
    val ourTxTemplate = Helpers.makeLocalTxTemplate(localParams, remoteParams, ourCommit.publishableTx.txIn, ourNextRevocationHash, spec)

    // this tx will NOT be signed if our output is empty
    val ourCommitTx: Transaction = ???/*commit.sig match {
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
    val ourRevocationPreimage = Helpers.revocationPreimage(shaSeed, ourCommit.index)
    val ourNextRevocationHash1 = Helpers.revocationHash(shaSeed, ourCommit.index + 2)
    val revocation: RevokeAndAck = ??? //RevokeAndAck(ourRevocationPreimage, ourNextRevocationHash1)

    // update our commitment data
    val ourCommit1 = ourCommit.copy(index = ourCommit.index + 1, spec, publishableTx = ourCommitTx)
    val ourChanges1 = ourChanges.copy(acked = Nil)
    val theirChanges1 = theirChanges.copy(proposed = Nil, acked = theirChanges.acked ++ theirChanges.proposed)
    val commitments1 = commitments.copy(ourCommit = ourCommit1, ourChanges = ourChanges1, theirChanges = theirChanges1)

    (commitments1, revocation)
  }

  def receiveRevocation(commitments: Commitments, revocation: RevokeAndAck): Commitments = {
    import commitments._
    // we receive a revocation because we just sent them a sig for their next commit tx
    theirNextCommitInfo match {
      case Left(theirNextCommit) if BinaryData(Crypto.sha256(revocation.perCommitmentSecret)) != BinaryData(theirCommit.theirRevocationHash) =>
        throw new RuntimeException("invalid preimage")
      case Left(theirNextCommit) =>
        // this is their revoked commit tx
        val theirTxTemplate = Helpers.makeRemoteTxTemplate(localParams, remoteParams, ourCommit.publishableTx.txIn, theirCommit.theirRevocationHash, theirCommit.spec)
        val theirTx = theirTxTemplate.makeTx
        val punishTx: Transaction = ??? //Helpers.claimRevokedCommitTx(theirTxTemplate, revocation.revocationPreimage, localParams.finalPrivKey)
        Transaction.correctlySpends(punishTx, Seq(theirTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        txDb.add(theirTx.txid, punishTx)

        commitments.copy(
          ourChanges = ourChanges.copy(signed = Nil, acked = ourChanges.acked ++ ourChanges.signed),
          theirCommit = theirNextCommit,
          theirNextCommitInfo = Right(revocation.nextPerCommitmentPoint),
          theirPreimages = commitments.theirPreimages.addHash(revocation.perCommitmentSecret, 0xFFFFFFFFFFFFFFFFL - commitments.theirCommit.index))
      case Right(_) =>
        throw new RuntimeException("received unexpected RevokeAndAck message")
    }
  }

  def makeOurTxTemplate(commitments: Commitments): CommitTxTemplate = {
    Helpers.makeLocalTxTemplate(commitments.localParams, commitments.remoteParams, commitments.ourCommit.publishableTx.txIn,
      Helpers.revocationHash(commitments.shaSeed, commitments.ourCommit.index), commitments.ourCommit.spec)
  }

  def makeTheirTxTemplate(commitments: Commitments): CommitTxTemplate = {
    commitments.theirNextCommitInfo match {
      case Left(theirNextCommit) =>
        Helpers.makeRemoteTxTemplate(commitments.localParams, commitments.remoteParams, commitments.ourCommit.publishableTx.txIn,
          theirNextCommit.theirRevocationHash, theirNextCommit.spec)
      case Right(revocationHash) =>
        Helpers.makeRemoteTxTemplate(commitments.localParams, commitments.remoteParams, commitments.ourCommit.publishableTx.txIn,
          commitments.theirCommit.theirRevocationHash, commitments.theirCommit.spec)
    }
  }
}


