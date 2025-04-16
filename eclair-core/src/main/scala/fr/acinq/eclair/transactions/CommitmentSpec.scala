/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.ScriptTree
import fr.acinq.bitcoin.scalacompat.Crypto.XonlyPublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, LexicographicalOrdering, OP_CHECKSIG, OP_DUP, OP_EQUALVERIFY, OP_HASH160, OP_PUSHDATA, Satoshi, SatoshiLong, Script, ScriptElt, TxOut}
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.transactions.Transactions.{CommitmentFormat, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat}
import fr.acinq.eclair.wire.protocol._

/**
 * Created by PM on 07/12/2016.
 */

sealed trait RedeemInfo {
  def publicKeyScript: Seq[ScriptElt]
}

object RedeemInfo {
  case class SegwitV0(redeemScript: Seq[ScriptElt]) extends RedeemInfo {
    override def publicKeyScript: Seq[ScriptElt] = redeemScript match {
      case OP_DUP :: OP_HASH160 :: OP_PUSHDATA(data, _) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil if data.size == 20 => Script.pay2wpkh(data)
      case _ => Script.pay2wsh(redeemScript)
    }
  }

  case class TaprootScriptPath(internalKey: XonlyPublicKey, scriptTree: ScriptTree, leafHash: ByteVector32) extends RedeemInfo {

    import fr.acinq.bitcoin.scalacompat.KotlinUtils._

    val leaf: ScriptTree.Leaf = Option(scriptTree.findScript(leafHash)).getOrElse(throw new IllegalArgumentException(s"leaf $leafHash not found in script tree"))

    override def publicKeyScript: Seq[ScriptElt] = Script.pay2tr(internalKey, Some(scriptTree))
  }

  case class TaprootKeyPath(internalKey: XonlyPublicKey, scriptTree_opt: Option[ScriptTree]) extends RedeemInfo {
    override def publicKeyScript: Seq[ScriptElt] = Script.pay2tr(internalKey, scriptTree_opt)
  }
}

sealed trait CommitmentOutput {
  val amount: Satoshi
  val redeemInfo: RedeemInfo
  val txOut: TxOut = TxOut(amount, redeemInfo.publicKeyScript)
}

object CommitmentOutput {
  // @formatter:off
  case class ToLocal(amount: Satoshi, redeemInfo: RedeemInfo) extends CommitmentOutput
  case class ToRemote(amount: Satoshi, redeemInfo: RedeemInfo) extends CommitmentOutput
  case class ToLocalAnchor(amount: Satoshi, redeemInfo: RedeemInfo) extends CommitmentOutput
  case class ToRemoteAnchor(amount: Satoshi, redeemInfo: RedeemInfo) extends CommitmentOutput
  case class HtlcSuccessOutput(amount: Satoshi, redeemInfo: RedeemInfo) {
    val txOut: TxOut = TxOut(amount, redeemInfo.publicKeyScript)
  }
  case class InHtlcWithoutHtlcSuccess(amount: Satoshi, incomingHtlc: IncomingHtlc, redeemInfo: RedeemInfo) extends CommitmentOutput
  case class InHtlc(amount: Satoshi, incomingHtlc: IncomingHtlc, redeemInfo: RedeemInfo, htlcSuccessOutput: HtlcSuccessOutput) extends CommitmentOutput
  case class HtlcTimeoutOutput(amount: Satoshi, redeemInfo: RedeemInfo) {
    val txOut: TxOut = TxOut(amount, redeemInfo.publicKeyScript)
  }
  case class OutHtlcWithoutHtlcTimeout(amount: Satoshi, outgoingHtlc: OutgoingHtlc, redeemInfo: RedeemInfo) extends CommitmentOutput
  case class OutHtlc(amount: Satoshi, outgoingHtlc: OutgoingHtlc, redeemInfo: RedeemInfo, htlcTimeoutOutput: HtlcTimeoutOutput) extends CommitmentOutput
  // @formatter:on

  def isLessThan(a: CommitmentOutput, b: CommitmentOutput): Boolean = (a, b) match {
    case (a: OutHtlc, b: OutHtlc) if a.outgoingHtlc.add.paymentHash == b.outgoingHtlc.add.paymentHash && a.outgoingHtlc.add.amountMsat.truncateToSatoshi == b.outgoingHtlc.add.amountMsat.truncateToSatoshi =>
      a.outgoingHtlc.add.cltvExpiry <= b.outgoingHtlc.add.cltvExpiry
    case _ => LexicographicalOrdering.isLessThan(a.txOut, b.txOut)
  }
}

sealed trait DirectedHtlc {
  val add: UpdateAddHtlc

  def opposite: DirectedHtlc = this match {
    case IncomingHtlc(_) => OutgoingHtlc(add)
    case OutgoingHtlc(_) => IncomingHtlc(add)
  }

  def direction: String = this match {
    case IncomingHtlc(_) => "IN"
    case OutgoingHtlc(_) => "OUT"
  }
}

object DirectedHtlc {
  def incoming: PartialFunction[DirectedHtlc, UpdateAddHtlc] = {
    case h: IncomingHtlc => h.add
  }

