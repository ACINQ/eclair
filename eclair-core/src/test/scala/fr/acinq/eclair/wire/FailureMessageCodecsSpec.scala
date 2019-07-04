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

package fr.acinq.eclair.wire

import fr.acinq.bitcoin.{Block, ByteVector32, ByteVector64}
import fr.acinq.eclair.crypto.Hmac256
import fr.acinq.eclair.{ShortChannelId, randomBytes32, randomBytes64}
import fr.acinq.eclair.wire.FailureMessageCodecs._
import org.scalatest.FunSuite
import scodec.bits._
import scodec.codecs.uint16

/**
  * Created by PM on 31/05/2016.
  */

class FailureMessageCodecsSpec extends FunSuite {
  val channelUpdate = ChannelUpdate(
    signature = randomBytes64,
    chainHash = Block.RegtestGenesisBlock.hash,
    shortChannelId = ShortChannelId(12345),
    timestamp = 1234567L,
    cltvExpiryDelta = 100,
    messageFlags = 0,
    channelFlags = 1,
    htlcMinimumMsat = 1000,
    feeBaseMsat = 12,
    feeProportionalMillionths = 76,
    htlcMaximumMsat = None)

  test("encode/decode all channel messages") {
    val msgs: List[FailureMessage] =
      InvalidRealm :: TemporaryNodeFailure :: PermanentNodeFailure :: RequiredNodeFeatureMissing ::
        InvalidOnionVersion(randomBytes32) :: InvalidOnionHmac(randomBytes32) :: InvalidOnionKey(randomBytes32) :: InvalidOnionUnknown(randomBytes32) ::
        TemporaryChannelFailure(channelUpdate) :: PermanentChannelFailure :: RequiredChannelFeatureMissing :: UnknownNextPeer ::
        AmountBelowMinimum(123456, channelUpdate) :: FeeInsufficient(546463, channelUpdate) :: IncorrectCltvExpiry(1211, channelUpdate) :: ExpiryTooSoon(channelUpdate) ::
        IncorrectOrUnknownPaymentDetails(123456L) :: IncorrectPaymentAmount :: FinalExpiryTooSoon :: FinalIncorrectCltvExpiry(1234) :: ChannelDisabled(0, 1, channelUpdate) :: ExpiryTooFar :: Nil

    msgs.foreach {
      msg => {
        val encoded = failureMessageCodec.encode(msg).require
        val decoded = failureMessageCodec.decode(encoded).require
        assert(msg === decoded.value)
      }
    }
  }

  test("bad onion failure code") {
    val msgs = Seq(
      InvalidOnionVersion(randomBytes32),
      InvalidOnionHmac(randomBytes32),
      InvalidOnionKey(randomBytes32),
      InvalidOnionUnknown(randomBytes32)
    )

    msgs.foreach {
      msg => {
        val encoded = failureMessageCodec.encode(msg).require.toByteVector
        val failureCode = uint16.decode(encoded.take(2).toBitVector).require.value
        assert(failureCode === msg.failureCode)
      }
    }
  }

  test("encode/decode failure onion") {
    val codec = failureOnionCodec(Hmac256(ByteVector32.Zeroes))
    val testCases = Seq(
      hex"41a824e2d630111669fa3e52b600a518f369691909b4e89205dc624ee17ed2c1 0022 c006 2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a 00de 000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
      hex"ba6e122b2941619e2106e8437bf525356ffc8439ac3b2245f68546e298a08cc6 000a 400f 000000000000002a 00f6 000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
    ) zip Seq(
      InvalidOnionKey(ByteVector32(hex"2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a")),
      IncorrectOrUnknownPaymentDetails(42)
    )

    for ((bin, expected) <- testCases) {
      val decoded = codec.decode(bin.toBitVector).require.value
      assert(decoded === expected)

      val encoded = codec.encode(expected).require.toByteVector
      assert(encoded === bin)
    }
  }

  test("decode invalid failure onion packet") {
    val codec = failureOnionCodec(Hmac256(ByteVector32.Zeroes))
    val testCases = Seq(
      // Invalid failure message.
      hex"fd2f3eb163dacfa7fe2ec1a7dc73c33438e7ca97c561475cf0dc96dc15a75039 0020 c005 2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a 00e0 0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
      // Invalid mac.
      hex"0000000000000000000000000000000000000000000000000000000000000000 0022 c006 2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a 00de 000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
      // Padding too small.
      hex"7bfb2aa46218240684f623322ae48af431d06986c82e210bb0cee83c7ddb2ba8 0002 4001 0002 0000",
      // Padding length doesn't match actual padding.
      hex"8c92256e45bbe765130d952e6c043cf594ab25224701f5477fce0e50ee88fa21 0002 4001 0002 0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
      // Padding too big.
      hex"6f9e2c0e44b3692dac37523c6ff054cc9b26ecab1a78ed6906a46848bffc2bd5 0002 4001 00ff 000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
      // Padding length doesn't match actual padding.
      hex"3898307b7c01781628ff6f854a4a78524541e4afde9b44046bdb84093f082d9d 0002 4001 00ff 0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
    )

    for (testCase <- testCases) {
      assert(codec.decode(testCase.toBitVector).isFailure)
    }
  }

  test("support encoding of channel_update with/without type in failure messages") {
    val tmp_channel_failure_notype = hex"10070080cc3e80149073ed487c76e48e9622bf980f78267b8a34a3f61921f2d8fce6063b08e74f34a073a13f2097337e4915bb4c001f3b5c4d81e9524ed575e1f45782196fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d619000000000008260500041300005b91b52f0003000e00000000000003e80000000100000001"
    val tmp_channel_failure_withtype = hex"100700820102cc3e80149073ed487c76e48e9622bf980f78267b8a34a3f61921f2d8fce6063b08e74f34a073a13f2097337e4915bb4c001f3b5c4d81e9524ed575e1f45782196fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d619000000000008260500041300005b91b52f0003000e00000000000003e80000000100000001"
    val ref = TemporaryChannelFailure(ChannelUpdate(ByteVector64(hex"cc3e80149073ed487c76e48e9622bf980f78267b8a34a3f61921f2d8fce6063b08e74f34a073a13f2097337e4915bb4c001f3b5c4d81e9524ed575e1f4578219"), Block.LivenetGenesisBlock.hash, ShortChannelId(0x826050004130000L), 1536275759, 0, 3, 14, 1000, 1, 1, None))

    val u = failureMessageCodec.decode(tmp_channel_failure_notype.toBitVector).require.value
    assert(u === ref)
    val bin = ByteVector(failureMessageCodec.encode(u).require.toByteArray)
    assert(bin === tmp_channel_failure_withtype)
    val u2 = failureMessageCodec.decode(bin.toBitVector).require.value
    assert(u2 === ref)
  }
}
