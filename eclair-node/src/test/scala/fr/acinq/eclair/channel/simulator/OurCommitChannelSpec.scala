package fr.acinq.eclair.channel.simulator

import fr.acinq.bitcoin.{BinaryData, Crypto}
import fr.acinq.eclair.channel._
import fr.acinq.eclair._
import lightning.locktime
import lightning.locktime.Locktime.Blocks
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 04/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class OurCommitChannelSpec extends BaseChannelTestClass {

  test("steal revoked commit tx") { case (alice, bob, pipe) =>
    pipe ! (alice, bob) // this starts the communication between alice and bob

    within(30 seconds) {

      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)

      val R: BinaryData = "0102030405060708010203040506070801020304050607080102030405060708"
      val H = Crypto.sha256(R)

      alice ! CMD_ADD_HTLC(60000000, H, locktime(Blocks(400)))
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

      bob ! (BITCOIN_FUNDING_SPENT, commitTx)
      awaitCond(bob.stateName == CLOSED)
    }
  }

}
