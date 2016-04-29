package fr.acinq.eclair.channel

import akka.actor.{ActorRef, LoggingFSM, Props}
import com.google.protobuf.ByteString
import fr.acinq.bitcoin._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.crypto.ShaChain
import Scripts._
import lightning._
import lightning.open_channel.anchor_offer.{WILL_CREATE_ANCHOR, WONT_CREATE_ANCHOR}
import org.bouncycastle.util.encoders.Hex

import scala.util.{Failure, Success, Try}

/**
  * Created by PM on 20/08/2015.
  */

// @formatter:off

/*
       .d8888b. 88888888888     d8888 88888888888 8888888888 .d8888b.
      d88P  Y88b    888        d88888     888     888       d88P  Y88b
      Y88b.         888       d88P888     888     888       Y88b.
       "Y888b.      888      d88P 888     888     8888888    "Y888b.
          "Y88b.    888     d88P  888     888     888           "Y88b.
            "888    888    d88P   888     888     888             "888
      Y88b  d88P    888   d8888888888     888     888       Y88b  d88P
       "Y8888P"     888  d88P     888     888     8888888888 "Y8888P"
 */
sealed trait State
case object INIT_NOANCHOR extends State
case object INIT_WITHANCHOR extends State
case object OPEN_WAIT_FOR_OPEN_NOANCHOR extends State
case object OPEN_WAIT_FOR_OPEN_WITHANCHOR extends State
case object OPEN_WAIT_FOR_ANCHOR extends State
case object OPEN_WAIT_FOR_COMMIT_SIG extends State
case object OPEN_WAITING_THEIRANCHOR extends State
case object OPEN_WAITING_OURANCHOR extends State
case object OPEN_WAIT_FOR_COMPLETE_OURANCHOR extends State
case object OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR extends State
case object NORMAL extends State
case object NORMAL_WAIT_FOR_REV extends State
case object NORMAL_WAIT_FOR_REV_THEIRSIG extends State
case object NORMAL_WAIT_FOR_SIG extends State
case object WAIT_FOR_CLOSE_ACK extends State
case object WAIT_FOR_CLOSE_COMPLETE extends State
case object CLOSING extends State
case object CLOSED extends State
case object ERR_ANCHOR_LOST extends State
case object ERR_ANCHOR_TIMEOUT extends State
case object ERR_INFORMATION_LEAK extends State

/*
      8888888888 888     888 8888888888 888b    888 88888888888 .d8888b.
      888        888     888 888        8888b   888     888    d88P  Y88b
      888        888     888 888        88888b  888     888    Y88b.
      8888888    Y88b   d88P 8888888    888Y88b 888     888     "Y888b.
      888         Y88b d88P  888        888 Y88b888     888        "Y88b.
      888          Y88o88P   888        888  Y88888     888          "888
      888           Y888P    888        888   Y8888     888    Y88b  d88P
      8888888888     Y8P     8888888888 888    Y888     888     "Y8888P"
 */

case object INPUT_NO_MORE_HTLCS
// when requesting a mutual close, we wait for as much as this timeout, then unilateral close
case object INPUT_CLOSE_COMPLETE_TIMEOUT

sealed trait BlockchainEvent
case object BITCOIN_ANCHOR_DEPTHOK extends BlockchainEvent
case object BITCOIN_ANCHOR_LOST extends BlockchainEvent
case object BITCOIN_ANCHOR_TIMEOUT extends BlockchainEvent
case object BITCOIN_ANCHOR_SPENT extends BlockchainEvent
case object BITCOIN_ANCHOR_OURCOMMIT_DELAYPASSED extends BlockchainEvent
case object BITCOIN_SPEND_THEIRS_DONE extends BlockchainEvent
case object BITCOIN_SPEND_OURS_DONE extends BlockchainEvent
case object BITCOIN_STEAL_DONE extends BlockchainEvent
case object BITCOIN_CLOSE_DONE extends BlockchainEvent

/*
       .d8888b.   .d88888b.  888b     d888 888b     d888        d8888 888b    888 8888888b.   .d8888b.
      d88P  Y88b d88P" "Y88b 8888b   d8888 8888b   d8888       d88888 8888b   888 888  "Y88b d88P  Y88b
      888    888 888     888 88888b.d88888 88888b.d88888      d88P888 88888b  888 888    888 Y88b.
      888        888     888 888Y88888P888 888Y88888P888     d88P 888 888Y88b 888 888    888  "Y888b.
      888        888     888 888 Y888P 888 888 Y888P 888    d88P  888 888 Y88b888 888    888     "Y88b.
      888    888 888     888 888  Y8P  888 888  Y8P  888   d88P   888 888  Y88888 888    888       "888
      Y88b  d88P Y88b. .d88P 888   "   888 888   "   888  d8888888888 888   Y8888 888  .d88P Y88b  d88P
       "Y8888P"   "Y88888P"  888       888 888       888 d88P     888 888    Y888 8888888P"   "Y8888P"
 */

sealed trait Command
final case class CMD_ADD_HTLC(amount: Int, rHash: sha256_hash, expiry: locktime, nodeIds: Seq[String] = Seq.empty[String], originChannelId: Option[BinaryData] = None) extends Command
final case class CMD_FULFILL_HTLC(id: Long, r: sha256_hash) extends Command
final case class CMD_FAIL_HTLC(id: Long, reason: String) extends Command
case object CMD_SIGN extends Command
final case class CMD_CLOSE(fee: Long) extends Command
case object CMD_GETSTATE extends Command
case object CMD_GETSTATEDATA extends Command
case object CMD_GETINFO extends Command
final case class RES_GETINFO(nodeid: BinaryData, channelid: BinaryData, state: State, data: Data)

/*
      8888888b.        d8888 88888888888     d8888
      888  "Y88b      d88888     888        d88888
      888    888     d88P888     888       d88P888
      888    888    d88P 888     888      d88P 888
      888    888   d88P  888     888     d88P  888
      888    888  d88P   888     888    d88P   888
      888  .d88P d8888888888     888   d8888888888
      8888888P" d88P     888     888  d88P     888
 */

sealed trait Data
case object Nothing extends Data
final case class OurChannelParams(delay: locktime, commitPrivKey: BinaryData, finalPrivKey: BinaryData, minDepth: Int, initialFeeRate: Long, shaSeed: BinaryData, anchorAmount: Option[Long]) {
  val commitPubKey: BinaryData = Crypto.publicKeyFromPrivateKey(commitPrivKey)
  val finalPubKey: BinaryData = Crypto.publicKeyFromPrivateKey(finalPrivKey)
}
final case class TheirChannelParams(delay: locktime, commitPubKey: BinaryData, finalPubKey: BinaryData, minDepth: Option[Int], initialFeeRate: Long)
final case class Commitment(index: Long, tx: Transaction, state: ChannelState, theirRevocationHash: sha256_hash) {
  def anchorId: BinaryData = {
    assert(tx.txIn.size == 1, "commitment tx should only have one input")
    tx.txIn(0).outPoint.hash
  }
}
final case class UpdateProposal(index: Long, state: ChannelState, theirRevocationHash: sha256_hash)

trait CurrentCommitment {
  def ack_in: Long
  def ack_out: Long
  def ourParams: OurChannelParams
  def theirParams: TheirChannelParams
  def shaChain: ShaChain
  def commitment: Commitment
}

trait NextCommitment
final case class ReadyForSig(theirNextRevocationHash: sha256_hash) extends NextCommitment
final case class WaitForRev(proposal: UpdateProposal) extends NextCommitment
final case class WaitForRevTheirSig(nextCommitment: Commitment) extends NextCommitment
final case class WaitForSig(proposal: UpdateProposal, theirNextRevocationHash: sha256_hash) extends NextCommitment

