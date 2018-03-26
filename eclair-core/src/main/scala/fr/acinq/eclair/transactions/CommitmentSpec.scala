/*
 * Copyright 2018 ACINQ SAS
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

import fr.acinq.eclair.wire._

/**
  * Created by PM on 07/12/2016.
  */

// @formatter:off
sealed trait Direction { def opposite: Direction }
case object IN extends Direction { def opposite = OUT }
case object OUT extends Direction { def opposite = IN }
// @formatter:on

case class DirectedHtlc(direction: Direction, add: UpdateAddHtlc)

final case class CommitmentSpec(htlcs: Set[DirectedHtlc], feeratePerKw: Long, toLocalMsat: Long, toRemoteMsat: Long) {
  val totalFunds = toLocalMsat + toRemoteMsat + htlcs.toSeq.map(_.add.amountMsat).sum
}

object CommitmentSpec {
  def removeHtlc(changes: List[UpdateMessage], id: Long): List[UpdateMessage] = changes.filterNot(_ match {
    case u: UpdateAddHtlc if u.id == id => true
    case _ => false
  })

  def addHtlc(spec: CommitmentSpec, direction: Direction, update: UpdateAddHtlc): CommitmentSpec = {
    val htlc = DirectedHtlc(direction, update)
    direction match {
      case OUT => spec.copy(toLocalMsat = spec.toLocalMsat - htlc.add.amountMsat, htlcs = spec.htlcs + htlc)
      case IN => spec.copy(toRemoteMsat = spec.toRemoteMsat - htlc.add.amountMsat, htlcs = spec.htlcs + htlc)
    }
  }

  // OUT means we are sending an UpdateFulfillHtlc message which means that we are fulfilling an HTLC that they sent
  def fulfillHtlc(spec: CommitmentSpec, direction: Direction, htlcId: Long): CommitmentSpec = {
    spec.htlcs.find(htlc => htlc.direction != direction && htlc.add.id == htlcId) match {
      case Some(htlc) if direction == OUT => spec.copy(toLocalMsat = spec.toLocalMsat + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
      case Some(htlc) if direction == IN => spec.copy(toRemoteMsat = spec.toRemoteMsat + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
      case None => throw new RuntimeException(s"cannot find htlc id=${htlcId}")
    }
  }

  // OUT means we are sending an UpdateFailHtlc message which means that we are failing an HTLC that they sent
  def failHtlc(spec: CommitmentSpec, direction: Direction, htlcId: Long): CommitmentSpec = {
    spec.htlcs.find(htlc => htlc.direction != direction && htlc.add.id == htlcId) match {
      case Some(htlc) if direction == OUT => spec.copy(toRemoteMsat = spec.toRemoteMsat + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
      case Some(htlc) if direction == IN => spec.copy(toLocalMsat = spec.toLocalMsat + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
      case None => throw new RuntimeException(s"cannot find htlc id=${htlcId}")
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
      case (spec, u: UpdateFulfillHtlc) => fulfillHtlc(spec, OUT, u.id)
      case (spec, u: UpdateFailHtlc) => failHtlc(spec, OUT, u.id)
      case (spec, u: UpdateFailMalformedHtlc) => failHtlc(spec, OUT, u.id)
      case (spec, _) => spec
    }
    val spec4 = remoteChanges.foldLeft(spec3) {
      case (spec, u: UpdateFulfillHtlc) => fulfillHtlc(spec, IN, u.id)
      case (spec, u: UpdateFailHtlc) => failHtlc(spec, IN, u.id)
      case (spec, u: UpdateFailMalformedHtlc) => failHtlc(spec, IN, u.id)
      case (spec, _) => spec
    }
    val spec5 = (localChanges ++ remoteChanges).foldLeft(spec4) {
      case (spec, u: UpdateFee) => spec.copy(feeratePerKw = u.feeratePerKw)
      case (spec, _) => spec
    }
    spec5
  }

}