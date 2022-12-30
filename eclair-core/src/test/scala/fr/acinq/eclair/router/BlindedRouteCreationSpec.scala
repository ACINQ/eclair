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

package fr.acinq.eclair.router

import fr.acinq.eclair.router.RouteCalculationSpec.makeUpdateShort
import fr.acinq.eclair.router.Router.{ChannelHop, HopRelayParams}
import fr.acinq.eclair.wire.protocol.{BlindedRouteData, RouteBlindingEncryptedDataCodecs, RouteBlindingEncryptedDataTlv}
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, MilliSatoshiLong, ShortChannelId, randomBytes32, randomKey}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.{ParallelTestExecution, Tag}

class BlindedRouteCreationSpec extends AnyFunSuite with ParallelTestExecution {

  import BlindedRouteCreation._

  test("create blinded route without hops") {
    val a = randomKey()
    val pathId = randomBytes32()
    val route = createBlindedRouteWithoutHops(a.publicKey, pathId, 1 msat, CltvExpiry(500))
    assert(route.route.introductionNodeId == a.publicKey)
    assert(route.route.encryptedPayloads.length == 1)
    assert(route.route.blindingKey == route.lastBlinding)
    val Right(decoded) = RouteBlindingEncryptedDataCodecs.decode(a, route.route.blindingKey, route.route.encryptedPayloads.head)
    assert(BlindedRouteData.validPaymentRecipientData(decoded.tlvs).isRight)
    assert(decoded.tlvs.get[RouteBlindingEncryptedDataTlv.PathId].get.data == pathId.bytes)
  }

  test("create blinded route from channel hops") {
    val (a, b, c) = (randomKey(), randomKey(), randomKey())
    val pathId = randomBytes32()
    val (scid1, scid2) = (ShortChannelId(1), ShortChannelId(2))
    val hops = Seq(
      ChannelHop(scid1, a.publicKey, b.publicKey, HopRelayParams.FromAnnouncement(makeUpdateShort(scid1, a.publicKey, b.publicKey, 10 msat, 300, cltvDelta = CltvExpiryDelta(200)))),
      ChannelHop(scid2, b.publicKey, c.publicKey, HopRelayParams.FromAnnouncement(makeUpdateShort(scid2, b.publicKey, c.publicKey, 20 msat, 150, cltvDelta = CltvExpiryDelta(600)))),
    )
    val route = createBlindedRouteFromHops(hops, pathId, 1 msat, CltvExpiry(500))
    assert(route.route.introductionNodeId == a.publicKey)
    assert(route.route.encryptedPayloads.length == 3)
    val Right(decoded1) = RouteBlindingEncryptedDataCodecs.decode(a, route.route.blindingKey, route.route.encryptedPayloads(0))
    assert(BlindedRouteData.validatePaymentRelayData(decoded1.tlvs).isRight)
    assert(decoded1.tlvs.get[RouteBlindingEncryptedDataTlv.OutgoingChannelId].get.shortChannelId == scid1)
    assert(decoded1.tlvs.get[RouteBlindingEncryptedDataTlv.PaymentRelay].get.feeBase == 10.msat)
    assert(decoded1.tlvs.get[RouteBlindingEncryptedDataTlv.PaymentRelay].get.feeProportionalMillionths == 300)
    assert(decoded1.tlvs.get[RouteBlindingEncryptedDataTlv.PaymentRelay].get.cltvExpiryDelta == CltvExpiryDelta(200))
    val Right(decoded2) = RouteBlindingEncryptedDataCodecs.decode(b, decoded1.nextBlinding, route.route.encryptedPayloads(1))
    assert(BlindedRouteData.validatePaymentRelayData(decoded2.tlvs).isRight)
    assert(decoded2.tlvs.get[RouteBlindingEncryptedDataTlv.OutgoingChannelId].get.shortChannelId == scid2)
    assert(decoded2.tlvs.get[RouteBlindingEncryptedDataTlv.PaymentRelay].get.feeBase == 20.msat)
    assert(decoded2.tlvs.get[RouteBlindingEncryptedDataTlv.PaymentRelay].get.feeProportionalMillionths == 150)
    assert(decoded2.tlvs.get[RouteBlindingEncryptedDataTlv.PaymentRelay].get.cltvExpiryDelta == CltvExpiryDelta(600))
    val Right(decoded3) = RouteBlindingEncryptedDataCodecs.decode(c, decoded2.nextBlinding, route.route.encryptedPayloads(2))
    assert(BlindedRouteData.validPaymentRecipientData(decoded3.tlvs).isRight)
    assert(decoded3.tlvs.get[RouteBlindingEncryptedDataTlv.PathId].get.data == pathId.bytes)
  }

  test("create blinded route payment info", Tag("fuzzy")) {
    val rand = new scala.util.Random()
    val nodeId = randomKey().publicKey
    for (_ <- 0 to 100) {
      val routeLength = rand.nextInt(10) + 1
      val hops = (1 to routeLength).map(i => {
        val scid = ShortChannelId(i)
        val feeBase = rand.nextInt(10_000).msat
        val feeProp = rand.nextInt(5000)
        val cltvExpiryDelta = CltvExpiryDelta(rand.nextInt(500))
        val params = HopRelayParams.FromAnnouncement(makeUpdateShort(scid, nodeId, nodeId, feeBase, feeProp, cltvDelta = cltvExpiryDelta))
        ChannelHop(scid, nodeId, nodeId, params)
      })
      for (_ <- 0 to 100) {
        val amount = rand.nextLong(10_000_000_000L).msat
        val payInfo = aggregatePaymentInfo(amount, hops, CltvExpiryDelta(0))
        assert(payInfo.cltvExpiryDelta == CltvExpiryDelta(hops.map(_.cltvExpiryDelta.toInt).sum))
        // We verify that the aggregated fee slightly exceeds the actual fee (because of proportional fees rounding).
        val aggregatedFee = payInfo.fee(amount)
        val actualFee = Router.Route(amount, hops, None).channelFee(includeLocalChannelCost = true)
        assert(aggregatedFee >= actualFee, s"amount=$amount, hops=${hops.map(_.params.relayFees)}, aggregatedFee=$aggregatedFee, actualFee=$actualFee")
        assert(aggregatedFee - actualFee < 1000.msat.max(amount * 1e-5), s"amount=$amount, hops=${hops.map(_.params.relayFees)}, aggregatedFee=$aggregatedFee, actualFee=$actualFee")
      }
    }
  }

}
