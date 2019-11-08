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

import com.google.common.math.Quantiles.percentiles
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.wire.ChannelUpdate
import fr.acinq.eclair.{CltvExpiryDelta, MilliSatoshi}

import scala.collection.JavaConverters._

/**
 * Created by t-bast on 30/08/2019.
 */

case class Stats[T](median: T, percentile5: T, percentile10: T, percentile25: T, percentile75: T, percentile90: T, percentile95: T)

object Stats {
  def apply[T](values: Iterable[Long], fromDouble: Double => T): Stats[T] = {
    require(values.nonEmpty, "can't compute stats on empty values")
    val stats = percentiles().indexes(5, 10, 25, 50, 75, 90, 95).compute(values.map(java.lang.Long.valueOf).asJavaCollection)
    Stats(fromDouble(stats.get(50)), fromDouble(stats.get(5)), fromDouble(stats.get(10)), fromDouble(stats.get(25)), fromDouble(stats.get(75)), fromDouble(stats.get(90)), fromDouble(stats.get(95)))
  }
}

case class NetworkStats(channels: Int, nodes: Int, capacity: Stats[Satoshi], cltvExpiryDelta: Stats[CltvExpiryDelta], feeBase: Stats[MilliSatoshi], feeProportional: Stats[Long])

object NetworkStats {
  /**
   * Computes various network statistics (expensive).
   * Network statistics won't change noticeably very quickly, so this should not be re-computed too often.
   */
  def computeStats(publicChannels: Iterable[PublicChannel]): Option[NetworkStats] = {
    // We need at least one channel update to be able to compute stats.
    if (publicChannels.isEmpty || publicChannels.flatMap(pc => getChannelUpdateField(pc, _ => true)).isEmpty) {
      None
    } else {
      val capacityStats = Stats(publicChannels.map(_.capacity.toLong), d => Satoshi(d.toLong))
      val cltvStats = Stats(publicChannels.flatMap(pc => getChannelUpdateField(pc, u => u.cltvExpiryDelta.toInt.toLong)), d => CltvExpiryDelta(d.toInt))
      val feeBaseStats = Stats(publicChannels.flatMap(pc => getChannelUpdateField(pc, u => u.feeBaseMsat.toLong)), d => MilliSatoshi(d.toLong))
      val feeProportionalStats = Stats(publicChannels.flatMap(pc => getChannelUpdateField(pc, u => u.feeProportionalMillionths)), d => d.toLong)
      val nodes = publicChannels.flatMap(pc => pc.ann.nodeId1 :: pc.ann.nodeId2 :: Nil).toSet.size
      Some(NetworkStats(publicChannels.size, nodes, capacityStats, cltvStats, feeBaseStats, feeProportionalStats))
    }
  }

  private def getChannelUpdateField[T](pc: PublicChannel, f: ChannelUpdate => T): Seq[T] = (pc.update_1_opt.toSeq ++ pc.update_2_opt.toSeq).map(f)

}
