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

package fr.acinq.eclair.blockchain.fee

import scala.concurrent.{ExecutionContext, Future}

case class SmoothFeeProvider(provider: FeeProvider, windowSize: Int)(implicit ec: ExecutionContext) extends FeeProvider {
  require(windowSize > 0)

  var queue = List.empty[FeeratesPerKB]

  def append(rate: FeeratesPerKB): Unit = synchronized {
    queue = queue :+ rate
    if (queue.length > windowSize) queue = queue.drop(1)
  }

  override def getFeerates: Future[FeeratesPerKB] = {
    for {
      rate <- provider.getFeerates
      _ = append(rate)
    } yield SmoothFeeProvider.smooth(queue)
  }

}

object SmoothFeeProvider {

  def avg(i: Seq[FeeratePerKB]): FeeratePerKB = FeeratePerKB(i.map(_.feerate).sum / i.size)

  def smooth(rates: Seq[FeeratesPerKB]): FeeratesPerKB =
    FeeratesPerKB(
      mempoolMinFee = avg(rates.map(_.mempoolMinFee)),
      block_1 = avg(rates.map(_.block_1)),
      blocks_2 = avg(rates.map(_.blocks_2)),
      blocks_6 = avg(rates.map(_.blocks_6)),
      blocks_12 = avg(rates.map(_.blocks_12)),
      blocks_36 = avg(rates.map(_.blocks_36)),
      blocks_72 = avg(rates.map(_.blocks_72)),
      blocks_144 = avg(rates.map(_.blocks_144)),
      blocks_1008 = avg(rates.map(_.blocks_1008)))

}
