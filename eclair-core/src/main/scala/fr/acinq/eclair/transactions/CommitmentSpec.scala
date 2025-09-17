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

import fr.acinq.bitcoin.scalacompat.{LexicographicalOrdering, SatoshiLong, TxOut}
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.transactions.Transactions.{CommitmentFormat, DefaultCommitmentFormat, PhoenixSimpleTaprootChannelCommitmentFormat, UnsafeLegacyAnchorOutputsCommitmentFormat, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat, ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat}
import fr.acinq.eclair.wire.protocol._

/**
 * Created by PM on 07/12/2016.
 */

sealed trait CommitmentOutput {
  val txOut: TxOut
}

object CommitmentOutput {
  // @formatter:off
  case class ToLocal(txOut: TxOut) extends CommitmentOutput
  case class ToRemote(txOut: TxOut) extends CommitmentOutput
  case class ToLocalAnchor(txOut: TxOut) extends CommitmentOutput
  case class ToRemoteAnchor(txOut: TxOut) extends CommitmentOutput
  // If there is an output for an HTLC in the commit tx, there is also a 2nd-level HTLC tx.
  case class InHtlc(htlc: IncomingHtlc, txOut: TxOut, htlcDelayedOutput: TxOut) extends CommitmentOutput
  case class OutHtlc(htlc: OutgoingHtlc, txOut: TxOut, htlcDelayedOutput: TxOut) extends CommitmentOutput
  // @formatter:on

  def isLessThan(a: CommitmentOutput, b: CommitmentOutput): Boolean = (a, b) match {
    // Outgoing HTLCs that have the same payment_hash will have the same script. If they also have the same amount, they
    // will produce exactly the same output: in that case, we must sort them using their expiry (see Bolt 3).
    // If they also have the same expiry, it doesn't really matter how we sort them, but in order to provide a fully
    // deterministic ordering (which is useful for tests), we sort them by htlc_id, which cannot be equal.
    case (a: OutHtlc, b: OutHtlc) if a.txOut == b.txOut && a.htlc.add.cltvExpiry == b.htlc.add.cltvExpiry => a.htlc.add.id <= b.htlc.add.id
    case (a: OutHtlc, b: OutHtlc) if a.txOut == b.txOut => a.htlc.add.cltvExpiry <= b.htlc.add.cltvExpiry
    // Incoming HTLCs that have the same payment_hash *and* expiry will have the same script. If they also have the same
    // amount, they will produce exactly the same output: just like offered HTLCs, it doesn't really matter how we sort
    // them, but we use the htlc_id to provide a fully deterministic ordering. Note that the expiry is included in the
    // script, so HTLCs with different expiries will have different scripts, and will thus be sorted by script as required
    // by Bolt 3.
    case (a: InHtlc, b: InHtlc) if a.txOut == b.txOut => a.htlc.add.id <= b.htlc.add.id
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
    case ZeroFeeHtlcTxAnchorOutputsCommitmentFormat | ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat => FeeratePerKw(0 sat)
    case UnsafeLegacyAnchorOutputsCommitmentFormat | PhoenixSimpleTaprootChannelCommitmentFormat => commitTxFeerate
    case DefaultCommitmentFormat => commitTxFeerate
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