final case class DATA_OPEN_WAIT_FOR_OPEN              (ack_in: Long, ack_out: Long, ourParams: OurChannelParams) extends Data
final case class DATA_OPEN_WITH_ANCHOR_WAIT_FOR_ANCHOR(ack_in: Long, ack_out: Long, ourParams: OurChannelParams, theirParams: TheirChannelParams, theirRevocationHash: BinaryData, theirNextRevocationHash: sha256_hash) extends Data
final case class DATA_OPEN_WAIT_FOR_ANCHOR            (ack_in: Long, ack_out: Long, ourParams: OurChannelParams, theirParams: TheirChannelParams, theirRevocationHash: sha256_hash, theirNextRevocationHash: sha256_hash) extends Data
final case class DATA_OPEN_WAIT_FOR_COMMIT_SIG        (ack_in: Long, ack_out: Long, ourParams: OurChannelParams, theirParams: TheirChannelParams, anchorTx: Transaction, anchorOutputIndex: Int, newCommitmentUnsigned: Commitment, theirNextRevocationHash: sha256_hash) extends Data
final case class DATA_OPEN_WAITING                    (ack_in: Long, ack_out: Long, ourParams: OurChannelParams, theirParams: TheirChannelParams, shaChain: ShaChain, commitment: Commitment, theirNextRevocationHash: sha256_hash, deferred: Option[open_complete]) extends Data with CurrentCommitment
final case class DATA_NORMAL                          (ack_in: Long, ack_out: Long, ourParams: OurChannelParams, theirParams: TheirChannelParams, shaChain: ShaChain, htlcIdx: Long, staged: List[Change], commitment: Commitment, next: NextCommitment) extends Data with CurrentCommitment
final case class DATA_WAIT_FOR_CLOSE_ACK              (ack_in: Long, ack_out: Long, ourParams: OurChannelParams, theirParams: TheirChannelParams, shaChain: ShaChain, commitment: Commitment, mutualCloseTx: Transaction) extends Data with CurrentCommitment
final case class DATA_CLOSING                         (ack_in: Long, ack_out: Long, ourParams: OurChannelParams, theirParams: TheirChannelParams, shaChain: ShaChain, commitment: Commitment,
                              mutualClosePublished: Option[Transaction] = None, ourCommitPublished: Option[Transaction] = None, theirCommitPublished: Option[Transaction] = None, revokedPublished: Seq[Transaction] = Seq()) extends Data with CurrentCommitment {
  assert(mutualClosePublished.isDefined || ourCommitPublished.isDefined || theirCommitPublished.isDefined || revokedPublished.size > 0, "there should be at least one tx published in this state")
}

// @formatter:on

object Channel {
  def props(them: ActorRef, blockchain: ActorRef, params: OurChannelParams, theirNodeId: String = Hash.Zeroes.toString()) = Props(new Channel(them, blockchain, params, theirNodeId))

  def isMutualClose(tx: Transaction, ourParams: OurChannelParams, theirParams: TheirChannelParams, commitment: Commitment): Boolean = {
    // we rebuild the closing tx as seen by both parties
    //TODO we should use the closing fee in pkts
    val closingState = commitment.state.adjust_fees(Globals.closing_fee * 1000, ourParams.anchorAmount.isDefined)
    //val finalTx = makeFinalTx(commitment.tx.txIn, theirParams.finalPubKey, ourFinalPubKey, closingState)
    val finalTx = makeFinalTx(commitment.tx.txIn, ourParams.finalPubKey, theirParams.finalPubKey, closingState)
    // and only compare the outputs
    tx.txOut == finalTx.txOut
  }

  def isOurCommit(tx: Transaction, commitment: Commitment): Boolean = tx == commitment.tx

  def isTheirCommit(tx: Transaction, ourParams: OurChannelParams, theirParams: TheirChannelParams, commitment: Commitment): Boolean = {
    // we rebuild their commitment tx
    val theirCommitTx = makeCommitTx(commitment.tx.txIn, theirParams.finalPubKey, ourParams.finalPubKey, theirParams.delay, commitment.theirRevocationHash, commitment.state.reverse)
    // and only compare the outputs
    tx.txOut == theirCommitTx.txOut
  }

  def isRevokedCommit(tx: Transaction): Boolean = {
    // TODO : for now we assume that every published tx which is none of (mutualclose, ourcommit, theircommit) is a revoked commit
    // which means ERR_INFORMATION_LEAK will never occur
    true
  }

  def sign_their_commitment_tx(ourParams: OurChannelParams, theirParams: TheirChannelParams, inputs: Seq[TxIn], newState: ChannelState, ourRevocationHash: sha256_hash, theirRevocationHash: sha256_hash): (Transaction, signature) = {
    // we build our side of the new commitment tx
    val ourCommitTx = makeCommitTx(inputs, ourParams.finalPubKey, theirParams.finalPubKey, ourParams.delay, ourRevocationHash, newState)
    // we build their commitment tx and sign it
    val theirCommitTx = makeCommitTx(inputs, theirParams.finalPubKey, ourParams.finalPubKey, theirParams.delay, theirRevocationHash, newState.reverse)
    val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourParams.commitPubKey, theirParams.commitPubKey), SIGHASH_ALL, ourParams.commitPrivKey))
    (ourCommitTx, ourSigForThem)
  }

  def sign_our_commitment_tx(ourParams: OurChannelParams, theirParams: TheirChannelParams, ourCommitTx: Transaction, theirSig: signature): Transaction = {
    // TODO : Transaction.sign(...) should handle multisig
    val ourSig = Transaction.signInput(ourCommitTx, 0, multiSig2of2(ourParams.commitPubKey, theirParams.commitPubKey), SIGHASH_ALL, ourParams.commitPrivKey)
    ourCommitTx.updateSigScript(0, sigScript2of2(theirSig, ourSig, theirParams.commitPubKey, ourParams.commitPubKey))
  }

  /*def handle_cmd_close(cmd: CMD_CLOSE, ourParams: OurChannelParams, theirParams: TheirChannelParams, commitment: Commitment): close_channel = {
    val closingState = commitment.state.adjust_fees(cmd.fee * 1000, ourParams.anchorAmount.isDefined)
    val finalTx = makeFinalTx(commitment.tx.txIn, ourParams.finalPubKey, theirParams.finalPubKey, closingState)
    val ourSig = bin2signature(Transaction.signInput(finalTx, 0, multiSig2of2(ourParams.commitPubKey, theirParams.commitPubKey), SIGHASH_ALL, ourParams.commitPrivKey))
    val anchorTxId = commitment.tx.txIn(0).outPoint.txid // commit tx only has 1 input, which is the anchor
    close_channel(ourSig, cmd.fee)
  }*/

  /*def handle_pkt_close(pkt: close_channel, ourParams: OurChannelParams, theirParams: TheirChannelParams, commitment: Commitment): (Transaction, close_channel_complete) = {
    val closingState = commitment.state.adjust_fees(pkt.closeFee * 1000, ourParams.anchorAmount.isDefined)
    val finalTx = makeFinalTx(commitment.tx.txIn, ourParams.finalPubKey, theirParams.finalPubKey, closingState)
    val ourSig = Transaction.signInput(finalTx, 0, multiSig2of2(ourParams.commitPubKey, theirParams.commitPubKey), SIGHASH_ALL, ourParams.commitPrivKey)
    val signedFinalTx = finalTx.updateSigScript(0, sigScript2of2(pkt.sig, ourSig, theirParams.commitPubKey, ourParams.commitPubKey))
    (signedFinalTx, close_channel_complete(ourSig))
  }*/

}

class Channel(val them: ActorRef, val blockchain: ActorRef, val params: OurChannelParams, theirNodeId: String) extends LoggingFSM[State, Data] {

  import Channel._
  import context.dispatcher

  val ourCommitPubKey = bitcoin_pubkey(ByteString.copyFrom(params.commitPubKey))
  val ourFinalPubKey = bitcoin_pubkey(ByteString.copyFrom(params.finalPubKey))

  log.info(s"commit pubkey: ${params.commitPubKey}")
  log.info(s"final pubkey: ${params.finalPubKey}")

