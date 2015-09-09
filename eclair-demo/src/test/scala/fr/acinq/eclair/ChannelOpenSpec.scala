package fr.acinq.eclair

import fr.acinq.bitcoin._
import fr.acinq.lightning._
import lightning._

/**
 * Created by PM on 02/09/2015.
 */
class ChannelOpenSpec extends TestHelper() {

  "Node" must {

    "successfuly open a channel in ANCHOR_NOINPUT mode" in {
      reachState_NOANCHOR(NORMAL_LOWPRIO)
    }

    "successfuly open a channel in ANCHOR_WITHINPUT mode" in {
      reachState_WITHANCHOR(NORMAL_HIGHPRIO)
    }

    "handle CMD_CLOSE in OPEN_WAIT_FOR_OPEN_NOANCHOR" in {
      val (node, _) = reachState_NOANCHOR(OPEN_WAIT_FOR_OPEN_NOANCHOR)
      node ! CMD_CLOSE(0)
      node ! CMD_GETSTATE
      expectMsg(CLOSED)
    }

    "handle CMD_CLOSE in OPEN_WAIT_FOR_OPEN_WITHANCHOR" in {
      val (node, _) = reachState_WITHANCHOR(OPEN_WAIT_FOR_OPEN_WITHANCHOR)
      node ! CMD_CLOSE(0)
      node ! CMD_GETSTATE
      expectMsg(CLOSED)
    }

    "handle CMD_CLOSE in OPEN_WAIT_FOR_ANCHOR" in {
      val (node, _) = reachState_NOANCHOR(OPEN_WAIT_FOR_ANCHOR)
      node ! CMD_CLOSE(0)
      node ! CMD_GETSTATE
      expectMsg(CLOSED)
    }

    "handle CMD_CLOSE in OPEN_WAIT_FOR_COMMIT_SIG" in {
      val (node, _) = reachState_WITHANCHOR(OPEN_WAIT_FOR_COMMIT_SIG)
      node ! CMD_CLOSE(0)
      node ! CMD_GETSTATE
      expectMsg(CLOSED)
    }

    "handle CMD_CLOSE in OPEN_WAITING_THEIRANCHOR" in {
      val (node, ChannelDesc(Some(ourParams), Some(theirParams), Some(Commitment(_, ourCommitTx, state, _)))) = reachState_NOANCHOR(OPEN_WAITING_THEIRANCHOR)
      node ! CMD_CLOSE(0)
      expectMsgClass(classOf[close_channel])
      node ! CMD_GETSTATE
      expectMsg(WAIT_FOR_CLOSE_COMPLETE)
      // the only difference between their final tx and ours is the order of the outputs, because state is symmetric
      val theirFinalTx = makeFinalTx(ourCommitTx.txIn, theirParams.finalPubKey, ourFinalPubKey, state.reverse)
      val ourFinalSigForThem = bin2signature(Transaction.signInput(theirFinalTx, 0, multiSig2of2(ourCommitPubKey, theirParams.commitPubKey), SIGHASH_ALL, ourParams.commitPrivKey))
      node ! close_channel_complete(ourFinalSigForThem)
      expectMsgClass(classOf[close_channel_ack])
      expectMsgClass(classOf[Watch])
      expectMsgClass(classOf[Publish])
      node ! CMD_GETSTATE
      expectMsg(CLOSE_WAIT_CLOSE)
      node ! BITCOIN_CLOSE_DONE
      node ! CMD_GETSTATE
      expectMsg(CLOSED)
    }

    "handle CMD_CLOSE in OPEN_WAITING_OURANCHOR" in {
      val (node, ChannelDesc(Some(ourParams), Some(theirParams), Some(Commitment(_, ourCommitTx, state, _)))) = reachState_WITHANCHOR(OPEN_WAITING_OURANCHOR)
      node ! CMD_CLOSE(0)
      expectMsgClass(classOf[close_channel])
      node ! CMD_GETSTATE
      expectMsg(WAIT_FOR_CLOSE_COMPLETE)
      // the only difference between their final tx and ours is the order of the outputs, because state is symmetric
      val theirFinalTx = makeFinalTx(ourCommitTx.txIn, theirParams.finalPubKey, ourFinalPubKey, state.reverse)
      val ourFinalSigForThem = bin2signature(Transaction.signInput(theirFinalTx, 0, multiSig2of2(ourCommitPubKey, theirParams.commitPubKey), SIGHASH_ALL, ourParams.commitPrivKey))
      node ! close_channel_complete(ourFinalSigForThem)
      expectMsgClass(classOf[close_channel_ack])
      expectMsgClass(classOf[Watch])
      expectMsgClass(classOf[Publish])
      node ! CMD_GETSTATE
      expectMsg(CLOSE_WAIT_CLOSE)
      node ! BITCOIN_CLOSE_DONE
      node ! CMD_GETSTATE
      expectMsg(CLOSED)
    }

    "handle CMD_CLOSE in OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR" in {
      val (node, ChannelDesc(Some(ourParams), Some(theirParams), Some(Commitment(_, ourCommitTx, state, _)))) = reachState_NOANCHOR(OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR)
      node ! CMD_CLOSE(0)
      expectMsgClass(classOf[close_channel])
      node ! CMD_GETSTATE
      expectMsg(WAIT_FOR_CLOSE_COMPLETE)
      // the only difference between their final tx and ours is the order of the outputs, because state is symmetric
      val theirFinalTx = makeFinalTx(ourCommitTx.txIn, theirParams.finalPubKey, ourFinalPubKey, state.reverse)
      val ourFinalSigForThem = bin2signature(Transaction.signInput(theirFinalTx, 0, multiSig2of2(ourCommitPubKey, theirParams.commitPubKey), SIGHASH_ALL, ourParams.commitPrivKey))
      node ! close_channel_complete(ourFinalSigForThem)
      expectMsgClass(classOf[close_channel_ack])
      expectMsgClass(classOf[Watch])
      expectMsgClass(classOf[Publish])
      node ! CMD_GETSTATE
      expectMsg(CLOSE_WAIT_CLOSE)
      node ! BITCOIN_CLOSE_DONE
      node ! CMD_GETSTATE
      expectMsg(CLOSED)
    }

    "handle CMD_CLOSE in OPEN_WAIT_FOR_COMPLETE_OURANCHOR" in {
      val (node, ChannelDesc(Some(ourParams), Some(theirParams), Some(Commitment(_, ourCommitTx, state, _)))) = reachState_WITHANCHOR(OPEN_WAIT_FOR_COMPLETE_OURANCHOR)
      node ! CMD_CLOSE(0)
      expectMsgClass(classOf[close_channel])
      node ! CMD_GETSTATE
      expectMsg(WAIT_FOR_CLOSE_COMPLETE)
      // the only difference between their final tx and ours is the order of the outputs, because state is symmetric
      val theirFinalTx = makeFinalTx(ourCommitTx.txIn, theirParams.finalPubKey, ourFinalPubKey, state.reverse)
      val ourFinalSigForThem = bin2signature(Transaction.signInput(theirFinalTx, 0, multiSig2of2(ourCommitPubKey, theirParams.commitPubKey), SIGHASH_ALL, ourParams.commitPrivKey))
      node ! close_channel_complete(ourFinalSigForThem)
      expectMsgClass(classOf[close_channel_ack])
      expectMsgClass(classOf[Watch])
      expectMsgClass(classOf[Publish])
      node ! CMD_GETSTATE
      expectMsg(CLOSE_WAIT_CLOSE)
      node ! BITCOIN_CLOSE_DONE
      node ! CMD_GETSTATE
      expectMsg(CLOSED)
    }

    "handle PKT_CLOSE in OPEN_WAITING_THEIRANCHOR" in {
      val (node, ChannelDesc(Some(ourParams), Some(theirParams), Some(Commitment(_, ourCommitTx, state, _)))) = reachState_NOANCHOR(OPEN_WAITING_THEIRANCHOR)
      // the only difference between their final tx and ours is the order of the outputs, because state is symmetric
      val theirFinalTx = makeFinalTx(ourCommitTx.txIn, theirParams.finalPubKey, ourFinalPubKey, state.reverse)
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
      node ! BITCOIN_CLOSE_DONE
      node ! CMD_GETSTATE
      expectMsg(CLOSED)
    }

    "handle PKT_CLOSE in OPEN_WAITING_OURANCHOR" in {
      val (node, ChannelDesc(Some(ourParams), Some(theirParams), Some(Commitment(_, ourCommitTx, state, _)))) = reachState_WITHANCHOR(OPEN_WAITING_OURANCHOR)
      // the only difference between their final tx and ours is the order of the outputs, because state is symmetric
      val theirFinalTx = makeFinalTx(ourCommitTx.txIn, theirParams.finalPubKey, ourFinalPubKey, state.reverse)
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
      node ! BITCOIN_CLOSE_DONE
      node ! CMD_GETSTATE
      expectMsg(CLOSED)
    }

    "handle PKT_CLOSE in OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR" in {
      val (node, ChannelDesc(Some(ourParams), Some(theirParams), Some(Commitment(_, ourCommitTx, state, _)))) = reachState_NOANCHOR(OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR)
      // the only difference between their final tx and ours is the order of the outputs, because state is symmetric
      val theirFinalTx = makeFinalTx(ourCommitTx.txIn, theirParams.finalPubKey, ourFinalPubKey, state.reverse)
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
      node ! BITCOIN_CLOSE_DONE
      node ! CMD_GETSTATE
      expectMsg(CLOSED)
    }

    "handle PKT_CLOSE in OPEN_WAIT_FOR_COMPLETE_OURANCHOR" in {
      val (node, ChannelDesc(Some(ourParams), Some(theirParams), Some(Commitment(_, ourCommitTx, state, _)))) = reachState_WITHANCHOR(OPEN_WAIT_FOR_COMPLETE_OURANCHOR)
      // the only difference between their final tx and ours is the order of the outputs, because state is symmetric
      val theirFinalTx = makeFinalTx(ourCommitTx.txIn, theirParams.finalPubKey, ourFinalPubKey, state.reverse)
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
      node ! BITCOIN_CLOSE_DONE
      node ! CMD_GETSTATE
      expectMsg(CLOSED)
    }

  }

}
