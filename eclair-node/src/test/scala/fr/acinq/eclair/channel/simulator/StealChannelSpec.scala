package fr.acinq.eclair.channel.simulator

import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.testkit.TestProbe
import fr.acinq.bitcoin.{BinaryData, Crypto}
import fr.acinq.eclair._
import fr.acinq.eclair.channel.{BITCOIN_FUNDING_SPENT, CLOSED, CLOSING, NEGOTIATING, _}
import lightning.locktime.Locktime.Blocks
import lightning.{locktime, update_add_htlc}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 26/04/2016.
  */
@RunWith(classOf[JUnitRunner])
class StealChannelSpec extends BaseChannelTestClass {

  test("steal revoked commit tx") { case (alice, bob, pipe) =>
    pipe !(alice, bob) // this starts the communication between alice and bob

    within(30 seconds) {

      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)

      val R: BinaryData = "0102030405060708010203040506070801020304050607080102030405060708"
      val H = Crypto.sha256(R)

      alice ! CMD_ADD_HTLC(60000000, H, 4)
      alice ! CMD_SIGN
      Thread.sleep(500)
      bob ! CMD_SIGN
      Thread.sleep(500)

      val commitTx = (alice.stateData: @unchecked) match {
        case d: DATA_NORMAL => d.commitments.ourCommit.publishableTx
      }

      bob ! CMD_FULFILL_HTLC(1, R)
      bob ! CMD_SIGN
      Thread.sleep(500)

      alice ! CMD_SIGN
      Thread.sleep(500)

      bob !(BITCOIN_FUNDING_SPENT, commitTx)
      awaitCond(bob.stateName == CLOSING)
    }
  }
}
