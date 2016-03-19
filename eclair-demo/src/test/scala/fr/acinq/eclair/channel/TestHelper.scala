package fr.acinq.eclair.channel

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import com.google.protobuf.ByteString
import fr.acinq.bitcoin._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.channel.Scripts._
import lightning._
import lightning.locktime.Locktime.Blocks
import lightning.open_channel.anchor_offer.{WILL_CREATE_ANCHOR, WONT_CREATE_ANCHOR}
import org.bouncycastle.util.encoders.Hex
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.util.Random

/**
 * Created by PM on 08/09/2015.
 */
abstract class TestHelper(_system: ActorSystem) extends TestKit(_system) with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("MySpec"))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val previousTxOutput = OutPoint(Hex.decode("7727730d21428276a4d6b0e16f3a3e6f3a07a07dc67151e6a88d4a8c3e8edb24").reverse, 1)
  val signData = SignData("76a914e093fbc19866b98e0fbc25d79d0ad0f0375170af88ac", Base58Check.decode("cU1YgK56oUKAtV6XXHZeJQjEx1KGXkZS1pGiKpyW4mUyKYFJwWFg")._2)

  val our_commitkey_priv = Base58Check.decode("cQPmcNr6pwBQPyGfab3SksE9nTCtx9ism9T4dkS9dETNU2KKtJHk")._2
  val our_finalkey_priv = Base58Check.decode("cUrAtLtV7GGddqdkhUxnbZVDWGJBTducpPoon3eKp9Vnr1zxs6BG")._2
  val bob_commit_priv = Base58Check.decode("cSUwLtdZ2tht9ZmHhdQue48pfe7tY2GT2TGWJDtjoZgo6FHrubGk")._2
  val bob_final_priv = Base58Check.decode("cPR7ZgXpUaDPA3GwGceMDS5pfnSm955yvks3yELf3wMJwegsdGTg")._2

  val anchorAmount = 100100000L

  val ourParams = OurChannelParams(locktime(Blocks(10)), our_commitkey_priv, our_finalkey_priv, 1, 100000, "alice-seed".getBytes(), Some(anchorAmount))
  val bob_params = OurChannelParams(locktime(Blocks(10)), bob_commit_priv, bob_final_priv, 2, 100000, "bob-seed".getBytes(), None)

  val ourCommitPubKey = bitcoin_pubkey(ByteString.copyFrom(Crypto.publicKeyFromPrivateKey(our_commitkey_priv.key.toByteArray)))
  val ourFinalPubKey = bitcoin_pubkey(ByteString.copyFrom(Crypto.publicKeyFromPrivateKey(our_finalkey_priv.key.toByteArray)))

  case class ChannelDesc(ourParams: Option[OurChannelParams] = None, theirParams: Option[TheirChannelParams] = None, ourCommitment: Option[Commitment] = None)

  def reachState_NOANCHOR(targetState: State): (ActorRef, ChannelDesc) = {
    var channelDesc = ChannelDesc()
    val node = system.actorOf(Channel.props(self, self, bob_params))
    val their_open_channel = expectMsgClass(classOf[open_channel])
    val theirParams = TheirChannelParams(their_open_channel.delay, their_open_channel.commitKey, their_open_channel.finalKey, their_open_channel.minDepth, their_open_channel.commitmentFee)
    channelDesc = channelDesc.copy(theirParams = Some(theirParams))
    val theirRevocationHash = their_open_channel.revocationHash
    val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, 0))
    node ! CMD_GETSTATE // node is in OPEN_WAIT_FOR_OPEN_NOANCHOR
    if (expectMsgClass(classOf[State]) == targetState) return (node, channelDesc)
    channelDesc = channelDesc.copy(ourParams = Some(ourParams.copy(anchorAmount = None)))
    node ! open_channel(ourParams.delay, ourRevocationHash, ourCommitPubKey, ourFinalPubKey, WILL_CREATE_ANCHOR, Some(ourParams.minDepth), ourParams.commitmentFee)
    val (anchorTx, anchorOutputIndex) = makeAnchorTx(ourCommitPubKey, theirParams.commitPubKey, anchorAmount, previousTxOutput, signData)
    // we fund the channel with the anchor tx, so the money is ours
    val state = ChannelState(them = ChannelOneSide(0, 0, Seq()), us = ChannelOneSide(ourParams.anchorAmount.get * 1000 - ourParams.commitmentFee * 1000, ourParams.commitmentFee * 1000, Seq()))
    // we build our commitment tx, leaving it unsigned
    val ourCommitTx = makeCommitTx(ourFinalPubKey, theirParams.finalPubKey, ourParams.delay, anchorTx.hash, anchorOutputIndex, ourRevocationHash, state)
    channelDesc = channelDesc.copy(ourCommitment = Some(Commitment(0, ourCommitTx, state, theirRevocationHash)))
    // then we build their commitment tx and sign it
    val theirCommitTx = makeCommitTx(theirParams.finalPubKey, ourFinalPubKey, theirParams.delay, anchorTx.hash, anchorOutputIndex, theirRevocationHash, state.reverse)
    val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourCommitPubKey, theirParams.commitPubKey), SIGHASH_ALL, ourParams.commitPrivKey))
    node ! CMD_GETSTATE // node is in OPEN_WAIT_FOR_ANCHOR
    if (expectMsgClass(classOf[State]) == targetState) return (node, channelDesc)
    node ! open_anchor(anchorTx.hash, 0, anchorAmount, ourSigForThem)
    expectMsgClass(classOf[open_commit_sig])
    expectMsgClass(classOf[WatchConfirmed])
    expectMsgClass(classOf[WatchSpent])
    node ! CMD_GETSTATE // node is in OPEN_WAITING_THEIRANCHOR
    if (expectMsgClass(classOf[State]) == targetState) return (node, channelDesc)
    node ! BITCOIN_ANCHOR_DEPTHOK
    expectMsgClass(classOf[WatchLost])
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
    val node = system.actorOf(Channel.props(self, self, bob_params.copy(anchorAmount = Some(anchorAmount))))
    //node ! INPUT_NONE
    val their_open_channel = expectMsgClass(classOf[open_channel])
    val theirParams = TheirChannelParams(their_open_channel.delay, their_open_channel.commitKey, their_open_channel.finalKey, their_open_channel.minDepth, their_open_channel.commitmentFee)
    channelDesc = channelDesc.copy(theirParams = Some(theirParams))
    val theirRevocationHash = their_open_channel.revocationHash
    val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, 0))
    node ! CMD_GETSTATE // node is in OPEN_WAIT_FOR_OPEN_WITHANCHOR
    if (expectMsgClass(classOf[State]) == targetState) return (node, channelDesc)
    channelDesc = channelDesc.copy(ourParams = Some(ourParams))
    node ! open_channel(ourParams.delay, ourRevocationHash, ourCommitPubKey, ourFinalPubKey, WONT_CREATE_ANCHOR, Some(ourParams.minDepth), ourParams.commitmentFee)
    val MakeAnchor(_, _, amount) = expectMsgClass(classOf[MakeAnchor])
    val anchorTx = Transaction(version = 1,
      txIn = Seq.empty[TxIn],
      txOut = TxOut(amount, Scripts.anchorPubkeyScript(ourCommitPubKey, theirParams.commitPubKey)) :: Nil,
      lockTime = 0
    )
    node ! (anchorTx, 0)
    val their_open_anchor = expectMsgClass(classOf[open_anchor])
    // we fund the channel with the anchor tx, so the money is ours
    val state = ChannelState(them = ChannelOneSide(their_open_anchor.amount * 1000 - ourParams.commitmentFee * 1000, ourParams.commitmentFee * 1000, Seq()), us = ChannelOneSide(0, 0, Seq()))
    // we build our commitment tx, leaving it unsigned
    val ourCommitTx = makeCommitTx(ourFinalPubKey, theirParams.finalPubKey, theirParams.delay, their_open_anchor.txid, their_open_anchor.outputIndex, ourRevocationHash, state)
    channelDesc = channelDesc.copy(ourCommitment = Some(Commitment(0, ourCommitTx, state, theirRevocationHash)))
    // then we build their commitment tx and sign it
    val theirCommitTx = makeCommitTx(theirParams.finalPubKey, ourFinalPubKey, ourParams.delay, their_open_anchor.txid, their_open_anchor.outputIndex, theirRevocationHash, state.reverse)
    val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourCommitPubKey, theirParams.commitPubKey), SIGHASH_ALL, ourParams.commitPrivKey))
    node ! CMD_GETSTATE // node is in OPEN_WAIT_FOR_COMMIT_SIG
    if (expectMsgClass(classOf[State]) == targetState) return (node, channelDesc)
    node ! open_commit_sig(ourSigForThem)
    expectMsgClass(classOf[WatchConfirmed])
    expectMsgClass(classOf[WatchSpent])
    expectMsgClass(classOf[Publish])
    node ! CMD_GETSTATE // node is in OPEN_WAITING_OURANCHOR
    if (expectMsgClass(classOf[State]) == targetState) return (node, channelDesc)
    node ! BITCOIN_ANCHOR_DEPTHOK
    expectMsgClass(classOf[WatchLost])
    expectMsgClass(classOf[open_complete])
    node ! CMD_GETSTATE // node is in OPEN_WAIT_FOR_COMPLETE_OURANCHOR
    if (expectMsgClass(classOf[State]) == targetState) return (node, channelDesc)
    node ! open_complete(None)
    node ! CMD_GETSTATE // node is in NORMAL_HIGHPRIO
    if (expectMsgClass(classOf[State]) == targetState) return (node, channelDesc)
    ???
  }

  val random = new Random()

  def random_r = sha256_hash(random.nextLong(), random.nextLong(), random.nextLong(), random.nextLong())

  def send_htlc(node: ActorRef, channelDesc: ChannelDesc, amount: Int): (ChannelDesc, sha256_hash) = {
    val ChannelDesc(Some(ourParams), Some(theirParams), Some(previousCommitment)) = channelDesc
    val ourRevocationHash1 = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, previousCommitment.index + 1))
    val r = random_r
    val rHash = Crypto.sha256(r)
    val htlc = update_add_htlc(ourRevocationHash1, amount, rHash, locktime(Blocks(4)))
    val state1 = previousCommitment.state.htlc_send(Htlc(amount, rHash, htlc.expiry, Nil, None))

    node ! htlc
    val update_accept(theirSig1, theirRevocationHash1) = expectMsgClass(classOf[update_accept])
    val (ourCommitTx1, ourSigForThem1) = sign_their_commitment_tx(ourParams, theirParams, previousCommitment.tx.txIn, state1, ourRevocationHash1, theirRevocationHash1)
    val signedCommitTx1 = sign_our_commitment_tx(ourParams, theirParams, ourCommitTx1, theirSig1)
    Transaction.correctlySpends(signedCommitTx1, Map(previousCommitment.tx.txIn(0).outPoint -> anchorPubkeyScript(ourCommitPubKey, theirParams.commitPubKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

    node ! update_signature(ourSigForThem1, ShaChain.shaChainFromSeed(ourParams.shaSeed, previousCommitment.index))
    val update_complete(theirRevocationPreimage0) = expectMsgClass(classOf[update_complete])
    assert(Crypto.sha256(theirRevocationPreimage0).deep === sha2562bin(previousCommitment.theirRevocationHash).data.toArray.deep)

    (channelDesc.copy(ourCommitment = Some(Commitment(previousCommitment.index + 1, signedCommitTx1, state1, theirRevocationHash1))), r)
  }

  def send_fulfill_htlc(node: ActorRef, channelDesc: ChannelDesc, r: sha256_hash): ChannelDesc = {
    val ChannelDesc(Some(ourParams), Some(theirParams), Some(previousCommitment)) = channelDesc

    val ourRevocationHash2 = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, previousCommitment.index + 1))
    val state2 = previousCommitment.state.htlc_fulfill(r)

    node ! update_fulfill_htlc(ourRevocationHash2, r)
    val update_accept(theirSig2, theirRevocationHash2) = expectMsgClass(classOf[update_accept])
    val (ourCommitTx2, ourSigForThem2) = sign_their_commitment_tx(ourParams, theirParams, previousCommitment.tx.txIn, state2, ourRevocationHash2, theirRevocationHash2)
    val signedCommitTx2 = sign_our_commitment_tx(ourParams, theirParams, ourCommitTx2, theirSig2)
    Transaction.correctlySpends(signedCommitTx2, Map(previousCommitment.tx.txIn(0).outPoint -> anchorPubkeyScript(ourCommitPubKey, theirParams.commitPubKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

    node ! update_signature(ourSigForThem2, ShaChain.shaChainFromSeed(ourParams.shaSeed, previousCommitment.index))
    val update_complete(theirRevocationPreimage1) = expectMsgClass(classOf[update_complete])
    assert(Crypto.sha256(theirRevocationPreimage1).deep === sha2562bin(previousCommitment.theirRevocationHash).data.toArray.deep)

    channelDesc.copy(ourCommitment = Some(Commitment(previousCommitment.index + 1, signedCommitTx2, state2, theirRevocationHash2)))
  }

  def receive_htlc(node: ActorRef, channelDesc: ChannelDesc, amount: Int): (ChannelDesc, sha256_hash) = {
    val ChannelDesc(Some(ourParams), Some(theirParams), Some(previousCommitment)) = channelDesc
    val state0 = previousCommitment.state
    val theirRevocationHash0 = previousCommitment.theirRevocationHash

    val their_r = random_r
    val their_rHash = Crypto.sha256(their_r)
    node ! CMD_SEND_HTLC_UPDATE(amount, their_rHash, locktime(Blocks(4)))

    val htlc@update_add_htlc(theirRevocationHash1, amount_, rHash, expiry, _) = expectMsgClass(classOf[update_add_htlc])
    assert(amount === amount_)
    val state1 = state0.htlc_receive(Htlc(htlc.amountMsat, rHash, htlc.expiry, Nil, None))
    val ourRevocationHash1 = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, previousCommitment.index + 1))
    val (ourCommitTx1, ourSigForThem1) = sign_their_commitment_tx(ourParams, theirParams, previousCommitment.tx.txIn, state1, ourRevocationHash1, theirRevocationHash1)

    node ! update_accept(ourSigForThem1, ourRevocationHash1)
    val update_signature(theirSig1, theirRevocationPreimage0) = expectMsgClass(classOf[update_signature])
    assert(Crypto.sha256(theirRevocationPreimage0).deep === sha2562bin(theirRevocationHash0).data.toArray.deep)
    val signedCommitTx1 = sign_our_commitment_tx(ourParams, theirParams, ourCommitTx1, theirSig1)
    Transaction.correctlySpends(signedCommitTx1, Map(previousCommitment.tx.txIn(0).outPoint -> anchorPubkeyScript(ourCommitPubKey, theirParams.commitPubKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

    node ! update_complete(ShaChain.shaChainFromSeed(ourParams.shaSeed, previousCommitment.index))

    (channelDesc.copy(ourCommitment = Some(Commitment(previousCommitment.index + 1, signedCommitTx1, state1, theirRevocationHash1))), their_r)
  }

  def receive_fulfill_htlc(node: ActorRef, channelDesc: ChannelDesc, r: sha256_hash): ChannelDesc = {
    val ChannelDesc(Some(ourParams), Some(theirParams), Some(previousCommitment)) = channelDesc
    val state1 = previousCommitment.state
    val theirRevocationHash1 = previousCommitment.theirRevocationHash

    node ! CMD_SEND_HTLC_FULFILL(r)
    val update_fulfill_htlc(theirRevocationHash2, r_) = expectMsgClass(classOf[update_fulfill_htlc])
    assert(r === r_)
    val state2 = previousCommitment.state.htlc_fulfill(r)
    val ourRevocationHash2 = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, previousCommitment.index + 1))
    val (ourCommitTx2, ourSigForThem2) = sign_their_commitment_tx(ourParams, theirParams, previousCommitment.tx.txIn, state2, ourRevocationHash2, theirRevocationHash2)

    node ! update_accept(ourSigForThem2, ourRevocationHash2)
    val update_signature(theirSig2, theirRevocationPreimage1) = expectMsgClass(classOf[update_signature])
    assert(Crypto.sha256(theirRevocationPreimage1).deep === sha2562bin(previousCommitment.theirRevocationHash).data.toArray.deep)
    val signedCommitTx2 = sign_our_commitment_tx(ourParams, theirParams, ourCommitTx2, theirSig2)
    Transaction.correctlySpends(signedCommitTx2, Map(previousCommitment.tx.txIn(0).outPoint -> anchorPubkeyScript(ourCommitPubKey, theirParams.commitPubKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

    node ! update_complete(ShaChain.shaChainFromSeed(ourParams.shaSeed, previousCommitment.index))

    channelDesc.copy(ourCommitment = Some(Commitment(previousCommitment.index + 1, signedCommitTx2, state2, theirRevocationHash2)))
  }


  def sign_their_commitment_tx(ourParams: OurChannelParams, theirParams: TheirChannelParams, inputs: Seq[TxIn], newState: ChannelState, ourRevocationHash: sha256_hash, theirRevocationHash: sha256_hash): (Transaction, signature) = {
    // we build our side of the new commitment tx
    val ourCommitTx = makeCommitTx(inputs, ourFinalPubKey, theirParams.finalPubKey, theirParams.delay, ourRevocationHash, newState)
    // we build their commitment tx and sign it
    val theirCommitTx = makeCommitTx(inputs, theirParams.finalPubKey, ourFinalPubKey, ourParams.delay, theirRevocationHash, newState.reverse)
    val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourCommitPubKey, theirParams.commitPubKey), SIGHASH_ALL, ourParams.commitPrivKey))
    (ourCommitTx, ourSigForThem)
  }

  def sign_our_commitment_tx(ourParams: OurChannelParams, theirParams: TheirChannelParams, ourCommitTx: Transaction, theirSig: signature): Transaction = {
    // TODO : Transaction.sign(...) should handle multisig
    val ourSig = Transaction.signInput(ourCommitTx, 0, multiSig2of2(ourCommitPubKey, theirParams.commitPubKey), SIGHASH_ALL, ourParams.commitPrivKey)
    ourCommitTx.updateSigScript(0, sigScript2of2(theirSig, ourSig, theirParams.commitPubKey, ourCommitPubKey))
  }

}
