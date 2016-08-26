package fr.acinq.eclair.channel.simulator.states

import akka.testkit.{TestFSMRef, TestKitBase, TestProbe}
import fr.acinq.bitcoin.Crypto
import fr.acinq.eclair.channel._
import fr.acinq.eclair._
import lightning.locktime.Locktime.Blocks
import lightning._

import scala.util.Random

/**
  * Created by PM on 23/08/2016.
  */
trait StateTestsHelperMethods extends TestKitBase {

  def addHtlc(amountMsat: Int, s: TestFSMRef[State, Data, Channel], r: TestFSMRef[State, Data, Channel], s2r: TestProbe, r2s: TestProbe): (rval, update_add_htlc) = {
    val rand = new Random()
    val R = rval(rand.nextInt(), rand.nextInt(), rand.nextInt(), rand.nextInt())
    val H: sha256_hash = Crypto.sha256(R)
    val sender = TestProbe()
    sender.send(s, CMD_ADD_HTLC(amountMsat, H, locktime(Blocks(1440))))
    sender.expectMsg("ok")
    val htlc = s2r.expectMsgType[update_add_htlc]
    s2r.forward(r)
    awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.theirChanges.proposed.contains(htlc))
    (R, htlc)
  }

  def fulfillHtlc(id: Long, R: rval, s: TestFSMRef[State, Data, Channel], r: TestFSMRef[State, Data, Channel], s2r: TestProbe, r2s: TestProbe) = {
    val sender = TestProbe()
    sender.send(s, CMD_FULFILL_HTLC(id, R))
    sender.expectMsg("ok")
    val fulfill = s2r.expectMsgType[update_fulfill_htlc]
    s2r.forward(r)
    awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.theirChanges.proposed.contains(fulfill))
  }

  def sign(s: TestFSMRef[State, Data, Channel], r: TestFSMRef[State, Data, Channel], s2r: TestProbe, r2s: TestProbe) = {
    val sender = TestProbe()
    val rCommitIndex = r.stateData.asInstanceOf[HasCommitments].commitments.ourCommit.index
    sender.send(s, CMD_SIGN)
    sender.expectMsg("ok")
    s2r.expectMsgType[update_commit]
    s2r.forward(r)
    r2s.expectMsgType[update_revocation]
    r2s.forward(s)
    awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.ourCommit.index == rCommitIndex + 1)
  }

}
