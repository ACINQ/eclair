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

package fr.acinq.eclair.router

import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.wire.ChannelUpdate
import fr.acinq.eclair.{CltvExpiryDelta, MilliSatoshi}

/**
 * Created by t-bast on 30/08/2019.
 */

case class Stats[T](min: T, max: T, median: T, percentile5: T, percentile10: T, percentile25: T, percentile75: T, percentile90: T, percentile95: T)

object Stats {
  def apply[T](values: Seq[Long], fromLong: Long => T): Stats[T] = {
    require(values.nonEmpty, "can't compute stats on empty values")
    val sorted = values.sorted
    val count = sorted.size
    val median = if (count % 2 == 0) (sorted(count / 2) + sorted((count / 2) - 1)) / 2 else sorted(count / 2)
    val percentileT = (bucket: Int) => percentile(bucket, sorted, fromLong)
    Stats(fromLong(sorted.head), fromLong(sorted.last), fromLong(median), percentileT(5), percentileT(10), percentileT(25), percentileT(75), percentileT(90), percentileT(95))
  }

  private def percentile[T](bucket: Int, values: Seq[Long], fromLong: Long => T): T = fromLong(values((bucket * values.size) / 100))
}

case class NetworkStats(channels: Int, nodes: Int, capacity: Stats[Satoshi], cltvExpiryDelta: Stats[CltvExpiryDelta], feeBase: Stats[MilliSatoshi], feeProportional: Stats[Long])

object NetworkStats {
  /**
   * Computes various network statistics (expensive).
   * Network statistics won't change noticeably very quickly, so this should not be re-computed too often.
   */
  def apply(publicChannels: Seq[PublicChannel]): Option[NetworkStats] = {
    // We need at least one channel update to be able to compute stats.
    if (publicChannels.isEmpty || publicChannels.flatMap(pc => getChannelUpdateField(pc, _ => true)).isEmpty) {
      None
    } else {
      val capacityStats = Stats(publicChannels.map(_.capacity.toLong).sorted, Satoshi)
      val cltvStats = Stats(publicChannels.flatMap(pc => getChannelUpdateField(pc, u => u.cltvExpiryDelta.toInt.toLong)), l => CltvExpiryDelta(l.toInt))
      val feeBaseStats = Stats(publicChannels.flatMap(pc => getChannelUpdateField(pc, u => u.feeBaseMsat.toLong)), l => MilliSatoshi(l))
      val feeProportionalStats = Stats(publicChannels.flatMap(pc => getChannelUpdateField(pc, u => u.feeProportionalMillionths)), l => l)
      val nodes = publicChannels.flatMap(pc => pc.ann.nodeId1 :: pc.ann.nodeId2 :: Nil).toSet.size
      Some(NetworkStats(publicChannels.size, nodes, capacityStats, cltvStats, feeBaseStats, feeProportionalStats))
    }
  }

  private def getChannelUpdateField[T](pc: PublicChannel, f: ChannelUpdate => T): Seq[T] =
    pc.update_1_opt.map(u => f(u) :: Nil).getOrElse(Nil) ++ pc.update_2_opt.map(u => f(u) :: Nil).getOrElse(Nil)

}
