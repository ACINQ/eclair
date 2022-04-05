/*
 * Copyright 2021 ACINQ SAS
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

import fr.acinq.bitcoin.scalacompat.{Satoshi, SatoshiLong}
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.blockchain.fee.{FeeratePerByte, FeeratePerKw}
import fr.acinq.eclair.transactions.Transactions.CommitmentFormat
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.protocol._

/**
 * Created by t-bast on 07/10/2021.
 */

object DustExposure {

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
  def computeExposure(spec: CommitmentSpec, dustLimit: Satoshi, commitmentFormat: CommitmentFormat): MilliSatoshi = {
    val feerate = feerateForDustExposure(spec.htlcTxFeerate(commitmentFormat))
    computeExposure(spec, feerate, dustLimit, commitmentFormat)
  }

  /** Compute our exposure to dust pending HTLCs (which will be lost as miner fees in case the channel force-closes) at the given feerate. */
  def computeExposure(spec: CommitmentSpec, feerate: FeeratePerKw, dustLimit: Satoshi, commitmentFormat: CommitmentFormat): MilliSatoshi = {
    // NB: we need the `toSeq` because otherwise duplicate amountMsat would be removed (since `spec.htlcs` is a Set).
    spec.htlcs.filter(htlc => contributesToDustExposure(htlc, feerate, dustLimit, commitmentFormat)).toSeq.map(_.add.amountMsat).sum
  }

  /** Accept as many incoming HTLCs as possible, in the order they are provided, while not overflowing our dust exposure. */
  def filterBeforeForward(maxDustExposure: Satoshi,
                          localSpec: CommitmentSpec,
                          localDustLimit: Satoshi,
                          localCommitDustExposure: MilliSatoshi,
                          remoteSpec: CommitmentSpec,
                          remoteDustLimit: Satoshi,
                          remoteCommitDustExposure: MilliSatoshi,
                          receivedHtlcs: Seq[UpdateAddHtlc],
                          commitmentFormat: CommitmentFormat): (Seq[UpdateAddHtlc], Seq[UpdateAddHtlc]) = {
    val (_, _, acceptedHtlcs, rejectedHtlcs) = receivedHtlcs.foldLeft((localCommitDustExposure, remoteCommitDustExposure, Seq.empty[UpdateAddHtlc], Seq.empty[UpdateAddHtlc])) {
      case ((currentLocalCommitDustExposure, currentRemoteCommitDustExposure, acceptedHtlcs, rejectedHtlcs), add) =>
        val contributesToLocalCommitDustExposure = contributesToDustExposure(IncomingHtlc(add), localSpec, localDustLimit, commitmentFormat)
        val overflowsLocalCommitDustExposure = contributesToLocalCommitDustExposure && currentLocalCommitDustExposure + add.amountMsat > maxDustExposure
        val contributesToRemoteCommitDustExposure = contributesToDustExposure(OutgoingHtlc(add), remoteSpec, remoteDustLimit, commitmentFormat)
        val overflowsRemoteCommitDustExposure = contributesToRemoteCommitDustExposure && currentRemoteCommitDustExposure + add.amountMsat > maxDustExposure
        if (overflowsLocalCommitDustExposure || overflowsRemoteCommitDustExposure) {
          (currentLocalCommitDustExposure, currentRemoteCommitDustExposure, acceptedHtlcs, rejectedHtlcs :+ add)
        } else {
          val nextLocalCommitDustExposure = if (contributesToLocalCommitDustExposure) currentLocalCommitDustExposure + add.amountMsat else currentLocalCommitDustExposure
          val nextRemoteCommitDustExposure = if (contributesToRemoteCommitDustExposure) currentRemoteCommitDustExposure + add.amountMsat else currentRemoteCommitDustExposure
          (nextLocalCommitDustExposure, nextRemoteCommitDustExposure, acceptedHtlcs :+ add, rejectedHtlcs)
        }
    }
    (acceptedHtlcs, rejectedHtlcs)
  }

  def reduceForDustExposure(localCommitSpec: CommitmentSpec, localChanges: List[UpdateMessage], remoteChanges: List[UpdateMessage]): CommitmentSpec = {
    // NB: when computing dust exposure, we usually apply all pending updates (proposed, signed and acked), which means
    // that we will sometimes apply fulfill/fail on htlcs that have already been removed: that's why we don't use the
    // normal functions from CommitmentSpec that would throw when that happens.
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
