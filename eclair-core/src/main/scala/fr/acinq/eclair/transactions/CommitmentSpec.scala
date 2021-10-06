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

import fr.acinq.bitcoin.{Satoshi, SatoshiLong}
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.blockchain.fee.{FeeratePerByte, FeeratePerKw}
import fr.acinq.eclair.transactions.Transactions.{CommitmentFormat, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat}
import fr.acinq.eclair.wire.protocol._

/**
 * Created by PM on 07/12/2016.
 */

sealed trait CommitmentOutput

object CommitmentOutput {

  case object ToLocal extends CommitmentOutput

  case object ToRemote extends CommitmentOutput

  case object ToLocalAnchor extends CommitmentOutput

  case object ToRemoteAnchor extends CommitmentOutput

  case class InHtlc(incomingHtlc: IncomingHtlc) extends CommitmentOutput

  case class OutHtlc(outgoingHtlc: OutgoingHtlc) extends CommitmentOutput

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

final case class CommitmentSpec(htlcs: Set[DirectedHtlc], commitTxFeerate: FeeratePerKw, toLocal: MilliSatoshi, toRemote: MilliSatoshi) {

  def htlcTxFeerate(commitmentFormat: CommitmentFormat): FeeratePerKw = commitmentFormat match {
    case ZeroFeeHtlcTxAnchorOutputsCommitmentFormat => FeeratePerKw(0 sat)
    case _ => commitTxFeerate
  }

  def findIncomingHtlcById(id: Long): Option[IncomingHtlc] = htlcs.collectFirst { case htlc: IncomingHtlc if htlc.add.id == id => htlc }

  def findOutgoingHtlcById(id: Long): Option[OutgoingHtlc] = htlcs.collectFirst { case htlc: OutgoingHtlc if htlc.add.id == id => htlc }

}

object CommitmentSpec {
  def removeHtlc(changes: List[UpdateMessage], id: Long): List[UpdateMessage] = changes.filterNot {
    case u: UpdateAddHtlc => u.id == id
    case _ => false
  }

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
   * We include in our dust exposure HTLCs that aren't trimmed but would be if the feerate increased.
   * This ensures that we pre-emptively fail some of these untrimmed HTLCs, so that when the feerate increases we reduce
   * the risk that we'll overflow our dust exposure.
   * However, this cannot fully protect us if the feerate increases too much (in which case we may have to force-close).
   */
  def feerateForDustExposure(currentFeerate: FeeratePerKw): FeeratePerKw = {
    (currentFeerate * 1.25).max(currentFeerate + FeeratePerKw(FeeratePerByte(10 sat)))
  }

  /** Test whether the given HTLC contributes to our dust exposure with the default dust feerate calculation. */
  def contributesToDustExposure(htlc: DirectedHtlc, spec: CommitmentSpec, dustLimit: Satoshi, commitmentFormat: CommitmentFormat): Boolean = {
    val feerate = feerateForDustExposure(spec.htlcTxFeerate(commitmentFormat))
    contributesToDustExposure(htlc, feerate, dustLimit, commitmentFormat)
  }

  /** Test whether the given HTLC contributes to our dust exposure at the given feerate. */
  def contributesToDustExposure(htlc: DirectedHtlc, feerate: FeeratePerKw, dustLimit: Satoshi, commitmentFormat: CommitmentFormat): Boolean = {
    val threshold = htlc match {
      case _: IncomingHtlc => Transactions.receivedHtlcTrimThreshold(dustLimit, feerate, commitmentFormat)
      case _: OutgoingHtlc => Transactions.offeredHtlcTrimThreshold(dustLimit, feerate, commitmentFormat)
    }
    htlc.add.amountMsat < threshold
  }

  /** Compute our exposure to dust pending HTLCs (which will be lost as miner fees in case the channel force-closes) with the default dust feerate calculation. */
  def dustExposure(spec: CommitmentSpec, dustLimit: Satoshi, commitmentFormat: CommitmentFormat): MilliSatoshi = {
    val feerate = feerateForDustExposure(spec.htlcTxFeerate(commitmentFormat))
    dustExposure(spec, feerate, dustLimit, commitmentFormat)
  }

  /** Compute our exposure to dust pending HTLCs (which will be lost as miner fees in case the channel force-closes) at the given feerate. */
  def dustExposure(spec: CommitmentSpec, feerate: FeeratePerKw, dustLimit: Satoshi, commitmentFormat: CommitmentFormat): MilliSatoshi = {
    // NB: we need the `toSeq` because otherwise duplicate amountMsat would be removed (since `spec.htlcs` is a Set).
    spec.htlcs.filter(htlc => contributesToDustExposure(htlc, feerate, dustLimit, commitmentFormat)).toSeq.map(_.add.amountMsat).sum
  }

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