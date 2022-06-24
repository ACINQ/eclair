/*
 * Copyright 2022 ACINQ SAS
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

package fr.acinq.eclair.channel

import fr.acinq.eclair.transactions.{CommitmentSpec, IncomingHtlc, OutgoingHtlc}
import fr.acinq.eclair.wire.protocol._

/**
 * Created by t-bast on 22/06/2022.
 */

/**
 * We may want to apply implementation-specific rate limits before forwarding HTLCs that would otherwise be valid
 * according to the specification (e.g. to protect against a large dust exposure or rate limit HTLCs based on their
 * amount).
 */
object HtlcFiltering {

  case class FilteredHtlcs(accepted: Seq[UpdateAddHtlc], rejected: Seq[UpdateAddHtlc]) {
    // @formatter:off
    def accept(add: UpdateAddHtlc): FilteredHtlcs = FilteredHtlcs(accepted :+ add, rejected)
    def reject(add: UpdateAddHtlc): FilteredHtlcs = FilteredHtlcs(accepted, rejected :+ add)
    // @formatter:on
  }

  // NB: when filtering htlcs, we want to apply all pending updates (proposed, signed and acked), which means that we
  // will sometimes apply fulfill/fail on htlcs that have already been removed: that's why we don't use the normal
  // functions from CommitmentSpec that would throw when that happens.
  def reduceForHtlcFiltering(localCommitSpec: CommitmentSpec, localChanges: List[UpdateMessage], remoteChanges: List[UpdateMessage]): CommitmentSpec = {
    def fulfillIncomingHtlc(spec: CommitmentSpec, htlcId: Long): CommitmentSpec = {
      spec.findIncomingHtlcById(htlcId) match {
        case Some(htlc) => spec.copy(toLocal = spec.toLocal + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
        case None => spec
      }
    }

    def fulfillOutgoingHtlc(spec: CommitmentSpec, htlcId: Long): CommitmentSpec = {
      spec.findOutgoingHtlcById(htlcId) match {
        case Some(htlc) => spec.copy(toRemote = spec.toRemote + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
        case None => spec
      }
    }

    def failIncomingHtlc(spec: CommitmentSpec, htlcId: Long): CommitmentSpec = {
      spec.findIncomingHtlcById(htlcId) match {
        case Some(htlc) => spec.copy(toRemote = spec.toRemote + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
        case None => spec
      }
    }

    def failOutgoingHtlc(spec: CommitmentSpec, htlcId: Long): CommitmentSpec = {
      spec.findOutgoingHtlcById(htlcId) match {
        case Some(htlc) => spec.copy(toLocal = spec.toLocal + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
        case None => spec
      }
    }

    val spec1 = localChanges.foldLeft(localCommitSpec) {
      case (spec, u: UpdateAddHtlc) => CommitmentSpec.addHtlc(spec, OutgoingHtlc(u))
      case (spec, _) => spec
    }
    val spec2 = remoteChanges.foldLeft(spec1) {
      case (spec, u: UpdateAddHtlc) => CommitmentSpec.addHtlc(spec, IncomingHtlc(u))
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