  def outgoing: PartialFunction[DirectedHtlc, UpdateAddHtlc] = {
    case h: OutgoingHtlc => h.add
  }
}

case class IncomingHtlc(add: UpdateAddHtlc) extends DirectedHtlc

case class OutgoingHtlc(add: UpdateAddHtlc) extends DirectedHtlc

/**
 * Current state of a channel's off-chain commitment, that maps to a specific on-chain commitment transaction.
 * It contains all htlcs, even those that are below dust and won't have a corresponding on-chain output.
 */
final case class CommitmentSpec(htlcs: Set[DirectedHtlc], commitTxFeerate: FeeratePerKw, toLocal: MilliSatoshi, toRemote: MilliSatoshi) {

  def htlcTxFeerate(commitmentFormat: CommitmentFormat): FeeratePerKw = commitmentFormat match {
    case ZeroFeeHtlcTxAnchorOutputsCommitmentFormat => FeeratePerKw(0 sat)
    case _ => commitTxFeerate
  }

  def findIncomingHtlcById(id: Long): Option[IncomingHtlc] = htlcs.collectFirst { case htlc: IncomingHtlc if htlc.add.id == id => htlc }

  def findOutgoingHtlcById(id: Long): Option[OutgoingHtlc] = htlcs.collectFirst { case htlc: OutgoingHtlc if htlc.add.id == id => htlc }

}

object CommitmentSpec {

  def addHtlc(spec: CommitmentSpec, directedHtlc: DirectedHtlc): CommitmentSpec = {
    directedHtlc match {
      case OutgoingHtlc(add) => spec.copy(toLocal = spec.toLocal - add.amountMsat, htlcs = spec.htlcs + directedHtlc)
      case IncomingHtlc(add) => spec.copy(toRemote = spec.toRemote - add.amountMsat, htlcs = spec.htlcs + directedHtlc)
    }
  }

  def fulfillIncomingHtlc(spec: CommitmentSpec, htlcId: Long): CommitmentSpec = {
    spec.findIncomingHtlcById(htlcId) match {
      case Some(htlc) => spec.copy(toLocal = spec.toLocal + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
      case None => throw new RuntimeException(s"cannot find htlc id=$htlcId")
    }
  }

  def fulfillOutgoingHtlc(spec: CommitmentSpec, htlcId: Long): CommitmentSpec = {
    spec.findOutgoingHtlcById(htlcId) match {
      case Some(htlc) => spec.copy(toRemote = spec.toRemote + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
      case None => throw new RuntimeException(s"cannot find htlc id=$htlcId")
    }
  }

  def failIncomingHtlc(spec: CommitmentSpec, htlcId: Long): CommitmentSpec = {
    spec.findIncomingHtlcById(htlcId) match {
      case Some(htlc) => spec.copy(toRemote = spec.toRemote + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
      case None => throw new RuntimeException(s"cannot find htlc id=$htlcId")
    }
  }

  def failOutgoingHtlc(spec: CommitmentSpec, htlcId: Long): CommitmentSpec = {
    spec.findOutgoingHtlcById(htlcId) match {
      case Some(htlc) => spec.copy(toLocal = spec.toLocal + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
      case None => throw new RuntimeException(s"cannot find htlc id=$htlcId")
    }
  }

  /**
   * Changes to a commitment are applied in batches, when we receive new signatures or revoke a previous commitment.
   * This function applies a batch of pending changes and returns the updated off-chain channel state.
   */
  def reduce(localCommitSpec: CommitmentSpec, localChanges: List[UpdateMessage], remoteChanges: List[UpdateMessage]): CommitmentSpec = {
    val spec1 = localChanges.foldLeft(localCommitSpec) {
      case (spec, u: UpdateAddHtlc) => addHtlc(spec, OutgoingHtlc(u))
      case (spec, _) => spec
    }
    val spec2 = remoteChanges.foldLeft(spec1) {
      case (spec, u: UpdateAddHtlc) => addHtlc(spec, IncomingHtlc(u))
      case (spec, _) => spec
    }
    val spec3 = localChanges.foldLeft(spec2) {
      case (spec, u: UpdateFulfillHtlc) => fulfillIncomingHtlc(spec, u.id)
      case (spec, u: UpdateFailHtlc) => failIncomingHtlc(spec, u.id)
      case (spec, u: UpdateFailMalformedHtlc) => failIncomingHtlc(spec, u.id)
      case (spec, _) => spec
    }
    val spec4 = remoteChanges.foldLeft(spec3) {
      case (spec, u: UpdateFulfillHtlc) => fulfillOutgoingHtlc(spec, u.id)
      case (spec, u: UpdateFailHtlc) => failOutgoingHtlc(spec, u.id)
      case (spec, u: UpdateFailMalformedHtlc) => failOutgoingHtlc(spec, u.id)
      case (spec, _) => spec
    }
    val spec5 = (localChanges ++ remoteChanges).foldLeft(spec4) {
      case (spec, u: UpdateFee) => spec.copy(commitTxFeerate = u.feeratePerKw)
      case (spec, _) => spec
    }
    spec5
  }

}