package fr.acinq.eclair

import akka.actor.{ActorSystem, Props, ActorRef}
import akka.testkit.{ImplicitSender, TestKit}
import com.google.protobuf.ByteString
import fr.acinq.bitcoin._
import fr.acinq.lightning._
import lightning.open_channel.anchor_offer.{WONT_CREATE_ANCHOR, WILL_CREATE_ANCHOR}
import lightning._
import lightning.locktime.Locktime.Blocks
import org.bouncycastle.util.encoders.Hex
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

/**
 * Created by PM on 08/09/2015.
 */
abstract class TestHelper(_system: ActorSystem) extends TestKit(_system) with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll {

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

  case class ChannelDesc(ourParams: Option[ChannelParams] = None, theirParams: Option[ChannelParams] = None, ourCommitment: Option[Commitment] = None)

  def reachState_NOANCHOR(targetState: State): (ActorRef, ChannelDesc) = {
    var channelDesc = ChannelDesc()
    val node = system.actorOf(Props(new Node(self, bob_commit_priv, bob_final_priv, 2, None)))
    node ! INPUT_NONE
    val their_open_channel = expectMsgClass(classOf[open_channel])
    val theirParams = ChannelParams(their_open_channel.delay, their_open_channel.commitKey, their_open_channel.finalKey, their_open_channel.minDepth.get, their_open_channel.commitmentFee)
    channelDesc = channelDesc.copy(theirParams = Some(theirParams))
    val theirRevocationHash = their_open_channel.revocationHash
    val ourRevocationHashPreimage = sha256_hash(4, 3, 2, 1)
    val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
    node ! CMD_GETSTATE // node is in OPEN_WAIT_FOR_OPEN_NOANCHOR
    if (expectMsgClass(classOf[State]) == targetState) return (node, channelDesc)
    val ourParams = ChannelParams(our_delay, our_commitkey_pub, our_finalkey_pub, our_mindepth, our_commitment_fee)
    channelDesc = channelDesc.copy(ourParams = Some(ourParams))
    node ! open_channel(ourParams.delay, ourRevocationHash, ourParams.commitKey, ourParams.finalKey, WILL_CREATE_ANCHOR, Some(ourParams.minDepth), ourParams.commitmentFee)
    val anchorTx = makeAnchorTx(ourParams.commitKey, theirParams.commitKey, anchorInput.amount, anchorInput.previousTxOutput, anchorInput.signData)
    val anchorOutputIndex = 0
    // we fund the channel with the anchor tx, so the money is ours
    val state = ChannelState(them = ChannelOneSide(0, 0, Seq()), us = ChannelOneSide(anchorInput.amount - our_commitment_fee, 0, Seq()))
    // we build our commitment tx, leaving it unsigned
    val ourCommitTx = makeCommitTx(ourParams.finalKey, theirParams.finalKey, theirParams.delay, anchorTx.hash, anchorOutputIndex, Crypto.sha256(ourRevocationHashPreimage), state)
    channelDesc = channelDesc.copy(ourCommitment = Some(Commitment(ourCommitTx, state, ourRevocationHashPreimage, theirRevocationHash)))
    // then we build their commitment tx and sign it
    val theirCommitTx = makeCommitTx(theirParams.finalKey, ourParams.finalKey, ourParams.delay, anchorTx.hash, anchorOutputIndex, theirRevocationHash, state.reverse)
    val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, commitPrivKey))
    node ! CMD_GETSTATE // node is in OPEN_WAIT_FOR_ANCHOR
    if (expectMsgClass(classOf[State]) == targetState) return (node, channelDesc)
    node ! open_anchor(anchorTx.hash, 0, anchorInput.amount, ourSigForThem)
    expectMsgClass(classOf[open_commit_sig])
    expectMsgClass(classOf[Watch])
    node ! CMD_GETSTATE // node is in OPEN_WAITING_THEIRANCHOR
    if (expectMsgClass(classOf[State]) == targetState) return (node, channelDesc)
    node ! BITCOIN_ANCHOR_DEPTHOK
    expectMsgClass(classOf[open_complete])
    node ! CMD_GETSTATE // node is in OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR
    if (expectMsgClass(classOf[State]) == targetState) return (node, channelDesc)
    node ! open_complete(None)
    node ! CMD_GETSTATE // node is in NORMAL_LOWPRIO
    if (expectMsgClass(classOf[State]) == targetState) return (node, channelDesc)
    ???
  }

  def reachState_WITHANCHOR(targetState: State): (ActorRef, ChannelDesc) = {
    var channelDesc = ChannelDesc()
    val node = system.actorOf(Props(new Node(self, bob_commit_priv, bob_final_priv, 2, Some(anchorInput))))
    node ! INPUT_NONE
    val their_open_channel = expectMsgClass(classOf[open_channel])
    val theirParams = ChannelParams(their_open_channel.delay, their_open_channel.commitKey, their_open_channel.finalKey, their_open_channel.minDepth.get, their_open_channel.commitmentFee)
    channelDesc = channelDesc.copy(theirParams = Some(theirParams))
    val theirRevocationHash = their_open_channel.revocationHash
    val ourRevocationHashPreimage = sha256_hash(4, 3, 2, 1)
    val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
    node ! CMD_GETSTATE // node is in OPEN_WAIT_FOR_OPEN_WITHANCHOR
    if (expectMsgClass(classOf[State]) == targetState) return (node, channelDesc)
    val ourParams = ChannelParams(our_delay, our_commitkey_pub, our_finalkey_pub, our_mindepth, our_commitment_fee)
    channelDesc = channelDesc.copy(ourParams = Some(ourParams))
    node ! open_channel(ourParams.delay, ourRevocationHash, ourParams.commitKey, ourParams.finalKey, WONT_CREATE_ANCHOR, Some(ourParams.minDepth), ourParams.commitmentFee)
    val their_open_anchor = expectMsgClass(classOf[open_anchor])
    // we fund the channel with the anchor tx, so the money is ours
    val state = ChannelState(them = ChannelOneSide(their_open_anchor.amount - our_commitment_fee, 0, Seq()), us = ChannelOneSide(0, 0, Seq()))
    // we build our commitment tx, leaving it unsigned
    val ourCommitTx = makeCommitTx(ourParams.finalKey, theirParams.finalKey, theirParams.delay, their_open_anchor.txid, their_open_anchor.outputIndex, Crypto.sha256(ourRevocationHashPreimage), state)
    channelDesc = channelDesc.copy(ourCommitment = Some(Commitment(ourCommitTx, state, ourRevocationHashPreimage, theirRevocationHash)))
    // then we build their commitment tx and sign it
    val theirCommitTx = makeCommitTx(theirParams.finalKey, ourParams.finalKey, ourParams.delay, their_open_anchor.txid, their_open_anchor.outputIndex, theirRevocationHash, state.reverse)
    val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, commitPrivKey))
    node ! CMD_GETSTATE // node is in OPEN_WAIT_FOR_COMMIT_SIG
    if (expectMsgClass(classOf[State]) == targetState) return (node, channelDesc)
    node ! open_commit_sig(ourSigForThem)
    expectMsgClass(classOf[Watch])
    expectMsgClass(classOf[Publish])
    node ! CMD_GETSTATE // node is in OPEN_WAITING_OURANCHOR
    if (expectMsgClass(classOf[State]) == targetState) return (node, channelDesc)
    node ! BITCOIN_ANCHOR_DEPTHOK
    expectMsgClass(classOf[open_complete])
    node ! CMD_GETSTATE // node is in OPEN_WAIT_FOR_COMPLETE_OURANCHOR
    if (expectMsgClass(classOf[State]) == targetState) return (node, channelDesc)
    node ! open_complete(None)
    node ! CMD_GETSTATE // node is in NORMAL_HIGHPRIO
    if (expectMsgClass(classOf[State]) == targetState) return (node, channelDesc)
    ???
  }

  def sign_their_commitment_tx(ourParams: ChannelParams, theirParams: ChannelParams, inputs: Seq[TxIn], newState: ChannelState, ourRevocationHashPreimage: sha256_hash, theirRevocationHash: sha256_hash): (Transaction, signature) = {
    // we build our side of the new commitment tx
    val ourCommitTx = makeCommitTx(inputs, ourParams.finalKey, theirParams.finalKey, theirParams.delay, Crypto.sha256(ourRevocationHashPreimage), newState)
    // we build their commitment tx and sign it
    val theirCommitTx = makeCommitTx(inputs, theirParams.finalKey, ourParams.finalKey, ourParams.delay, theirRevocationHash, newState.reverse)
    val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, commitPrivKey))
    (ourCommitTx, ourSigForThem)
  }

  def sign_our_commitment_tx(ourParams: ChannelParams, theirParams: ChannelParams, ourCommitTx: Transaction, theirSig: signature): Transaction = {
    // TODO : Transaction.sign(...) should handle multisig
    val ourSig = Transaction.signInput(ourCommitTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, commitPrivKey)
    ourCommitTx.updateSigScript(0, sigScript2of2(theirSig, ourSig, theirParams.commitKey, ourParams.commitKey))
  }

}
