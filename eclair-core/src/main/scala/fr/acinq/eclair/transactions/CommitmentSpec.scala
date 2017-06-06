package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.wire._

/**
  * Created by PM on 07/12/2016.
  */

// @formatter:off
sealed trait Direction { def opposite: Direction }
case object IN extends Direction { def opposite = OUT }
case object OUT extends Direction { def opposite = IN }
// @formatter:on

case class Htlc(direction: Direction, add: UpdateAddHtlc, val previousChannelId: Option[BinaryData])

final case class CommitmentSpec(htlcs: Set[Htlc], feeratePerKw: Long, toLocalMsat: Long, toRemoteMsat: Long) {
  val totalFunds = toLocalMsat + toRemoteMsat + htlcs.toSeq.map(_.add.amountMsat).sum
}

object CommitmentSpec {
  def removeHtlc(changes: List[UpdateMessage], id: Long): List[UpdateMessage] = changes.filterNot(_ match {
    case u: UpdateAddHtlc if u.id == id => true
    case _ => false
  })

  def addHtlc(spec: CommitmentSpec, direction: Direction, update: UpdateAddHtlc): CommitmentSpec = {
    val htlc = Htlc(direction, update, previousChannelId = None)
    direction match {
      case OUT => spec.copy(toLocalMsat = spec.toLocalMsat - htlc.add.amountMsat, htlcs = spec.htlcs + htlc)
      case IN => spec.copy(toRemoteMsat = spec.toRemoteMsat - htlc.add.amountMsat, htlcs = spec.htlcs + htlc)
    }
  }

  // OUT means we are sending an UpdateFulfillHtlc message which means that we are fulfilling an HTLC that they sent
  def fulfillHtlc(spec: CommitmentSpec, direction: Direction, update: UpdateFulfillHtlc): CommitmentSpec = {
    spec.htlcs.find(htlc => htlc.direction != direction && htlc.add.id == update.id) match {
      case Some(htlc) if direction == OUT => spec.copy(toLocalMsat = spec.toLocalMsat + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
      case Some(htlc) if direction == IN => spec.copy(toRemoteMsat = spec.toRemoteMsat + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
      case None => throw new RuntimeException(s"cannot find htlc id=${update.id}")
    }
  }

  // OUT means we are sending an UpdateFailHtlc message which means that we are failing an HTLC that they sent
  def failHtlc(spec: CommitmentSpec, direction: Direction, update: UpdateFailHtlc): CommitmentSpec = {
    spec.htlcs.find(htlc => htlc.direction != direction && htlc.add.id == update.id) match {
      case Some(htlc) if direction == OUT => spec.copy(toRemoteMsat = spec.toRemoteMsat + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
      case Some(htlc) if direction == IN => spec.copy(toLocalMsat = spec.toLocalMsat + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
      case None => throw new RuntimeException(s"cannot find htlc id=${update.id}")
    }
  }

  def reduce(localCommitSpec: CommitmentSpec, localChanges: List[UpdateMessage], remoteChanges: List[UpdateMessage]): CommitmentSpec = {
    val spec1 = localChanges.foldLeft(localCommitSpec) {
      case (spec, u: UpdateAddHtlc) => addHtlc(spec, OUT, u)
      case (spec, _) => spec
    }
    val spec2 = remoteChanges.foldLeft(spec1) {
      case (spec, u: UpdateAddHtlc) => addHtlc(spec, IN, u)
      case (spec, _) => spec
    }
    val spec3 = localChanges.foldLeft(spec2) {
      case (spec, u: UpdateFulfillHtlc) => fulfillHtlc(spec, OUT, u)
      case (spec, u: UpdateFailHtlc) => failHtlc(spec, OUT, u)
      case (spec, _) => spec
    }
    val spec4 = remoteChanges.foldLeft(spec3) {
      case (spec, u: UpdateFulfillHtlc) => fulfillHtlc(spec, IN, u)
      case (spec, u: UpdateFailHtlc) => failHtlc(spec, IN, u)
      case (spec, _) => spec
    }
    val spec5 = (localChanges ++ remoteChanges).foldLeft(spec4) {
      case (spec, u: UpdateFee) => spec.copy(feeratePerKw = u.feeratePerKw)
      case (spec, _) => spec
    }
    spec5
  }

}