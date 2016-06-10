package fr.acinq.eclair.channel

import com.trueaccord.scalapb.GeneratedMessage
import fr.acinq.bitcoin.{BinaryData, Crypto, Transaction, TxOut}
import fr.acinq.eclair.crypto.ShaChain
import lightning._

/**
  * Created by PM on 20/05/2016.
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
case object CLEARING extends State
case object NEGOCIATING extends State
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
/**
  *
  *
  * @param amount
  * @param rHash
  * @param expiry
  * @param nodeIds
  * @param originChannelId
  * @param id should only be provided in tests otherwise it will be assigned automatically
  */
final case class CMD_ADD_HTLC(amount: Int, rHash: sha256_hash, expiry: locktime, nodeIds: Seq[String] = Seq.empty[String], originChannelId: Option[BinaryData] = None, id: Option[Long] = None) extends Command
final case class CMD_FULFILL_HTLC(id: Long, r: sha256_hash) extends Command
final case class CMD_FAIL_HTLC(id: Long, reason: String) extends Command
case object CMD_SIGN extends Command
final case class CMD_CLOSE(scriptPubKey: Option[BinaryData]) extends Command
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

sealed trait Direction
case object IN extends Direction
case object OUT extends Direction

case class Htlc(direction: Direction, id: Long, amountMsat: Int, rHash: sha256_hash, expiry: locktime, nextNodeIds: Seq[String] = Nil, val previousChannelId: Option[BinaryData])

final case class CommitmentSpec(htlcs: Set[Htlc], feeRate: Long, initial_amount_us_msat : Long, initial_amount_them_msat: Long, amount_us_msat: Long, amount_them_msat: Long) {
  val totalFunds = amount_us_msat + amount_them_msat + htlcs.toSeq.map(_.amountMsat).sum
}

object TypeDefs {
  type Change = GeneratedMessage
}
import TypeDefs._
case class OurChanges(proposed: List[Change], signed: List[Change], acked: List[Change])
case class TheirChanges(proposed: List[Change], acked: List[Change])
case class Changes(ourChanges: OurChanges, theirChanges: TheirChanges)
case class OurCommit(index: Long, spec: CommitmentSpec, publishableTx: Transaction)
case class TheirCommit(index: Long, spec: CommitmentSpec, theirRevocationHash: sha256_hash)

final case class ClosingData(ourScriptPubKey: BinaryData, theirScriptPubKey: Option[BinaryData])

final case class DATA_OPEN_WAIT_FOR_OPEN              (ourParams: OurChannelParams) extends Data
final case class DATA_OPEN_WITH_ANCHOR_WAIT_FOR_ANCHOR(ourParams: OurChannelParams, theirParams: TheirChannelParams, theirRevocationHash: BinaryData, theirNextRevocationHash: sha256_hash) extends Data
final case class DATA_OPEN_WAIT_FOR_ANCHOR            (ourParams: OurChannelParams, theirParams: TheirChannelParams, theirRevocationHash: sha256_hash, theirNextRevocationHash: sha256_hash) extends Data
final case class DATA_OPEN_WAIT_FOR_COMMIT_SIG        (ourParams: OurChannelParams, theirParams: TheirChannelParams, anchorTx: Transaction, anchorOutputIndex: Int, initialCommitment: TheirCommit, theirNextRevocationHash: sha256_hash) extends Data
final case class DATA_OPEN_WAITING                    (commitments: Commitments, shaChain: ShaChain, deferred: Option[open_complete]) extends Data
final case class DATA_NORMAL                          (commitments: Commitments, shaChain: ShaChain, htlcIdx: Long,
                                                       ourClearing: Option[close_clearing]) extends Data
final case class DATA_CLEARING                        (commitments: Commitments, shaChain: ShaChain, htlcIdx: Long,
                                                       ourClearing: close_clearing, theirClearing: close_clearing) extends Data
final case class DATA_NEGOCIATING                     (commitments: Commitments, shaChain: ShaChain, htlcIdx: Long,
                                                       ourClearing: close_clearing, theirClearing: close_clearing, ourSignature: close_signature) extends Data
final case class DATA_CLOSING                         (commitments: Commitments, shaChain: ShaChain,
                                                       ourSignature: Option[close_signature] = None,
                                                       mutualClosePublished: Option[Transaction] = None,
                                                       ourCommitPublished: Option[Transaction] = None,
                                                       theirCommitPublished: Option[Transaction] = None,
                                                       revokedPublished: Seq[Transaction] = Seq()) extends Data {
  assert(mutualClosePublished.isDefined || ourCommitPublished.isDefined || theirCommitPublished.isDefined || revokedPublished.size > 0, "there should be at least one tx published in this state")
}

// @formatter:on