  val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(params.shaSeed, 0))
  val ourNextRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(params.shaSeed, 1))

  params.anchorAmount match {
    case None =>
      them ! open_channel(params.delay, ourRevocationHash, ourNextRevocationHash, ourCommitPubKey, ourFinalPubKey, WONT_CREATE_ANCHOR, Some(params.minDepth), params.initialFeeRate)
      startWith(OPEN_WAIT_FOR_OPEN_NOANCHOR, DATA_OPEN_WAIT_FOR_OPEN(0, 1, params))
    case _ =>
      them ! open_channel(params.delay, ourRevocationHash, ourNextRevocationHash, ourCommitPubKey, ourFinalPubKey, WILL_CREATE_ANCHOR, Some(params.minDepth), params.initialFeeRate)
      startWith(OPEN_WAIT_FOR_OPEN_WITHANCHOR, DATA_OPEN_WAIT_FOR_OPEN(0, 1, params))
  }

  /*
          8888888 888b    888 8888888 88888888888
            888   8888b   888   888       888
            888   88888b  888   888       888
            888   888Y88b 888   888       888
            888   888 Y88b888   888       888
            888   888  Y88888   888       888
            888   888   Y8888   888       888
          8888888 888    Y888 8888888     888
   */
  when(OPEN_WAIT_FOR_OPEN_NOANCHOR) {
    case Event(open_channel(delay, theirRevocationHash, theirNextRevocationHash, commitKey, finalKey, WILL_CREATE_ANCHOR, minDepth, initialFeeRate), DATA_OPEN_WAIT_FOR_OPEN(ack_in, ack_out, ourParams)) =>
      val theirParams = TheirChannelParams(delay, commitKey, finalKey, minDepth, initialFeeRate)
      goto(OPEN_WAIT_FOR_ANCHOR) using DATA_OPEN_WAIT_FOR_ANCHOR(ack_in + 1, ack_out, ourParams, theirParams, theirRevocationHash, theirNextRevocationHash)

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)
  }

  when(OPEN_WAIT_FOR_OPEN_WITHANCHOR) {
    case Event(open_channel(delay, theirRevocationHash, theirNextRevocationHash, commitKey, finalKey, WONT_CREATE_ANCHOR, minDepth, initialFeeRate), DATA_OPEN_WAIT_FOR_OPEN(ack_in, ack_out, ourParams)) =>
      val theirParams = TheirChannelParams(delay, commitKey, finalKey, minDepth, initialFeeRate)
      log.debug(s"their params: $theirParams")
      blockchain ! MakeAnchor(ourCommitPubKey, theirParams.commitPubKey, ourParams.anchorAmount.get)
      stay using DATA_OPEN_WITH_ANCHOR_WAIT_FOR_ANCHOR(ack_in + 1, ack_out, ourParams, theirParams, theirRevocationHash, theirNextRevocationHash)

    case Event((anchorTx: Transaction, anchorOutputIndex: Int), DATA_OPEN_WITH_ANCHOR_WAIT_FOR_ANCHOR(ack_in, ack_out, ourParams, theirParams, theirRevocationHash, theirNextRevocationHash)) =>
      log.info(s"anchor txid=${anchorTx.txid}")
      val amount = anchorTx.txOut(anchorOutputIndex).amount
      val state = ChannelState(them = ChannelOneSide(0, 0, Seq()), us = ChannelOneSide((amount - ourParams.initialFeeRate) * 1000, ourParams.initialFeeRate * 1000, Seq()))
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, 0))
      val (ourCommitTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, TxIn(OutPoint(anchorTx.hash, anchorOutputIndex), Array.emptyByteArray, 0xffffffffL) :: Nil, state, ourRevocationHash, theirRevocationHash)
      log.info(s"our commit tx: ${Hex.toHexString(Transaction.write(ourCommitTx))}")
      them ! open_anchor(anchorTx.hash, anchorOutputIndex, amount, ourSigForThem)
      goto(OPEN_WAIT_FOR_COMMIT_SIG) using DATA_OPEN_WAIT_FOR_COMMIT_SIG(ack_in, ack_out + 1, ourParams, theirParams, anchorTx, anchorOutputIndex, Commitment(0, ourCommitTx, state, theirRevocationHash), theirNextRevocationHash)

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)
  }

  when(OPEN_WAIT_FOR_ANCHOR) {
    case Event(open_anchor(anchorTxHash, anchorOutputIndex, anchorAmount, theirSig), DATA_OPEN_WAIT_FOR_ANCHOR(ack_in, ack_out, ourParams, theirParams, theirRevocationHash, theirNextRevocationHash)) =>
      val anchorTxid = anchorTxHash.reverse //see https://github.com/ElementsProject/lightning/issues/17
    // they fund the channel with their anchor tx, so the money is theirs
    val state = ChannelState(them = ChannelOneSide((anchorAmount - ourParams.initialFeeRate) * 1000, ourParams.initialFeeRate * 1000, Seq()), us = ChannelOneSide(0, 0, Seq()))
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, 0))
      // we build our commitment tx, sign it and check that it is spendable using the counterparty's sig
      val (ourCommitTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, TxIn(OutPoint(anchorTxHash, anchorOutputIndex), Array.emptyByteArray, 0xffffffffL) :: Nil, state, ourRevocationHash, theirRevocationHash)
      val signedCommitTx = sign_our_commitment_tx(ourParams, theirParams, ourCommitTx, theirSig)
      val ok = Try(Transaction.correctlySpends(signedCommitTx, Map(OutPoint(anchorTxHash, anchorOutputIndex) -> anchorPubkeyScript(ourCommitPubKey, theirParams.commitPubKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      ok match {
        case false =>
          them ! error(Some("Bad signature"))
          goto(CLOSED)
        case true =>
          them ! open_commit_sig(ourSigForThem)
          blockchain ! WatchConfirmed(self, anchorTxid, ourParams.minDepth, BITCOIN_ANCHOR_DEPTHOK)
          blockchain ! WatchSpent(self, anchorTxid, anchorOutputIndex, 0, BITCOIN_ANCHOR_SPENT)
          goto(OPEN_WAITING_THEIRANCHOR) using DATA_OPEN_WAITING(ack_in + 1, ack_out + 1, ourParams, theirParams, ShaChain.init, Commitment(0, signedCommitTx, state, theirRevocationHash), theirNextRevocationHash, None)
      }

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)
  }

  when(OPEN_WAIT_FOR_COMMIT_SIG) {
    case Event(open_commit_sig(theirSig), DATA_OPEN_WAIT_FOR_COMMIT_SIG(ack_in, ack_out, ourParams, theirParams, anchorTx, anchorOutputIndex, commitment, theirNextRevocationHash)) =>
      val signedCommitTx = sign_our_commitment_tx(ourParams, theirParams, commitment.tx, theirSig)
      val ok = Try(Transaction.correctlySpends(signedCommitTx, Map(OutPoint(anchorTx.hash, anchorOutputIndex) -> anchorPubkeyScript(ourCommitPubKey, theirParams.commitPubKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      ok match {
        case false =>
          them ! error(Some("Bad signature"))
          goto(CLOSED)
        case true =>
          blockchain ! WatchConfirmed(self, anchorTx.txid, ourParams.minDepth, BITCOIN_ANCHOR_DEPTHOK)
          blockchain ! WatchSpent(self, anchorTx.txid, anchorOutputIndex, 0, BITCOIN_ANCHOR_SPENT)
          blockchain ! Publish(anchorTx)
          goto(OPEN_WAITING_OURANCHOR) using DATA_OPEN_WAITING(ack_in + 1, ack_out, ourParams, theirParams, ShaChain.init, commitment.copy(tx = signedCommitTx), theirNextRevocationHash, None)
      }

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)
  }

  when(OPEN_WAITING_THEIRANCHOR) {
    case Event(BITCOIN_ANCHOR_DEPTHOK, DATA_OPEN_WAITING(ack_in, ack_out, ourParams, theirParams, shaChain, commitment, theirNextRevocationHash, deferred)) =>
      val anchorTxId = commitment.tx.txIn(0).outPoint.txid // commit tx only has 1 input, which is the anchor
      blockchain ! WatchLost(self, anchorTxId, ourParams.minDepth, BITCOIN_ANCHOR_LOST)
      them ! open_complete(None)
      deferred.map(self ! _)
      //TODO htlcIdx should not be 0 when resuming connection
      goto(OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR) using DATA_NORMAL(ack_in, ack_out + 1, ourParams, theirParams, shaChain, 0, Nil, commitment, ReadyForSig(theirNextRevocationHash))

    case Event(msg@open_complete(blockId_opt), d: DATA_OPEN_WAITING) =>
      log.info(s"received their open_complete, deferring message")
      stay using d.copy(deferred = Some(msg))

    case Event(BITCOIN_ANCHOR_TIMEOUT, _) =>
      them ! error(Some("Anchor timed out"))
      goto(ERR_ANCHOR_TIMEOUT)

    /*case Event(pkt: close_channel, d: CurrentCommitment) =>
      val (finalTx, res) = handle_pkt_close(pkt, d.ourParams, d.theirParams, d.commitment)
      blockchain ! Publish(finalTx)
      them ! res
      goto(WAIT_FOR_CLOSE_ACK) using DATA_WAIT_FOR_CLOSE_ACK(d.ourParams, d.theirParams, d.shaChain, d.commitment, finalTx)

    case Event(cmd: CMD_CLOSE, d: CurrentCommitment) =>
      them ! handle_cmd_close(cmd, d.ourParams, d.theirParams, d.commitment)
      goto(WAIT_FOR_CLOSE_COMPLETE)*/

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: CurrentCommitment) if (isTheirCommit(tx, d.ourParams, d.theirParams, d.commitment)) =>
      them ! handle_theircommit(tx, d.ourParams, d.theirParams, d.shaChain, d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ack_in, d.ack_out, d.ourParams, d.theirParams, d.shaChain, d.commitment, theirCommitPublished = Some(tx))

    case Event(BITCOIN_ANCHOR_SPENT, _) =>
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, d: CurrentCommitment) =>
      publish_ourcommit(d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ack_in + 1, d.ack_out, d.ourParams, d.theirParams, d.shaChain, d.commitment, ourCommitPublished = Some(d.commitment.tx))
  }

  when(OPEN_WAITING_OURANCHOR) {
    case Event(BITCOIN_ANCHOR_DEPTHOK, DATA_OPEN_WAITING(ack_in, ack_out, ourParams, theirParams, shaChain, commitment, theirNextRevocationHash, deferred)) =>
      val anchorTxId = commitment.tx.txIn(0).outPoint.txid // commit tx only has 1 input, which is the anchor
      blockchain ! WatchLost(self, anchorTxId, ourParams.minDepth, BITCOIN_ANCHOR_LOST)
      them ! open_complete(None)
      deferred.map(self ! _)
      goto(OPEN_WAIT_FOR_COMPLETE_OURANCHOR) using DATA_NORMAL(ack_in, ack_out + 1, ourParams, theirParams, shaChain, 0, Nil, commitment, ReadyForSig(theirNextRevocationHash))

    case Event(msg@open_complete(blockId_opt), d: DATA_OPEN_WAITING) =>
      log.info(s"received their open_complete, deferring message")
      stay using d.copy(deferred = Some(msg))

    /*case Event(pkt: close_channel, d: CurrentCommitment) =>
      val (finalTx, res) = handle_pkt_close(pkt, d.ourParams, d.theirParams, d.commitment)
      blockchain ! Publish(finalTx)
      them ! res
      goto(WAIT_FOR_CLOSE_ACK) using DATA_WAIT_FOR_CLOSE_ACK(d.ourParams, d.theirParams, d.shaChain, d.commitment, finalTx)

    case Event(cmd: CMD_CLOSE, d: CurrentCommitment) =>
      them ! handle_cmd_close(cmd, d.ourParams, d.theirParams, d.commitment)
      goto(WAIT_FOR_CLOSE_COMPLETE)*/

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: CurrentCommitment) if (isTheirCommit(tx, d.ourParams, d.theirParams, d.commitment)) =>
      them ! handle_theircommit(tx, d.ourParams, d.theirParams, d.shaChain, d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ack_in, d.ack_out, d.ourParams, d.theirParams, d.shaChain, d.commitment, theirCommitPublished = Some(tx))

    case Event((BITCOIN_ANCHOR_SPENT, _), _) =>
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, d: CurrentCommitment) =>
      publish_ourcommit(d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ack_in + 1, d.ack_out, d.ourParams, d.theirParams, d.shaChain, d.commitment, ourCommitPublished = Some(d.commitment.tx))
  }

  when(OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR) {
    case Event(open_complete(blockid_opt), d: DATA_NORMAL) =>
      Register.create_alias(theirNodeId, d.commitment.anchorId)
      goto(NORMAL) using d.copy(ack_in = d.ack_in + 1)

    /*case Event(pkt: close_channel, d: CurrentCommitment) =>
      val (finalTx, res) = handle_pkt_close(pkt, d.ourParams, d.theirParams, d.commitment)
      blockchain ! Publish(finalTx)
      them ! res
      goto(WAIT_FOR_CLOSE_ACK) using DATA_WAIT_FOR_CLOSE_ACK(d.ourParams, d.theirParams, d.shaChain, d.commitment, finalTx)

    case Event(cmd: CMD_CLOSE, d: CurrentCommitment) =>
      them ! handle_cmd_close(cmd, d.ourParams, d.theirParams, d.commitment)
      goto(WAIT_FOR_CLOSE_COMPLETE)*/

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: DATA_NORMAL) if (isTheirCommit(tx, d.ourParams, d.theirParams, d.commitment)) =>
      them ! handle_theircommit(tx, d.ourParams, d.theirParams, d.shaChain, d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ack_in, d.ack_out, d.ourParams, d.theirParams, d.shaChain, d.commitment, theirCommitPublished = Some(tx))

    case Event((BITCOIN_ANCHOR_SPENT, _), _) =>
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, d: CurrentCommitment) =>
      publish_ourcommit(d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ack_in + 1, d.ack_out, d.ourParams, d.theirParams, d.shaChain, d.commitment, ourCommitPublished = Some(d.commitment.tx))
  }

  when(OPEN_WAIT_FOR_COMPLETE_OURANCHOR) {

    case Event(open_complete(blockid_opt), d: DATA_NORMAL) =>
      Register.create_alias(theirNodeId, d.commitment.anchorId)
      goto(NORMAL) using d.copy(ack_in = d.ack_in + 1)

    /*case Event(pkt: close_channel, d: CurrentCommitment) =>
      val (finalTx, res) = handle_pkt_close(pkt, d.ourParams, d.theirParams, d.commitment)
      blockchain ! Publish(finalTx)
      them ! res
      goto(WAIT_FOR_CLOSE_ACK) using DATA_WAIT_FOR_CLOSE_ACK(d.ourParams, d.theirParams, d.shaChain, d.commitment, finalTx)

    case Event(cmd: CMD_CLOSE, d: CurrentCommitment) =>
      them ! handle_cmd_close(cmd, d.ourParams, d.theirParams, d.commitment)
      goto(WAIT_FOR_CLOSE_COMPLETE)*/

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: DATA_NORMAL) if (isTheirCommit(tx, d.ourParams, d.theirParams, d.commitment)) =>
      them ! handle_theircommit(tx, d.ourParams, d.theirParams, d.shaChain, d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ack_in, d.ack_out, d.ourParams, d.theirParams, d.shaChain, d.commitment, theirCommitPublished = Some(tx))

    case Event((BITCOIN_ANCHOR_SPENT, _), _) =>
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, d: CurrentCommitment) =>
      publish_ourcommit(d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ack_in + 1, d.ack_out, d.ourParams, d.theirParams, d.shaChain, d.commitment, ourCommitPublished = Some(d.commitment.tx))
  }


  /*
          888b     d888        d8888 8888888 888b    888      888      .d88888b.   .d88888b.  8888888b.
          8888b   d8888       d88888   888   8888b   888      888     d88P" "Y88b d88P" "Y88b 888   Y88b
          88888b.d88888      d88P888   888   88888b  888      888     888     888 888     888 888    888
          888Y88888P888     d88P 888   888   888Y88b 888      888     888     888 888     888 888   d88P
          888 Y888P 888    d88P  888   888   888 Y88b888      888     888     888 888     888 8888888P"
          888  Y8P  888   d88P   888   888   888  Y88888      888     888     888 888     888 888
          888   "   888  d8888888888   888   888   Y8888      888     Y88b. .d88P Y88b. .d88P 888
          888       888 d88P     888 8888888 888    Y888      88888888 "Y88888P"   "Y88888P"  888
   */

  def update_handler: StateFunction = {
    case Event(CMD_ADD_HTLC(amount, rHash, expiry, nodeIds, origin), d@DATA_NORMAL(_, ack_out, _, _, _, htlcIdx, staged, commitment, _)) =>
      // TODO : should we take pending htlcs into account?
      assert(commitment.state.commit_changes(staged).us.pay_msat >= amount, "insufficient funds!")
      // TODO nodeIds are ignored
      val htlc = update_add_htlc(htlcIdx + 1, amount, rHash, expiry, routing(ByteString.EMPTY))
      them ! htlc
      stay using d.copy(ack_out = ack_out + 1, htlcIdx = htlc.id, staged = staged :+ Change(OUT, ack_out + 1, htlc))

    case Event(htlc@update_add_htlc(htlcId, amount, rHash, expiry, nodeIds), d@DATA_NORMAL(ack_in, _, _, _, _, _, staged, commitment, _)) =>
      // TODO : should we take pending htlcs into account?
      // TODO : we should fail the channel
      assert(commitment.state.commit_changes(staged).them.pay_msat >= amount, "insufficient funds!")
      // TODO nodeIds are ignored
      stay using d.copy(ack_in = ack_in + 1, staged = staged :+ Change(IN, ack_in + 1, htlc))

    case Event(CMD_FULFILL_HTLC(id, r), d@DATA_NORMAL(_, ack_out, _, _, _, _, staged, commitment, _)) =>
      // TODO : we should fail the channel
      assert(commitment.state.commit_changes(staged).us.htlcs_received.exists(_.id == id), s"unknown htlc id=$id")
      val fulfill = update_fulfill_htlc(id, r)
      them ! fulfill
      stay using d.copy(ack_out = ack_out + 1, staged = staged :+ Change(OUT, ack_out + 1, fulfill))

    case Event(fulfill@update_fulfill_htlc(id, r), d@DATA_NORMAL(ack_in, _, _, _, _, _, staged, commitment, _)) =>
      // TODO : we should fail the channel
      assert(commitment.state.commit_changes(staged).them.htlcs_received.exists(_.id == id), s"unknown htlc id=$id")
      stay using d.copy(ack_in = ack_in + 1, staged = staged :+ Change(IN, ack_in + 1, fulfill))

    case Event(CMD_FAIL_HTLC(id, reason), d@DATA_NORMAL(_, ack_out, _, _, _, _, staged, commitment, _)) =>
      // TODO : we should fail the channel
      assert(commitment.state.commit_changes(staged).us.htlcs_received.exists(_.id == id), s"unknown htlc id=$id")
      val fail = update_fail_htlc(id, fail_reason(ByteString.copyFromUtf8(reason)))
      them ! fail
      stay using d.copy(ack_out = ack_out + 1, staged = staged :+ Change(OUT, ack_out + 1, fail))

    case Event(fail@update_fail_htlc(id, reason), d@DATA_NORMAL(ack_in, _, _, _, _, _, staged, commitment, _)) =>
      // TODO : we should fail the channel
      assert(commitment.state.commit_changes(staged).them.htlcs_received.exists(_.id == id), s"unknown htlc id=$id")
      stay using d.copy(ack_in = ack_in + 1, staged = staged :+ Change(IN, ack_in + 1, fail))

    /*case Event(pkt: close_channel, d: CurrentCommitment) =>
      val (finalTx, res) = handle_pkt_close(pkt, d.ourParams, d.theirParams, d.commitment)
      blockchain ! Publish(finalTx)
      them ! res
      goto(WAIT_FOR_CLOSE_ACK) using DATA_WAIT_FOR_CLOSE_ACK(d.ourParams, d.theirParams, d.shaChain, d.commitment, finalTx)

    case Event(cmd: CMD_CLOSE, d: CurrentCommitment) =>
      them ! handle_cmd_close(cmd, d.ourParams, d.theirParams, d.commitment)
      goto(WAIT_FOR_CLOSE_COMPLETE)

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: DATA_NORMAL) if (isTheirCommit(tx, d.ourParams, d.theirParams, d.commitment)) =>
      them ! handle_theircommit(tx, d.ourParams, d.theirParams, d.shaChain, d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, theirCommitPublished = Some(tx))

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: CurrentCommitment) if (isRevokedCommit(tx)) =>
      them ! handle_revoked(tx)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, revokedPublished = tx :: Nil)

    case Event((BITCOIN_ANCHOR_SPENT, _), _) =>
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, d: CurrentCommitment) =>
      publish_ourcommit(d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, ourCommitPublished = Some(d.commitment.tx))*/
  }

  when(NORMAL)(update_handler orElse {
    case Event(CMD_SIGN, d@DATA_NORMAL(ack_in, ack_out, ourParams, theirParams, shaChain, htlcIdx, staged, previousCommitment, ReadyForSig(theirNextRevocationHash))) =>
      val proposal = UpdateProposal(previousCommitment.index + 1, previousCommitment.state.commit_changes(staged), theirNextRevocationHash)
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, proposal.index))
      val (ourCommitTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, previousCommitment.tx.txIn, proposal.state, ourRevocationHash, theirNextRevocationHash)
      them ! update_commit(ourSigForThem, ack_in)
      goto(NORMAL_WAIT_FOR_REV) using d.copy(ack_out = ack_out + 1, staged = Nil, next = WaitForRev(proposal))

    case Event(msg@update_commit(theirSig, theirAck), d@DATA_NORMAL(ack_in, ack_out, ourParams, theirParams, shaChain, htlcIdx, staged, previousCommitment, ReadyForSig(theirNextRevocationHash))) =>
      // counterparty initiated a new commitment
      val committed_changes = staged.filter(c => c.direction == IN || c.ack <= theirAck)
      val uncommitted_changes = staged.filterNot(committed_changes.contains(_))
      // TODO : we should check that this is the correct state (see acknowledge discussion)
      val proposal = UpdateProposal(previousCommitment.index + 1, previousCommitment.state.commit_changes(committed_changes), theirNextRevocationHash)
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, proposal.index))
      val (ourCommitTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, previousCommitment.tx.txIn, proposal.state, ourRevocationHash, proposal.theirRevocationHash)
      val signedCommitTx = sign_our_commitment_tx(ourParams, theirParams, ourCommitTx, theirSig)
      val ok = Try(Transaction.correctlySpends(signedCommitTx, Map(previousCommitment.tx.txIn(0).outPoint -> anchorPubkeyScript(ourCommitPubKey, theirParams.commitPubKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      ok match {
        case false =>
          them ! error(Some("Bad signature"))
          publish_ourcommit(previousCommitment)
          goto(CLOSING) using DATA_CLOSING(ack_in = ack_in + 1, ack_out = ack_out + 1, ourParams, theirParams, shaChain, previousCommitment, ourCommitPublished = Some(previousCommitment.tx))
        case true =>
          val preimage = ShaChain.shaChainFromSeed(ourParams.shaSeed, previousCommitment.index)
          val ourNextRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, proposal.index + 1))
          them ! update_revocation(preimage, ourNextRevocationHash, ack_in + 1)
          them ! update_commit(ourSigForThem, ack_in + 1)
          goto(NORMAL_WAIT_FOR_REV_THEIRSIG) using d.copy(ack_in = ack_in + 1, ack_out = ack_out + 2, staged = uncommitted_changes, next = WaitForRevTheirSig(Commitment(proposal.index, signedCommitTx, proposal.state, proposal.theirRevocationHash)))
      }
  })

  when(NORMAL_WAIT_FOR_REV)(update_handler orElse {
    case Event(update_revocation(theirRevocationPreimage, theirNextRevocationHash, theirAck), d@DATA_NORMAL(ack_in, ack_out, ourParams, theirParams, shaChain, htlcIdx, staged, previousCommitment, WaitForRev(proposal))) =>
      // counterparty replied with the signature for its new commitment tx, and revocationPreimage
      val revocationHashCheck = new BinaryData(previousCommitment.theirRevocationHash) == new BinaryData(Crypto.sha256(theirRevocationPreimage))
      if (revocationHashCheck) {
        goto(NORMAL_WAIT_FOR_SIG) using d.copy(ack_in = ack_in + 1, next = WaitForSig(proposal, theirNextRevocationHash))
      } else {
        log.warning(s"the revocation preimage they gave us is wrong! hash=${previousCommitment.theirRevocationHash} preimage=$theirRevocationPreimage")
        them ! error(Some("Wrong preimage"))
        publish_ourcommit(previousCommitment)
        goto(CLOSING) using DATA_CLOSING(ack_in = ack_in + 1, ack_out = ack_out + 1, ourParams, theirParams, shaChain, previousCommitment, ourCommitPublished = Some(previousCommitment.tx))
      }

    case Event(msg@update_commit(theirSig, theirAck), DATA_NORMAL(ack_in, ack_out, ourParams, theirParams, shaChain, htlcIdx, staged, previousCommitment, WaitForRev(proposal))) =>
      // TODO : IGNORED FOR NOW
      log.warning(s"ignored $msg")
      stay
  })

  when(NORMAL_WAIT_FOR_REV_THEIRSIG)(update_handler orElse {
    case Event(update_revocation(theirRevocationPreimage, theirNextRevocationHash, theirAck), d@DATA_NORMAL(ack_in, ack_out, ourParams, theirParams, shaChain, htlcIdx, staged, previousCommitment, WaitForRevTheirSig(nextCommitment))) =>
      // counterparty replied with the signature for its new commitment tx, and revocationPreimage
      val revocationHashCheck = new BinaryData(previousCommitment.theirRevocationHash) == new BinaryData(Crypto.sha256(theirRevocationPreimage))
      if (revocationHashCheck) {
        goto(NORMAL) using d.copy(ack_in = ack_in + 1, commitment = nextCommitment, next = ReadyForSig(theirNextRevocationHash))
      } else {
        log.warning(s"the revocation preimage they gave us is wrong! hash=${previousCommitment.theirRevocationHash} preimage=$theirRevocationPreimage")
        them ! error(Some("Wrong preimage"))
        publish_ourcommit(previousCommitment)
        goto(CLOSING) using DATA_CLOSING(ack_in = ack_in + 1, ack_out = ack_out + 1, ourParams, theirParams, shaChain, previousCommitment, ourCommitPublished = Some(previousCommitment.tx))
      }
  })

  when(NORMAL_WAIT_FOR_SIG)(update_handler orElse {
    case Event(update_commit(theirSig, theirAck), d@DATA_NORMAL(ack_in, ack_out, ourParams, theirParams, shaChain, htlcIdx, staged, previousCommitment, WaitForSig(proposal, theirNextRevocationHash))) =>
      // counterparty replied with the signature for the new commitment tx
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, proposal.index))
      val (ourCommitTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, previousCommitment.tx.txIn, proposal.state, ourRevocationHash, proposal.theirRevocationHash)
      val signedCommitTx = sign_our_commitment_tx(ourParams, theirParams, ourCommitTx, theirSig)
      val ok = Try(Transaction.correctlySpends(signedCommitTx, Map(previousCommitment.tx.txIn(0).outPoint -> anchorPubkeyScript(ourCommitPubKey, theirParams.commitPubKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      ok match {
        case false =>
          them ! error(Some("Bad signature"))
          publish_ourcommit(previousCommitment)
          goto(CLOSING) using DATA_CLOSING(ack_in = ack_in + 1, ack_out = ack_out + 1, ourParams, theirParams, shaChain, previousCommitment, ourCommitPublished = Some(previousCommitment.tx))
        case true =>
          val preimage = ShaChain.shaChainFromSeed(ourParams.shaSeed, previousCommitment.index)
          val ourNextRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, proposal.index + 1))
          them ! update_revocation(preimage, ourNextRevocationHash, ack_in + 1)
          goto(NORMAL) using d.copy(ack_in = ack_in + 1, ack_out = ack_out + 1, commitment = Commitment(proposal.index, signedCommitTx, proposal.state, proposal.theirRevocationHash), next = ReadyForSig(theirNextRevocationHash))
      }
  })


  /*case Event(CMD_SEND_HTLC_ROUTEFAIL(rHash), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
    // we couldn't reach upstream node, so we update the commitment tx, removing the corresponding htlc
    val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, commitment.index + 1))
    val newState = commitment.state.htlc_remove(rHash)
    them ! update_routefail_htlc(ourRevocationHash, rHash)
    goto(WAIT_FOR_HTLC_ACCEPT(priority)) using DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, shaChain, commitment, UpdateProposal(commitment.index + 1, newState))*/

  /*case Event(CMD_SEND_HTLC_TIMEDOUT(rHash), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
    // the upstream node didn't provide the r value in time
    // we couldn't reach upstream node, so we update the commitment tx, removing the corresponding htlc
    val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, commitment.index + 1))
    val newState = commitment.state.htlc_remove(rHash)
    them ! update_timedout_htlc(ourRevocationHash, rHash)
    goto(WAIT_FOR_HTLC_ACCEPT(priority)) using DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, shaChain, commitment, UpdateProposal(commitment.index + 1, newState))*/

  /*case Event(update_timedout_htlc(theirRevocationHash, rHash), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
    val newState = commitment.state.htlc_remove(rHash)
    val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, commitment.index + 1))
    val (newCommitmentTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, commitment.tx.txIn, newState, ourRevocationHash, theirRevocationHash)
    them ! update_accept(ourSigForThem, ourRevocationHash)
    goto(WAIT_FOR_UPDATE_SIG(priority)) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, shaChain, commitment, Commitment(commitment.index + 1, newCommitmentTx, newState, theirRevocationHash))*/

  /*case Event(c@CMD_SEND_HTLC_FULFILL(r), DATA_NORMAL(ourParams, theirParams, shaChain, commitment@Commitment(_, _, previousState, _))) =>
    // we paid upstream in exchange for r, now lets gets paid
    Try(previousState.htlc_fulfill(r)) match {
      case Success(newState) =>
        val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, commitment.index + 1))
        // Complete your HTLC: I have the R value, pay me!
        them ! update_fulfill_htlc(ourRevocationHash, r)
        goto(WAIT_FOR_HTLC_ACCEPT(priority)) using DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, shaChain, commitment, UpdateProposal(commitment.index + 1, newState))
      case Failure(t) =>
        log.error(t, s"command $c failed")
        stay
    }*/

  /*case Event(update_fulfill_htlc(theirRevocationHash, r), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
    // FIXME: is this the right moment to propagate this htlc ?
    // pm : probably not because if subsequent channel update fails we will already have paid the downstream channel
    // and we'll get our money back only after the timeout
    commitment.state.them.htlcs_received.find(_.rHash == bin2sha256(Crypto.sha256(r)))
      .map(htlc => htlc.previousChannelId match {
        case Some(previousChannelId) =>
          log.info(s"resolving channelId=$previousChannelId")
          Boot.system.actorSelection(Register.actorPathToChannelId(previousChannelId))
            .resolveOne(3 seconds)
            .onComplete {
              case Success(downstream) =>
                log.info(s"forwarding r value to downstream=$downstream")
                downstream ! CMD_SEND_HTLC_FULFILL(r)
              case Failure(t: Throwable) =>
                log.warning(s"couldn't resolve downstream node, htlc will timeout", t)
            }
        case None =>
          log.info(s"looks like I was the origin payer for htlc $htlc")
      })
    val newState = commitment.state.htlc_fulfill(r)
    val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, commitment.index + 1))
    val (newCommitmentTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, commitment.tx.txIn, newState, ourRevocationHash, theirRevocationHash)
    them ! update_accept(ourSigForThem, ourRevocationHash)
    goto(WAIT_FOR_UPDATE_SIG(priority)) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, shaChain, commitment, Commitment(commitment.index + 1, newCommitmentTx, newState, theirRevocationHash))*/


  /*def WAIT_FOR_HTLC_ACCEPT_HIGHPRIO_handler: StateFunction = {
    case Event(update_accept(theirSig, theirRevocationHash), DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, shaChain, previousCommitment, UpdateProposal(newIndex, newState))) =>
      // counterparty replied with the signature for the new commitment tx
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, newIndex))
      val (ourCommitTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, previousCommitment.tx.txIn, newState, ourRevocationHash, theirRevocationHash)
      val signedCommitTx = sign_our_commitment_tx(ourParams, theirParams, ourCommitTx, theirSig)
      val ok = Try(Transaction.correctlySpends(signedCommitTx, Map(previousCommitment.tx.txIn(0).outPoint -> anchorPubkeyScript(ourCommitPubKey, theirParams.commitPubKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      ok match {
        case false =>
          them ! error(Some("Bad signature"))
          publish_ourcommit(previousCommitment)
          goto(CLOSING) using DATA_CLOSING(ourParams, theirParams, shaChain, previousCommitment, ourCommitPublished = Some(previousCommitment.tx))
        case true =>
          val preimage = ShaChain.shaChainFromSeed(ourParams.shaSeed, previousCommitment.index)
          them ! update_signature(ourSigForThem, preimage)
          goto(WAIT_FOR_UPDATE_COMPLETE(priority)) using DATA_WAIT_FOR_UPDATE_COMPLETE(ourParams, theirParams, shaChain, previousCommitment, Commitment(newIndex, signedCommitTx, newState, theirRevocationHash))
      }

    case Event(update_decline_htlc(reason), DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, shaChain, previousCommitmentTx, _)) =>
      log.info(s"counterparty declined htlc update with reason=$reason")
      goto(NORMAL(priority)) using DATA_NORMAL(ourParams, theirParams, shaChain, previousCommitmentTx)

    case Event(pkt: close_channel, d: CurrentCommitment) =>
      val (finalTx, res) = handle_pkt_close(pkt, d.ourParams, d.theirParams, d.commitment)
      blockchain ! Publish(finalTx)
      them ! res
      goto(WAIT_FOR_CLOSE_ACK) using DATA_WAIT_FOR_CLOSE_ACK(d.ourParams, d.theirParams, d.shaChain, d.commitment, finalTx)

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: DATA_WAIT_FOR_HTLC_ACCEPT) if (isTheirCommit(tx, d.ourParams, d.theirParams, d.commitment)) =>
      them ! handle_theircommit(tx, d.ourParams, d.theirParams, d.shaChain, d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, theirCommitPublished = Some(tx))

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: CurrentCommitment) if (isRevokedCommit(tx)) =>
      them ! handle_revoked(tx)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, revokedPublished = tx :: Nil)

    case Event((BITCOIN_ANCHOR_SPENT, _), _) =>
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, d: CurrentCommitment) =>
      publish_ourcommit(d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, ourCommitPublished = Some(d.commitment.tx))
  }

  when(WAIT_FOR_HTLC_ACCEPT_HIGHPRIO)(WAIT_FOR_HTLC_ACCEPT_HIGHPRIO_handler)

  when(WAIT_FOR_HTLC_ACCEPT_LOWPRIO)(WAIT_FOR_HTLC_ACCEPT_HIGHPRIO_handler orElse {
    case Event(htlc@update_add_htlc(theirRevocationHash, amount, rHash, expiry, nodeIds), DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, shaChain, commitment, _)) =>
      val newState = commitment.state.htlc_receive(Htlc(amount, rHash, expiry, nodeIds, None))
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, commitment.index + 1))
      val (newCommitmentTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, commitment.tx.txIn, newState, ourRevocationHash, theirRevocationHash)
      them ! update_accept(ourSigForThem, ourRevocationHash)
      goto(WAIT_FOR_UPDATE_SIG(priority)) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, shaChain, commitment, Commitment(commitment.index + 1, newCommitmentTx, newState, theirRevocationHash))

    case Event(update_routefail_htlc(theirRevocationHash, rHash), DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, shaChain, commitment, _)) =>
      val newState = commitment.state.htlc_remove(rHash)
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, commitment.index + 1))
      val (newCommitmentTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, commitment.tx.txIn, newState, ourRevocationHash, theirRevocationHash)
      them ! update_accept(ourSigForThem, ourRevocationHash)
      goto(WAIT_FOR_UPDATE_SIG(priority)) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, shaChain, commitment, Commitment(commitment.index + 1, newCommitmentTx, newState, theirRevocationHash))

    case Event(update_timedout_htlc(theirRevocationHash, rHash), DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, shaChain, commitment, _)) =>
      val newState = commitment.state.htlc_remove(rHash)
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, commitment.index + 1))
      val (newCommitmentTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, commitment.tx.txIn, newState, ourRevocationHash, theirRevocationHash)
      them ! update_accept(ourSigForThem, ourRevocationHash)
      goto(WAIT_FOR_UPDATE_SIG(priority)) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, shaChain, commitment, Commitment(commitment.index + 1, newCommitmentTx, newState, theirRevocationHash))

    case Event(update_fulfill_htlc(theirRevocationHash, r), DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, shaChain, commitment, _)) =>
      val newState = commitment.state.htlc_fulfill(r)
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, commitment.index + 1))
      val (newCommitmentTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, commitment.tx.txIn, newState, ourRevocationHash, theirRevocationHash)
      them ! update_accept(ourSigForThem, ourRevocationHash)
      goto(WAIT_FOR_UPDATE_SIG(priority)) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, shaChain, commitment, Commitment(commitment.index + 1, newCommitmentTx, newState, theirRevocationHash))
  })

  def WAIT_FOR_UPDATE_SIG_handler: StateFunction = {
    case Event(update_signature(theirSig, theirRevocationPreimage), DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, shaChain, previousCommitment, newCommitment)) =>
      // counterparty replied with the signature for its new commitment tx, and revocationPreimage
      val revocationHashCheck = new BinaryData(previousCommitment.theirRevocationHash) == new BinaryData(Crypto.sha256(theirRevocationPreimage))
      // we build our commitment tx, sign it and check that it is spendable using the counterparty's sig
      val signedCommitTx = sign_our_commitment_tx(ourParams, theirParams, newCommitment.tx, theirSig)
      val newSigCheck = Try(Transaction.correctlySpends(signedCommitTx, Map(previousCommitment.tx.txIn(0).outPoint -> anchorPubkeyScript(ourCommitPubKey, theirParams.commitPubKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      (revocationHashCheck, newSigCheck) match {
        case (true, true) =>
          val preimage = ShaChain.shaChainFromSeed(ourParams.shaSeed, previousCommitment.index)
          them ! update_complete(preimage)

          goto(NORMAL(priority.invert)) using DATA_NORMAL(ourParams, theirParams, ShaChain.addHash(shaChain, theirRevocationPreimage, previousCommitment.index), newCommitment)
        case (true, false) =>
          log.warning(s"bad signature !")
          them ! error(Some("Bad signature"))
          publish_ourcommit(previousCommitment)
          goto(CLOSING) using DATA_CLOSING(ourParams, theirParams, shaChain, previousCommitment, ourCommitPublished = Some(previousCommitment.tx))
        case (false, _) =>
          log.warning(s"the revocation preimage they gave us is wrong! hash=${previousCommitment.theirRevocationHash} preimage=$theirRevocationPreimage")
          them ! error(Some("Wrong preimage"))
          publish_ourcommit(previousCommitment)
          goto(CLOSING) using DATA_CLOSING(ourParams, theirParams, shaChain, previousCommitment, ourCommitPublished = Some(previousCommitment.tx))
      }

    case Event(cmd: CMD_CLOSE, d: CurrentCommitment) =>
      them ! handle_cmd_close(cmd, d.ourParams, d.theirParams, d.commitment)
      goto(WAIT_FOR_CLOSE_COMPLETE)

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: DATA_WAIT_FOR_UPDATE_SIG) if (isTheirCommit(tx, d.ourParams, d.theirParams, d.commitment)) =>
      them ! handle_theircommit(tx, d.ourParams, d.theirParams, d.shaChain, d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, theirCommitPublished = Some(tx))

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: CurrentCommitment) if (isRevokedCommit(tx)) =>
      them ! handle_revoked(tx)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, revokedPublished = tx :: Nil)

    case Event((BITCOIN_ANCHOR_SPENT, _), _) =>
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, d: CurrentCommitment) =>
      publish_ourcommit(d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, ourCommitPublished = Some(d.commitment.tx))

  }

  when(WAIT_FOR_UPDATE_SIG_HIGHPRIO)(WAIT_FOR_UPDATE_SIG_handler)

  when(WAIT_FOR_UPDATE_SIG_LOWPRIO)(WAIT_FOR_UPDATE_SIG_handler)

  def WAIT_FOR_UPDATE_COMPLETE_handler: StateFunction = {
    case Event(update_complete(theirRevocationPreimage), DATA_WAIT_FOR_UPDATE_COMPLETE(ourParams, theirParams, shaChain, previousCommitment, newCommitment)) =>
      val ok = new BinaryData(previousCommitment.theirRevocationHash) == new BinaryData(Crypto.sha256(theirRevocationPreimage))
      ok match {
        case false =>
          log.warning(s"the revocation preimage they gave us is wrong! hash=${previousCommitment.theirRevocationHash} preimage=$theirRevocationPreimage")
          them ! error(Some("Wrong preimage"))
          publish_ourcommit(previousCommitment)
          goto(CLOSING) using DATA_CLOSING(ourParams, theirParams, shaChain, previousCommitment, ourCommitPublished = Some(previousCommitment.tx))
        case true =>
          goto(NORMAL(priority.invert)) using DATA_NORMAL(ourParams, theirParams, ShaChain.addHash(shaChain, theirRevocationPreimage, previousCommitment.index), newCommitment)
      }

    case Event(pkt: close_channel, d: CurrentCommitment) =>
      val (finalTx, res) = handle_pkt_close(pkt, d.ourParams, d.theirParams, d.commitment)
      blockchain ! Publish(finalTx)
      them ! res
      goto(WAIT_FOR_CLOSE_ACK) using DATA_WAIT_FOR_CLOSE_ACK(d.ourParams, d.theirParams, d.shaChain, d.commitment, finalTx)

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: DATA_WAIT_FOR_UPDATE_COMPLETE) if (isTheirCommit(tx, d.ourParams, d.theirParams, d.commitment)) =>
      them ! handle_theircommit(tx, d.ourParams, d.theirParams, d.shaChain, d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, theirCommitPublished = Some(tx))

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: CurrentCommitment) if (isRevokedCommit(tx)) =>
      them ! handle_revoked(tx)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, revokedPublished = tx :: Nil)

    case Event((BITCOIN_ANCHOR_SPENT, _), _) =>
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, d: CurrentCommitment) =>
      publish_ourcommit(d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, ourCommitPublished = Some(d.commitment.tx))
  }

  when(WAIT_FOR_UPDATE_COMPLETE_HIGHPRIO)(WAIT_FOR_UPDATE_COMPLETE_handler)

  when(WAIT_FOR_UPDATE_COMPLETE_LOWPRIO)(WAIT_FOR_UPDATE_COMPLETE_handler)*/

  /*
           .d8888b.  888      .d88888b.   .d8888b. 8888888 888b    888  .d8888b.
          d88P  Y88b 888     d88P" "Y88b d88P  Y88b  888   8888b   888 d88P  Y88b
          888    888 888     888     888 Y88b.       888   88888b  888 888    888
          888        888     888     888  "Y888b.    888   888Y88b 888 888
          888        888     888     888     "Y88b.  888   888 Y88b888 888  88888
          888    888 888     888     888       "888  888   888  Y88888 888    888
          Y88b  d88P 888     Y88b. .d88P Y88b  d88P  888   888   Y8888 Y88b  d88P
           "Y8888P"  88888888 "Y88888P"   "Y8888P" 8888888 888    Y888  "Y8888P88
   */

  /*when(WAIT_FOR_CLOSE_COMPLETE) {
    case Event(close_channel_complete(theirSig), d: CurrentCommitment) =>
      //TODO we should use the closing fee in pkts
      val closingState = d.commitment.state.adjust_fees(Globals.closing_fee * 1000, d.ourParams.anchorAmount.isDefined)
      val finalTx = makeFinalTx(d.commitment.tx.txIn, ourFinalPubKey, d.theirParams.finalPubKey, closingState)
      val signedFinalTx = sign_our_commitment_tx(d.ourParams, d.theirParams, finalTx, theirSig)
      val ok = Try(Transaction.correctlySpends(signedFinalTx, Map(signedFinalTx.txIn(0).outPoint -> anchorPubkeyScript(ourCommitPubKey, d.theirParams.commitPubKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      ok match {
        case false =>
          them ! error(Some("Bad signature"))
          publish_ourcommit(d.commitment)
          goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, ourCommitPublished = Some(d.commitment.tx))
        case true =>
          them ! close_channel_ack()
          blockchain ! Publish(signedFinalTx)
          goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, mutualClosePublished = Some(signedFinalTx))
      }

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: CurrentCommitment) if (isMutualClose(tx, d.ourParams, d.theirParams, d.commitment)) =>
      // it is possible that we received this before the close_channel_complete, we may still receive the latter
      log.info(s"mutual close detected: $tx")
      blockchain ! WatchConfirmed(self, tx.txid, d.ourParams.minDepth, BITCOIN_CLOSE_DONE)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, mutualClosePublished = Some(tx))

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: CurrentCommitment) if (isTheirCommit(tx, d.ourParams, d.theirParams, d.commitment)) =>
      them ! handle_theircommit(tx, d.ourParams, d.theirParams, d.shaChain, d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, theirCommitPublished = Some(tx))

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: CurrentCommitment) if (isRevokedCommit(tx)) =>
      them ! handle_revoked(tx)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, revokedPublished = tx :: Nil)

    case Event((BITCOIN_ANCHOR_SPENT, _), _) =>
      goto(ERR_INFORMATION_LEAK)
  }*/

  /**
    * At this point we have already published the closing tx
    */
  /*when(WAIT_FOR_CLOSE_ACK) {
    case Event(close_channel_ack(), d: DATA_WAIT_FOR_CLOSE_ACK) =>
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, mutualClosePublished = Some(d.mutualCloseTx))

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: CurrentCommitment) if (isMutualClose(tx, d.ourParams, d.theirParams, d.commitment)) =>
      // it is possible that we received this before the close_channel_ack, we may still receive the latter
      log.info(s"mutual close detected: $tx")
      blockchain ! WatchConfirmed(self, tx.txid, d.ourParams.minDepth, BITCOIN_CLOSE_DONE)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, mutualClosePublished = Some(tx))

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: CurrentCommitment) if (isTheirCommit(tx, d.ourParams, d.theirParams, d.commitment)) =>
      them ! handle_theircommit(tx, d.ourParams, d.theirParams, d.shaChain, d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, theirCommitPublished = Some(tx))

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: CurrentCommitment) if (isRevokedCommit(tx)) =>
      them ! handle_revoked(tx)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, revokedPublished = tx :: Nil)

    case Event((BITCOIN_ANCHOR_SPENT, _), _) =>
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, d: DATA_WAIT_FOR_CLOSE_ACK) =>
      // no-op, because at this point we have already published the mutual close tx on the blockchain
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, mutualClosePublished = Some(d.mutualCloseTx))
  }*/

  /**
    * We enter this state when the anchor is spent by at least one tx
    * We leave this state when tx (or txes) spending the spending tx is buried deep enough in the blockchain
    */
  when(CLOSING) {

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d@DATA_CLOSING(_, _, ourParams, theirParams, _, commitment, _, _, _, _)) if (isMutualClose(tx, ourParams, theirParams, commitment)) =>
      log.info(s"mutual close detected: $tx")
      blockchain ! WatchConfirmed(self, tx.txid, ourParams.minDepth, BITCOIN_CLOSE_DONE)
      // wait for BITCOIN_CLOSE_DONE
      // should we override the previous tx? (which may be different because of malleability)
      stay using d.copy(mutualClosePublished = Some(tx))

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), DATA_CLOSING(_, _, _, _, _, commitment, _, _, _, _)) if (isOurCommit(tx, commitment)) =>
      log.info(s"our commit detected: $tx")
      handle_ourcommit()
      stay

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d@DATA_CLOSING(_,_,  ourParams, theirParams, shaChain, commitment, _, _, _, _)) if (isTheirCommit(tx, ourParams, theirParams, commitment)) =>
      handle_theircommit(tx, ourParams, theirParams, shaChain, commitment)
      stay using d.copy(theirCommitPublished = Some(tx))

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), d: DATA_CLOSING) if (isRevokedCommit(tx)) =>
      them ! handle_revoked(tx)
      stay using d.copy(revokedPublished = tx +: d.revokedPublished)

    case Event((BITCOIN_ANCHOR_SPENT, tx: Transaction), _) =>
      // somebody managed to spend the anchor...
      // we're fucked
      goto(ERR_INFORMATION_LEAK)

    case Event(INPUT_NO_MORE_HTLCS, _) =>
      // should we do something ???
      // wait for BITCOIN_ANCHOR_OURCOMMIT_DELAYPASSED
      stay

    case Event(BITCOIN_ANCHOR_OURCOMMIT_DELAYPASSED, DATA_CLOSING(_, _, ourParams, theirParams, _, _, _, Some(ourCommitPublished), _, _)) =>
      handle_ourcommit_delaypassed()
      stay

    case Event(pkt: error, _) =>
      // there is nothing to do here
      stay

    case Event(BITCOIN_CLOSE_DONE, _) => goto(CLOSED)

    case Event(BITCOIN_SPEND_OURS_DONE, _) => goto(CLOSED)

    case Event(BITCOIN_SPEND_THEIRS_DONE, _) => goto(CLOSED)

    case Event(BITCOIN_STEAL_DONE, _) => goto(CLOSED)

    /*case Event(p: close_channel_complete, _) => stay // if bitcoin network is faster than lightning network (very unlikely to happen)

    case Event(p: close_channel_ack, _) => stay // if bitcoin network is faster than lightning network (very unlikely to happen)*/
  }

  when(CLOSED) {
    case _ if false => stay // we don't want this to match so that whenUnhandled works
  }

  whenUnhandled {

    case Event(BITCOIN_ANCHOR_LOST, _) => goto(ERR_ANCHOR_LOST)

    case Event(CMD_GETSTATE, _) =>
      sender ! stateName
      stay

    case Event(CMD_GETSTATEDATA, _) =>
      sender ! stateData
      stay

    case Event(CMD_GETINFO, _) =>
      sender ! RES_GETINFO(theirNodeId, stateData match {
        case c: CurrentCommitment => c.commitment.anchorId
        case _ => "unknown"
      }, stateName, stateData)
      stay

    // TODO : them ! error(Some("Unexpected message")) ?

  }

  /*
          888    888 8888888888 888      8888888b.  8888888888 8888888b.   .d8888b.
          888    888 888        888      888   Y88b 888        888   Y88b d88P  Y88b
          888    888 888        888      888    888 888        888    888 Y88b.
          8888888888 8888888    888      888   d88P 8888888    888   d88P  "Y888b.
          888    888 888        888      8888888P"  888        8888888P"      "Y88b.
          888    888 888        888      888        888        888 T88b         "888
          888    888 888        888      888        888        888  T88b  Y88b  d88P
          888    888 8888888888 88888888 888        8888888888 888   T88b  "Y8888P"
  */

  /**
    * Something went wrong, we publish the current commitment transaction
    */
  def publish_ourcommit(commitment: Commitment) = {
    log.info(s"publishing our commitment tx: ${commitment.tx}")
    blockchain ! Publish(commitment.tx)
  }

  def handle_ourcommit() = {
    // if (HTLCs)
    //    handle them (how ???)
    //    wait for INPUT_NO_MORE_HTLCS
    // else
    //    wait for BITCOIN_ANCHOR_OURCOMMIT_DELAYPASSED
  }

  def handle_ourcommit_delaypassed() = {
    // spend ours
    // wait for BITCOIN_SPEND_OURS_DONE
  }

  /**
    * They published their current commitment transaction
    */
  def handle_theircommit(publishedTx: Transaction, ourParams: OurChannelParams, theirParams: TheirChannelParams, shaChain: ShaChain, commitment: Commitment): error = {
    log.info(s"their commit detected: $publishedTx")
    // if (HTLCs)
    //    handle them (how ???)
    //    wait for INPUT_NO_MORE_HTLCS
    // else
    //    spend theirs
    //    wait for BITCOIN_SPEND_THEIRS_DONE
    error(Some("Commit tx noticed"))
  }

  def handle_revoked(publishedTx: Transaction): error = {
    log.info(s"revoked commit detected: $publishedTx")
    // steal immediately
    // wait for BITCOIN_STEAL_DONE
    error(Some("Otherspend noticed"))
  }
}




