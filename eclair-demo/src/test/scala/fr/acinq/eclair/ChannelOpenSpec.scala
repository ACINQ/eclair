package fr.acinq.eclair

import akka.actor.{Props, ActorSystem}
import akka.testkit.{TestKit, ImplicitSender}
import com.google.protobuf.ByteString
import fr.acinq.bitcoin._
import fr.acinq.lightning._
import lightning.locktime.Locktime.Blocks
import lightning._
import lightning.open_channel.anchor_offer.{WONT_CREATE_ANCHOR, WILL_CREATE_ANCHOR}
import org.bouncycastle.util.encoders.Hex
import org.scalatest.{BeforeAndAfterAll, WordSpecLike, Matchers}

/**
 * Created by PM on 02/09/2015.
 */
class ChannelOpenSpec(_system: ActorSystem) extends TestKit(_system) with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("MySpec"))


  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val anchorInput = AnchorInput(100100000L, OutPoint(Hex.decode("7727730d21428276a4d6b0e16f3a3e6f3a07a07dc67151e6a88d4a8c3e8edb24").reverse, 1), SignData("76a914e093fbc19866b98e0fbc25d79d0ad0f0375170af88ac", Base58Check.decode("cU1YgK56oUKAtV6XXHZeJQjEx1KGXkZS1pGiKpyW4mUyKYFJwWFg")._2))

  val bob_commit_priv = Base58Check.decode("cSUwLtdZ2tht9ZmHhdQue48pfe7tY2GT2TGWJDtjoZgo6FHrubGk")._2
  val bob_final_priv = Base58Check.decode("cPR7ZgXpUaDPA3GwGceMDS5pfnSm955yvks3yELf3wMJwegsdGTg")._2

  val our_delay = locktime(Blocks(1))
  val our_mindepth = 2
  val our_commitment_fee = 100000
  val commitPrivKey = Base58Check.decode("cQPmcNr6pwBQPyGfab3SksE9nTCtx9ism9T4dkS9dETNU2KKtJHk")._2
  val our_commitkey_pub = bitcoin_pubkey(ByteString.copyFrom(Crypto.publicKeyFromPrivateKey(commitPrivKey.key.toByteArray)))
  val our_finalkey_priv = Base58Check.decode("cUrAtLtV7GGddqdkhUxnbZVDWGJBTducpPoon3eKp9Vnr1zxs6BG")._2
  val our_finalkey_pub = bitcoin_pubkey(ByteString.copyFrom(Crypto.publicKeyFromPrivateKey(our_finalkey_priv.key.toByteArray)))

  val ourParams = ChannelParams(our_delay, our_commitkey_pub, our_finalkey_pub, our_mindepth, our_commitment_fee)

  "Node" must {

    "successfuly open a channel in ANCHOR_NOINPUT mode" in {
      val node = system.actorOf(Props(new Node(self, bob_commit_priv, bob_final_priv, 2, None)))
      node ! INPUT_NONE
      val their_open_channel = expectMsgClass(classOf[open_channel])
      val theirParams = ChannelParams(their_open_channel.delay, their_open_channel.commitKey, their_open_channel.finalKey, their_open_channel.minDepth.get, their_open_channel.commitmentFee)
      val theirRevocationHash = their_open_channel.revocationHash
      val ourRevocationHashPreimage = sha256_hash(4, 3, 2, 1)
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      node ! open_channel(ourParams.delay, ourRevocationHash, ourParams.commitKey, ourParams.finalKey, WILL_CREATE_ANCHOR, Some(ourParams.minDepth), ourParams.commitmentFee)
      val anchorTx = makeAnchorTx(ourParams.commitKey, theirParams.commitKey, anchorInput.amount, anchorInput.previousTxOutput, anchorInput.signData)
      val anchorOutputIndex = 0
      // we fund the channel with the anchor tx, so the money is ours
      val state = ChannelState(them = ChannelOneSide(0, 0, Seq()), us = ChannelOneSide(anchorInput.amount - our_commitment_fee, 0, Seq()))
      // we build our commitment tx, leaving it unsigned
      val ourCommitTx = makeCommitTx(ourParams.finalKey, theirParams.finalKey, theirParams.delay, anchorTx.hash, anchorOutputIndex, Crypto.sha256(ourRevocationHashPreimage), state)
      // then we build their commitment tx and sign it
      val theirCommitTx = makeCommitTx(theirParams.finalKey, ourParams.finalKey, ourParams.delay, anchorTx.hash, anchorOutputIndex, theirRevocationHash, state.reverse)
      val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, commitPrivKey))
      node ! open_anchor(anchorTx.hash, 0, anchorInput.amount, ourSigForThem)
      expectMsgClass(classOf[open_commit_sig])
      expectMsgClass(classOf[Watch])
      node ! BITCOIN_ANCHOR_DEPTHOK
      expectMsgClass(classOf[open_complete])
      node ! open_complete(None)
      node ! CMD_GETSTATE
      expectMsg(NORMAL_LOWPRIO)
    }

    "successfuly open a channel in ANCHOR_WITHINPUT mode" in {
      val node = system.actorOf(Props(new Node(self, bob_commit_priv, bob_final_priv, 2, Some(anchorInput))))
      node ! INPUT_NONE
      val their_open_channel = expectMsgClass(classOf[open_channel])
      val theirParams = ChannelParams(their_open_channel.delay, their_open_channel.commitKey, their_open_channel.finalKey, their_open_channel.minDepth.get, their_open_channel.commitmentFee)
      val theirRevocationHash = their_open_channel.revocationHash
      val ourRevocationHashPreimage = sha256_hash(4, 3, 2, 1)
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      node ! open_channel(ourParams.delay, ourRevocationHash, ourParams.commitKey, ourParams.finalKey, WONT_CREATE_ANCHOR, Some(ourParams.minDepth), ourParams.commitmentFee)
      val their_open_anchor = expectMsgClass(classOf[open_anchor])
      // we fund the channel with the anchor tx, so the money is ours
      val state = ChannelState(them = ChannelOneSide(their_open_anchor.amount - our_commitment_fee, 0, Seq()), us = ChannelOneSide(0, 0, Seq()))
      // we build our commitment tx, leaving it unsigned
      val ourCommitTx = makeCommitTx(ourParams.finalKey, theirParams.finalKey, theirParams.delay, their_open_anchor.txid, their_open_anchor.outputIndex, Crypto.sha256(ourRevocationHashPreimage), state)
      // then we build their commitment tx and sign it
      val theirCommitTx = makeCommitTx(theirParams.finalKey, ourParams.finalKey, ourParams.delay, their_open_anchor.txid, their_open_anchor.outputIndex, theirRevocationHash, state.reverse)
      val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, commitPrivKey))
      node ! open_commit_sig(ourSigForThem)
      expectMsgClass(classOf[Watch])
      expectMsgClass(classOf[Publish])
      node ! BITCOIN_ANCHOR_DEPTHOK
      expectMsgClass(classOf[open_complete])
      node ! open_complete(None)
      node ! CMD_GETSTATE
      expectMsg(NORMAL_HIGHPRIO)
    }

    "handle CMD_CLOSE in OPEN_WAIT_FOR_OPEN_NOANCHOR" in {
      val node = system.actorOf(Props(new Node(self, bob_commit_priv, bob_final_priv, 2, None)))
      node ! INPUT_NONE
      expectMsgClass(classOf[open_channel])
      node ! CMD_GETSTATE
      expectMsg(OPEN_WAIT_FOR_OPEN_NOANCHOR)
      node ! CMD_CLOSE(0)
      node ! CMD_GETSTATE
      expectMsg(CLOSED)
    }

    "handle CMD_CLOSE in OPEN_WAIT_FOR_OPEN_WITHANCHOR" in {
      val node = system.actorOf(Props(new Node(self, bob_commit_priv, bob_final_priv, 2, Some(anchorInput))))
      node ! INPUT_NONE
      expectMsgClass(classOf[open_channel])
      node ! CMD_GETSTATE
      expectMsg(OPEN_WAIT_FOR_OPEN_WITHANCHOR)
      node ! CMD_CLOSE(0)
      node ! CMD_GETSTATE
      expectMsg(CLOSED)
    }

    "handle CMD_CLOSE in OPEN_WAIT_FOR_ANCHOR" in {
      val node = system.actorOf(Props(new Node(self, bob_commit_priv, bob_final_priv, 2, None)))
      node ! INPUT_NONE
      val their_open_channel = expectMsgClass(classOf[open_channel])
      val theirParams = ChannelParams(their_open_channel.delay, their_open_channel.commitKey, their_open_channel.finalKey, their_open_channel.minDepth.get, their_open_channel.commitmentFee)
      val theirRevocationHash = their_open_channel.revocationHash
      val ourRevocationHashPreimage = sha256_hash(4, 3, 2, 1)
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      node ! open_channel(ourParams.delay, ourRevocationHash, ourParams.commitKey, ourParams.finalKey, WILL_CREATE_ANCHOR, Some(ourParams.minDepth), ourParams.commitmentFee)
      node ! CMD_GETSTATE
      expectMsg(OPEN_WAIT_FOR_ANCHOR)
      node ! CMD_CLOSE(0)
      node ! CMD_GETSTATE
      expectMsg(CLOSED)
    }

    "handle CMD_CLOSE in OPEN_WAIT_FOR_COMMIT_SIG" in {
      val node = system.actorOf(Props(new Node(self, bob_commit_priv, bob_final_priv, 2, Some(anchorInput))))
      node ! INPUT_NONE
      val their_open_channel = expectMsgClass(classOf[open_channel])
      val theirParams = ChannelParams(their_open_channel.delay, their_open_channel.commitKey, their_open_channel.finalKey, their_open_channel.minDepth.get, their_open_channel.commitmentFee)
      val theirRevocationHash = their_open_channel.revocationHash
      val ourRevocationHashPreimage = sha256_hash(4, 3, 2, 1)
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      node ! open_channel(ourParams.delay, ourRevocationHash, ourParams.commitKey, ourParams.finalKey, WONT_CREATE_ANCHOR, Some(ourParams.minDepth), ourParams.commitmentFee)
      val their_open_anchor = expectMsgClass(classOf[open_anchor])
      node ! CMD_GETSTATE
      expectMsg(OPEN_WAIT_FOR_COMMIT_SIG)
      node ! CMD_CLOSE(0)
      node ! CMD_GETSTATE
      expectMsg(CLOSED)
    }

    "handle CMD_CLOSE in OPEN_WAITING_THEIRANCHOR" in {
      val node = system.actorOf(Props(new Node(self, bob_commit_priv, bob_final_priv, 2, None)))
      node ! INPUT_NONE
      val their_open_channel = expectMsgClass(classOf[open_channel])
      val theirParams = ChannelParams(their_open_channel.delay, their_open_channel.commitKey, their_open_channel.finalKey, their_open_channel.minDepth.get, their_open_channel.commitmentFee)
      val theirRevocationHash = their_open_channel.revocationHash
      val ourRevocationHashPreimage = sha256_hash(4, 3, 2, 1)
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      node ! open_channel(ourParams.delay, ourRevocationHash, ourParams.commitKey, ourParams.finalKey, WILL_CREATE_ANCHOR, Some(ourParams.minDepth), ourParams.commitmentFee)
      val anchorTx = makeAnchorTx(ourParams.commitKey, theirParams.commitKey, anchorInput.amount, anchorInput.previousTxOutput, anchorInput.signData)
      val anchorOutputIndex = 0
      // we fund the channel with the anchor tx, so the money is ours
      val state = ChannelState(them = ChannelOneSide(0, 0, Seq()), us = ChannelOneSide(anchorInput.amount - our_commitment_fee, 0, Seq()))
      // we build our commitment tx, leaving it unsigned
      val ourCommitTx = makeCommitTx(ourParams.finalKey, theirParams.finalKey, theirParams.delay, anchorTx.hash, anchorOutputIndex, Crypto.sha256(ourRevocationHashPreimage), state)
      // then we build their commitment tx and sign it
      val theirCommitTx = makeCommitTx(theirParams.finalKey, ourParams.finalKey, ourParams.delay, anchorTx.hash, anchorOutputIndex, theirRevocationHash, state.reverse)
      val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, commitPrivKey))
      node ! open_anchor(anchorTx.hash, 0, anchorInput.amount, ourSigForThem)
      expectMsgClass(classOf[open_commit_sig])
      expectMsgClass(classOf[Watch])
      node ! CMD_GETSTATE
      expectMsg(OPEN_WAITING_THEIRANCHOR)
      node ! CMD_CLOSE(0)
      expectMsgClass(classOf[close_channel])
      node ! CMD_GETSTATE
      expectMsg(WAIT_FOR_CLOSE_COMPLETE)
      // the only difference between their final tx and ours is the order of the outputs, because state is symmetric
      val theirFinalTx = makeFinalTx(ourCommitTx.txIn, theirParams.finalKey, ourParams.finalKey, state.reverse)
      val ourFinalSigForThem = bin2signature(Transaction.signInput(theirFinalTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, commitPrivKey))
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
      val node = system.actorOf(Props(new Node(self, bob_commit_priv, bob_final_priv, 2, Some(anchorInput))))
      node ! INPUT_NONE
      val their_open_channel = expectMsgClass(classOf[open_channel])
      val theirParams = ChannelParams(their_open_channel.delay, their_open_channel.commitKey, their_open_channel.finalKey, their_open_channel.minDepth.get, their_open_channel.commitmentFee)
      val theirRevocationHash = their_open_channel.revocationHash
      val ourRevocationHashPreimage = sha256_hash(4, 3, 2, 1)
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      node ! open_channel(ourParams.delay, ourRevocationHash, ourParams.commitKey, ourParams.finalKey, WONT_CREATE_ANCHOR, Some(ourParams.minDepth), ourParams.commitmentFee)
      val their_open_anchor = expectMsgClass(classOf[open_anchor])
      // we fund the channel with the anchor tx, so the money is ours
      val state = ChannelState(them = ChannelOneSide(their_open_anchor.amount - our_commitment_fee, 0, Seq()), us = ChannelOneSide(0, 0, Seq()))
      // we build our commitment tx, leaving it unsigned
      val ourCommitTx = makeCommitTx(ourParams.finalKey, theirParams.finalKey, theirParams.delay, their_open_anchor.txid, their_open_anchor.outputIndex, Crypto.sha256(ourRevocationHashPreimage), state)
      // then we build their commitment tx and sign it
      val theirCommitTx = makeCommitTx(theirParams.finalKey, ourParams.finalKey, ourParams.delay, their_open_anchor.txid, their_open_anchor.outputIndex, theirRevocationHash, state.reverse)
      val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, commitPrivKey))
      node ! open_commit_sig(ourSigForThem)
      expectMsgClass(classOf[Watch])
      expectMsgClass(classOf[Publish])
      node ! CMD_GETSTATE
      expectMsg(OPEN_WAITING_OURANCHOR)
      node ! CMD_CLOSE(0)
      expectMsgClass(classOf[close_channel])
      node ! CMD_GETSTATE
      expectMsg(WAIT_FOR_CLOSE_COMPLETE)
      // the only difference between their final tx and ours is the order of the outputs, because state is symmetric
      val theirFinalTx = makeFinalTx(ourCommitTx.txIn, theirParams.finalKey, ourParams.finalKey, state.reverse)
      val ourFinalSigForThem = bin2signature(Transaction.signInput(theirFinalTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, commitPrivKey))
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
      val node = system.actorOf(Props(new Node(self, bob_commit_priv, bob_final_priv, 2, None)))
      node ! INPUT_NONE
      val their_open_channel = expectMsgClass(classOf[open_channel])
      val theirParams = ChannelParams(their_open_channel.delay, their_open_channel.commitKey, their_open_channel.finalKey, their_open_channel.minDepth.get, their_open_channel.commitmentFee)
      val theirRevocationHash = their_open_channel.revocationHash
      val ourRevocationHashPreimage = sha256_hash(4, 3, 2, 1)
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      node ! open_channel(ourParams.delay, ourRevocationHash, ourParams.commitKey, ourParams.finalKey, WILL_CREATE_ANCHOR, Some(ourParams.minDepth), ourParams.commitmentFee)
      val anchorTx = makeAnchorTx(ourParams.commitKey, theirParams.commitKey, anchorInput.amount, anchorInput.previousTxOutput, anchorInput.signData)
      val anchorOutputIndex = 0
      // we fund the channel with the anchor tx, so the money is ours
      val state = ChannelState(them = ChannelOneSide(0, 0, Seq()), us = ChannelOneSide(anchorInput.amount - our_commitment_fee, 0, Seq()))
      // we build our commitment tx, leaving it unsigned
      val ourCommitTx = makeCommitTx(ourParams.finalKey, theirParams.finalKey, theirParams.delay, anchorTx.hash, anchorOutputIndex, Crypto.sha256(ourRevocationHashPreimage), state)
      // then we build their commitment tx and sign it
      val theirCommitTx = makeCommitTx(theirParams.finalKey, ourParams.finalKey, ourParams.delay, anchorTx.hash, anchorOutputIndex, theirRevocationHash, state.reverse)
      val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, commitPrivKey))
      node ! open_anchor(anchorTx.hash, 0, anchorInput.amount, ourSigForThem)
      expectMsgClass(classOf[open_commit_sig])
      expectMsgClass(classOf[Watch])
      node ! BITCOIN_ANCHOR_DEPTHOK
      expectMsgClass(classOf[open_complete])
      node ! CMD_GETSTATE
      expectMsg(OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR)
      node ! CMD_CLOSE(0)
      expectMsgClass(classOf[close_channel])
      node ! CMD_GETSTATE
      expectMsg(WAIT_FOR_CLOSE_COMPLETE)
      // the only difference between their final tx and ours is the order of the outputs, because state is symmetric
      val theirFinalTx = makeFinalTx(ourCommitTx.txIn, theirParams.finalKey, ourParams.finalKey, state.reverse)
      val ourFinalSigForThem = bin2signature(Transaction.signInput(theirFinalTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, commitPrivKey))
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
      val node = system.actorOf(Props(new Node(self, bob_commit_priv, bob_final_priv, 2, Some(anchorInput))))
      node ! INPUT_NONE
      val their_open_channel = expectMsgClass(classOf[open_channel])
      val theirParams = ChannelParams(their_open_channel.delay, their_open_channel.commitKey, their_open_channel.finalKey, their_open_channel.minDepth.get, their_open_channel.commitmentFee)
      val theirRevocationHash = their_open_channel.revocationHash
      val ourRevocationHashPreimage = sha256_hash(4, 3, 2, 1)
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      node ! open_channel(ourParams.delay, ourRevocationHash, ourParams.commitKey, ourParams.finalKey, WONT_CREATE_ANCHOR, Some(ourParams.minDepth), ourParams.commitmentFee)
      val their_open_anchor = expectMsgClass(classOf[open_anchor])
      // we fund the channel with the anchor tx, so the money is ours
      val state = ChannelState(them = ChannelOneSide(their_open_anchor.amount - our_commitment_fee, 0, Seq()), us = ChannelOneSide(0, 0, Seq()))
      // we build our commitment tx, leaving it unsigned
      val ourCommitTx = makeCommitTx(ourParams.finalKey, theirParams.finalKey, theirParams.delay, their_open_anchor.txid, their_open_anchor.outputIndex, Crypto.sha256(ourRevocationHashPreimage), state)
      // then we build their commitment tx and sign it
      val theirCommitTx = makeCommitTx(theirParams.finalKey, ourParams.finalKey, ourParams.delay, their_open_anchor.txid, their_open_anchor.outputIndex, theirRevocationHash, state.reverse)
      val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, commitPrivKey))
      node ! open_commit_sig(ourSigForThem)
      expectMsgClass(classOf[Watch])
      expectMsgClass(classOf[Publish])
      node ! BITCOIN_ANCHOR_DEPTHOK
      expectMsgClass(classOf[open_complete])
      node ! CMD_GETSTATE
      expectMsg(OPEN_WAIT_FOR_COMPLETE_OURANCHOR)
      node ! CMD_CLOSE(0)
      expectMsgClass(classOf[close_channel])
      node ! CMD_GETSTATE
      expectMsg(WAIT_FOR_CLOSE_COMPLETE)
      // the only difference between their final tx and ours is the order of the outputs, because state is symmetric
      val theirFinalTx = makeFinalTx(ourCommitTx.txIn, theirParams.finalKey, ourParams.finalKey, state.reverse)
      val ourFinalSigForThem = bin2signature(Transaction.signInput(theirFinalTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, commitPrivKey))
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
      val node = system.actorOf(Props(new Node(self, bob_commit_priv, bob_final_priv, 2, None)))
      node ! INPUT_NONE
      val their_open_channel = expectMsgClass(classOf[open_channel])
      val theirParams = ChannelParams(their_open_channel.delay, their_open_channel.commitKey, their_open_channel.finalKey, their_open_channel.minDepth.get, their_open_channel.commitmentFee)
      val theirRevocationHash = their_open_channel.revocationHash
      val ourRevocationHashPreimage = sha256_hash(4, 3, 2, 1)
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      node ! open_channel(ourParams.delay, ourRevocationHash, ourParams.commitKey, ourParams.finalKey, WILL_CREATE_ANCHOR, Some(ourParams.minDepth), ourParams.commitmentFee)
      val anchorTx = makeAnchorTx(ourParams.commitKey, theirParams.commitKey, anchorInput.amount, anchorInput.previousTxOutput, anchorInput.signData)
      val anchorOutputIndex = 0
      // we fund the channel with the anchor tx, so the money is ours
      val state = ChannelState(them = ChannelOneSide(0, 0, Seq()), us = ChannelOneSide(anchorInput.amount - our_commitment_fee, 0, Seq()))
      // we build our commitment tx, leaving it unsigned
      val ourCommitTx = makeCommitTx(ourParams.finalKey, theirParams.finalKey, theirParams.delay, anchorTx.hash, anchorOutputIndex, Crypto.sha256(ourRevocationHashPreimage), state)
      // then we build their commitment tx and sign it
      val theirCommitTx = makeCommitTx(theirParams.finalKey, ourParams.finalKey, ourParams.delay, anchorTx.hash, anchorOutputIndex, theirRevocationHash, state.reverse)
      val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, commitPrivKey))
      node ! open_anchor(anchorTx.hash, 0, anchorInput.amount, ourSigForThem)
      expectMsgClass(classOf[open_commit_sig])
      expectMsgClass(classOf[Watch])
      node ! CMD_GETSTATE
      expectMsg(OPEN_WAITING_THEIRANCHOR)
      // the only difference between their final tx and ours is the order of the outputs, because state is symmetric
      val theirFinalTx = makeFinalTx(ourCommitTx.txIn, theirParams.finalKey, ourParams.finalKey, state.reverse)
      val ourFinalSigForThem = bin2signature(Transaction.signInput(theirFinalTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, commitPrivKey))
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
      val node = system.actorOf(Props(new Node(self, bob_commit_priv, bob_final_priv, 2, Some(anchorInput))))
      node ! INPUT_NONE
      val their_open_channel = expectMsgClass(classOf[open_channel])
      val theirParams = ChannelParams(their_open_channel.delay, their_open_channel.commitKey, their_open_channel.finalKey, their_open_channel.minDepth.get, their_open_channel.commitmentFee)
      val theirRevocationHash = their_open_channel.revocationHash
      val ourRevocationHashPreimage = sha256_hash(4, 3, 2, 1)
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      node ! open_channel(ourParams.delay, ourRevocationHash, ourParams.commitKey, ourParams.finalKey, WONT_CREATE_ANCHOR, Some(ourParams.minDepth), ourParams.commitmentFee)
      val their_open_anchor = expectMsgClass(classOf[open_anchor])
      // we fund the channel with the anchor tx, so the money is ours
      val state = ChannelState(them = ChannelOneSide(their_open_anchor.amount - our_commitment_fee, 0, Seq()), us = ChannelOneSide(0, 0, Seq()))
      // we build our commitment tx, leaving it unsigned
      val ourCommitTx = makeCommitTx(ourParams.finalKey, theirParams.finalKey, theirParams.delay, their_open_anchor.txid, their_open_anchor.outputIndex, Crypto.sha256(ourRevocationHashPreimage), state)
      // then we build their commitment tx and sign it
      val theirCommitTx = makeCommitTx(theirParams.finalKey, ourParams.finalKey, ourParams.delay, their_open_anchor.txid, their_open_anchor.outputIndex, theirRevocationHash, state.reverse)
      val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, commitPrivKey))
      node ! open_commit_sig(ourSigForThem)
      expectMsgClass(classOf[Watch])
      expectMsgClass(classOf[Publish])
      node ! CMD_GETSTATE
      expectMsg(OPEN_WAITING_OURANCHOR)
      // the only difference between their final tx and ours is the order of the outputs, because state is symmetric
      val theirFinalTx = makeFinalTx(ourCommitTx.txIn, theirParams.finalKey, ourParams.finalKey, state.reverse)
      val ourFinalSigForThem = bin2signature(Transaction.signInput(theirFinalTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, commitPrivKey))
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
      val node = system.actorOf(Props(new Node(self, bob_commit_priv, bob_final_priv, 2, None)))
      node ! INPUT_NONE
      val their_open_channel = expectMsgClass(classOf[open_channel])
      val theirParams = ChannelParams(their_open_channel.delay, their_open_channel.commitKey, their_open_channel.finalKey, their_open_channel.minDepth.get, their_open_channel.commitmentFee)
      val theirRevocationHash = their_open_channel.revocationHash
      val ourRevocationHashPreimage = sha256_hash(4, 3, 2, 1)
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      node ! open_channel(ourParams.delay, ourRevocationHash, ourParams.commitKey, ourParams.finalKey, WILL_CREATE_ANCHOR, Some(ourParams.minDepth), ourParams.commitmentFee)
      val anchorTx = makeAnchorTx(ourParams.commitKey, theirParams.commitKey, anchorInput.amount, anchorInput.previousTxOutput, anchorInput.signData)
      val anchorOutputIndex = 0
      // we fund the channel with the anchor tx, so the money is ours
      val state = ChannelState(them = ChannelOneSide(0, 0, Seq()), us = ChannelOneSide(anchorInput.amount - our_commitment_fee, 0, Seq()))
      // we build our commitment tx, leaving it unsigned
      val ourCommitTx = makeCommitTx(ourParams.finalKey, theirParams.finalKey, theirParams.delay, anchorTx.hash, anchorOutputIndex, Crypto.sha256(ourRevocationHashPreimage), state)
      // then we build their commitment tx and sign it
      val theirCommitTx = makeCommitTx(theirParams.finalKey, ourParams.finalKey, ourParams.delay, anchorTx.hash, anchorOutputIndex, theirRevocationHash, state.reverse)
      val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, commitPrivKey))
      node ! open_anchor(anchorTx.hash, 0, anchorInput.amount, ourSigForThem)
      expectMsgClass(classOf[open_commit_sig])
      expectMsgClass(classOf[Watch])
      node ! BITCOIN_ANCHOR_DEPTHOK
      expectMsgClass(classOf[open_complete])
      node ! CMD_GETSTATE
      expectMsg(OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR)
      // the only difference between their final tx and ours is the order of the outputs, because state is symmetric
      val theirFinalTx = makeFinalTx(ourCommitTx.txIn, theirParams.finalKey, ourParams.finalKey, state.reverse)
      val ourFinalSigForThem = bin2signature(Transaction.signInput(theirFinalTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, commitPrivKey))
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
      val node = system.actorOf(Props(new Node(self, bob_commit_priv, bob_final_priv, 2, Some(anchorInput))))
      node ! INPUT_NONE
      val their_open_channel = expectMsgClass(classOf[open_channel])
      val theirParams = ChannelParams(their_open_channel.delay, their_open_channel.commitKey, their_open_channel.finalKey, their_open_channel.minDepth.get, their_open_channel.commitmentFee)
      val theirRevocationHash = their_open_channel.revocationHash
      val ourRevocationHashPreimage = sha256_hash(4, 3, 2, 1)
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      node ! open_channel(ourParams.delay, ourRevocationHash, ourParams.commitKey, ourParams.finalKey, WONT_CREATE_ANCHOR, Some(ourParams.minDepth), ourParams.commitmentFee)
      val their_open_anchor = expectMsgClass(classOf[open_anchor])
      // we fund the channel with the anchor tx, so the money is ours
      val state = ChannelState(them = ChannelOneSide(their_open_anchor.amount - our_commitment_fee, 0, Seq()), us = ChannelOneSide(0, 0, Seq()))
      // we build our commitment tx, leaving it unsigned
      val ourCommitTx = makeCommitTx(ourParams.finalKey, theirParams.finalKey, theirParams.delay, their_open_anchor.txid, their_open_anchor.outputIndex, Crypto.sha256(ourRevocationHashPreimage), state)
      // then we build their commitment tx and sign it
      val theirCommitTx = makeCommitTx(theirParams.finalKey, ourParams.finalKey, ourParams.delay, their_open_anchor.txid, their_open_anchor.outputIndex, theirRevocationHash, state.reverse)
      val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, commitPrivKey))
      node ! open_commit_sig(ourSigForThem)
      expectMsgClass(classOf[Watch])
      expectMsgClass(classOf[Publish])
      node ! BITCOIN_ANCHOR_DEPTHOK
      expectMsgClass(classOf[open_complete])
      node ! CMD_GETSTATE
      expectMsg(OPEN_WAIT_FOR_COMPLETE_OURANCHOR)
      // the only difference between their final tx and ours is the order of the outputs, because state is symmetric
      val theirFinalTx = makeFinalTx(ourCommitTx.txIn, theirParams.finalKey, ourParams.finalKey, state.reverse)
      val ourFinalSigForThem = bin2signature(Transaction.signInput(theirFinalTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, commitPrivKey))
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
