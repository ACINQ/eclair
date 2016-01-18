package fr.acinq.eclair


import fr.acinq.bitcoin._
import fr.acinq.lightning._
import lightning.locktime.Locktime.Blocks
import lightning.update_decline_htlc.Reason.CannotRoute
import lightning._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

/**
  * Created by PM on 05/01/2016.
  */
@RunWith(classOf[JUnitRunner])
class CornerCasesSpec extends TestHelper {

  "Node" must {

    /*"handle a reorg where we were waiting for a close on the old chain to be buried enough and they commited a revoked tx on the new main chain" in {
      val (node, ChannelDesc(Some(ourParams), Some(theirParams), Some(commitment))) = reachState_WITHANCHOR(NORMAL_HIGHPRIO)
      // the only difference between their final tx and ours is the order of the outputs, because state is symmetric
      val theirFinalTx = makeFinalTx(commitment.tx.txIn, theirParams.finalPubKey, ourFinalPubKey, commitment.state.reverse)
      val ourFinalSigForThem = bin2signature(Transaction.signInput(theirFinalTx, 0, multiSig2of2(ourCommitPubKey, theirParams.commitPubKey), SIGHASH_ALL, ourParams.commitPrivKey))
      node ! close_channel(ourFinalSigForThem, 0)
      expectMsgClass(classOf[Watch])
      expectMsgClass(classOf[Publish])
      expectMsgClass(classOf[close_channel_complete])
      node ! CMD_GETSTATE
      expectMsg(WAIT_FOR_CLOSE_ACK)
      node ! close_channel_ack()
      node ! CMD_GETSTATE
      expectMsg(CLOSE_WAIT_CLOSE)

      // reorg ! they spent a revoked tx
      node ! BITCOIN_ANCHOR_OTHERSPEND

      // we steal and close
      node ! CMD_GETSTATE
      expectMsg(CLOSE_WAIT_STEAL_CLOSE)
      node ! BITCOIN_STEAL_DONE
      node ! CMD_GETSTATE
      expectMsg(CLOSED)
    }*/
  }
}