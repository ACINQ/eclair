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

import akka.event.LoggingAdapter
import fr.acinq.bitcoin.scalacompat.Satoshi
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.channel.HtlcFiltering.FilteredHtlcs
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions.{CommitmentSpec, IncomingHtlc, OutgoingHtlc}
import fr.acinq.eclair.wire.protocol.UpdateAddHtlc

/**
 * Created by t-bast on 22/06/2022.
 */

/**
 * Channels have a limited number of HTLCs that can be in-flight at a given time, because the commitment transaction
 * cannot have an unbounded number of outputs. Malicious actors can exploit this by filling our channels with HTLCs and
 * waiting as long as possible before failing them.
 *
 * To increase the cost of this attack, we don't let our channels be filled with low-value HTLCs. When we already have
 * many low-value HTLCs in-flight, we only accept higher value HTLCs. Attackers will have to lock non-negligible amounts
 * to carry out the attack.
 */
object ChannelCongestionControl {

  case class HtlcBucket(threshold: MilliSatoshi, size: Int) {
    def allowHtlc(add: UpdateAddHtlc, current: Seq[UpdateAddHtlc]): Boolean = {
      // We allow the HTLC if it belongs to a bigger bucket or if the bucket isn't full.
      add.amountMsat > threshold || current.count(_.amountMsat <= threshold) < size
    }
  }

  case class CongestionConfig(maxAcceptedHtlcs: Int, maxHtlcValueInFlight: MilliSatoshi, buckets: Seq[HtlcBucket]) {
    def allowHtlc(add: UpdateAddHtlc, current: Seq[UpdateAddHtlc], trimThreshold: Satoshi)(implicit log: LoggingAdapter): Boolean = {
      // We allow the HTLC if it's trimmed (since it doesn't use an output in the commit tx) or if we can find a bucket that isn't full.
      val allow = add.amountMsat < trimThreshold || buckets.forall(_.allowHtlc(add, current))
      if (!allow) {
        log.info("htlc rejected by congestion control (amount={} max-accepted={} max-in-flight={}): current={}", add.amountMsat, maxAcceptedHtlcs, maxHtlcValueInFlight, current.map(_.amountMsat).sorted.mkString(", "))
      }
      allow
    }
  }

  object CongestionConfig {
    /**
     * With the following configuration, if we allow 30 HTLCs and a maximum value in-flight of 250 000 sats, an attacker
     * would need to lock 165 000 sats in order to fill our channel.
     */
    def apply(maxAcceptedHtlcs: Int, maxHtlcValueInFlight: MilliSatoshi): CongestionConfig = {
      val buckets = Seq(
        HtlcBucket(maxHtlcValueInFlight / 100, maxAcceptedHtlcs / 2), // allow at most 50% of htlcs below 1% of our max-in-flight
        HtlcBucket(maxHtlcValueInFlight * 5 / 100, maxAcceptedHtlcs * 8 / 10), // allow at most 80% of htlcs below 5% of our max-in-flight
        HtlcBucket(maxHtlcValueInFlight * 10 / 100, maxAcceptedHtlcs * 9 / 10), // allow at most 90% of htlcs below 10% of our max-in-flight
      )
      CongestionConfig(maxAcceptedHtlcs, maxHtlcValueInFlight, buckets)
    }
  }

  def shouldSendHtlc(add: UpdateAddHtlc,
                     localSpec: CommitmentSpec,
                     localDustLimit: Satoshi,
                     localMaxAcceptedHtlcs: Int,
                     remoteSpec: CommitmentSpec,
                     remoteDustLimit: Satoshi,
                     remoteMaxAcceptedHtlcs: Int,
                     maxHtlcValueInFlight: MilliSatoshi,
                     commitmentFormat: CommitmentFormat)(implicit log: LoggingAdapter): Boolean = {
    // We apply the most restrictive value between our peer's and ours.
    val maxAcceptedHtlcs = localMaxAcceptedHtlcs.min(remoteMaxAcceptedHtlcs)
    val config = CongestionConfig(maxAcceptedHtlcs, maxHtlcValueInFlight)
    val localOk = {
      val pending = trimOfferedHtlcs(localDustLimit, localSpec, commitmentFormat).map(_.add)
      val trimThreshold = offeredHtlcTrimThreshold(localDustLimit, localSpec, commitmentFormat)
      config.allowHtlc(add, pending, trimThreshold)
    }
    val remoteOk = {
      val pending = trimReceivedHtlcs(remoteDustLimit, remoteSpec, commitmentFormat).map(_.add)
      val trimThreshold = receivedHtlcTrimThreshold(remoteDustLimit, remoteSpec, commitmentFormat)
      config.allowHtlc(add, pending, trimThreshold)
    }
    localOk && remoteOk
  }

  def filterBeforeForward(localSpec: CommitmentSpec,
                          localDustLimit: Satoshi,
                          localMaxAcceptedHtlcs: Int,
                          remoteSpec: CommitmentSpec,
                          remoteDustLimit: Satoshi,
                          receivedHtlcs: FilteredHtlcs,
                          maxHtlcValueInFlight: MilliSatoshi,
                          commitmentFormat: CommitmentFormat)(implicit log: LoggingAdapter): FilteredHtlcs = {
    val config = CongestionConfig(localMaxAcceptedHtlcs, maxHtlcValueInFlight)
    val (_, _, result) = receivedHtlcs.accepted.foldLeft((localSpec, remoteSpec, receivedHtlcs.copy(accepted = Seq.empty))) {
      case ((currentLocalSpec, currentRemoteSpec, currentHtlcs), add) =>
        val localOk = {
          val pending = trimReceivedHtlcs(localDustLimit, currentLocalSpec, commitmentFormat).map(_.add)
          val trimThreshold = receivedHtlcTrimThreshold(localDustLimit, currentLocalSpec, commitmentFormat)
          config.allowHtlc(add, pending, trimThreshold)
        }
        val remoteOk = {
          val pending = trimOfferedHtlcs(remoteDustLimit, currentRemoteSpec, commitmentFormat).map(_.add)
          val trimThreshold = offeredHtlcTrimThreshold(remoteDustLimit, currentRemoteSpec, commitmentFormat)
          config.allowHtlc(add, pending, trimThreshold)
        }
        if (localOk && remoteOk) {
          val nextLocalSpec = CommitmentSpec.addHtlc(currentLocalSpec, IncomingHtlc(add))
          val nextRemoteSpec = CommitmentSpec.addHtlc(currentRemoteSpec, OutgoingHtlc(add))
          (nextLocalSpec, nextRemoteSpec, currentHtlcs.accept(add))
        } else {
          (currentLocalSpec, currentRemoteSpec, currentHtlcs.reject(add))
        }
    }
    result
  }

}
