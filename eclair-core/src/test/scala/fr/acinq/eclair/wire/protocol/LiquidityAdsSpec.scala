/*
 * Copyright 2024 ACINQ SAS
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

package fr.acinq.eclair.wire.protocol

import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector64, SatoshiLong}
import fr.acinq.eclair.blockchain.fee.{FeeratePerByte, FeeratePerKw}
import fr.acinq.eclair.channel.{InvalidLiquidityAdsAmount, InvalidLiquidityAdsSig, MissingLiquidityAds}
import fr.acinq.eclair.{randomBytes32, randomBytes64}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.HexStringSyntax

class LiquidityAdsSpec extends AnyFunSuite {

  test("validate liquidity ads funding attempt") {
    val nodeKey = PrivateKey(hex"57ac961f1b80ebfb610037bf9c96c6333699bde42257919a53974811c34649e3")
    assert(nodeKey.publicKey == PublicKey(hex"03ca9b880627d2d4e3b33164f66946349f820d26aa9572fe0e525e534850cbd413"))

    val fundingRate = LiquidityAds.FundingRate(100_000 sat, 1_000_000 sat, 500, 100, 10 sat, 1000 sat)
    assert(fundingRate.fees(FeeratePerByte(5 sat).perKw, 500_000 sat, 500_000 sat, isChannelCreation = false).total == 5635.sat)
    assert(fundingRate.fees(FeeratePerByte(5 sat).perKw, 500_000 sat, 600_000 sat, isChannelCreation = false).total == 5635.sat)
    assert(fundingRate.fees(FeeratePerByte(5 sat).perKw, 500_000 sat, 600_000 sat, isChannelCreation = true).total == 6635.sat)
    assert(fundingRate.fees(FeeratePerByte(5 sat).perKw, 500_000 sat, 400_000 sat, isChannelCreation = false).total == 4635.sat)
    assert(fundingRate.fees(FeeratePerByte(10 sat).perKw, 500_000 sat, 500_000 sat, isChannelCreation = false).total == 6260.sat)

    val fundingRates = LiquidityAds.WillFundRates(fundingRate :: Nil, Set(LiquidityAds.PaymentType.FromChannelBalance))
    val Some(request) = LiquidityAds.requestFunding(500_000 sat, LiquidityAds.PaymentDetails.FromChannelBalance, fundingRates)
    val fundingScript = hex"00202395c9c52c02ca069f1d56a3c6124bf8b152a617328c76e6b31f83ace370c2ff"
    val Right(willFund) = fundingRates.validateRequest(nodeKey, randomBytes32(), fundingScript, FeeratePerKw(1000 sat), request, isChannelCreation = true, None).map(_.willFund)
    assert(willFund.fundingRate == fundingRate)
    assert(willFund.fundingScript == fundingScript)
    assert(willFund.signature == ByteVector64.fromValidHex("a53106bd20027b0215480ff0b06b2bf9324bb257c2a0e74c2604ec347493f90d3a975d56a68b21a6cc48d6763d96f70e1d630dd1720cf6b7314d4304050fe265"))

    val channelId = randomBytes32()
    val testCases = Seq(
      (500_000 sat, Some(willFund), None),
      (500_000 sat, None, Some(MissingLiquidityAds(channelId))),
      (500_000 sat, Some(willFund.copy(signature = randomBytes64())), Some(InvalidLiquidityAdsSig(channelId))),
      (0 sat, Some(willFund), Some(InvalidLiquidityAdsAmount(channelId, 0 sat, 500_000 sat))),
    )
    testCases.foreach {
      case (fundingAmount, willFund_opt, failure_opt) =>
        val result = request.validateRemoteFunding(nodeKey.publicKey, channelId, fundingScript, fundingAmount, FeeratePerKw(2500 sat), isChannelCreation = true, willFund_opt)
        failure_opt match {
          case Some(failure) => assert(result == Left(failure))
          case None => assert(result.isRight)
        }
    }
  }

}
