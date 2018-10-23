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

package fr.acinq.eclair.wire

import fr.acinq.bitcoin.{BinaryData, Block}
import fr.acinq.eclair.ShortChannelId
import org.scalatest.FunSuite
import scodec.bits.BitVector

import scala.util.Random

/**
  * Created by PM on 31/05/2016.
  */

class FailureMessageCodecsSpec extends FunSuite {
  val channelUpdate = ChannelUpdate(
    signature = BinaryData("3045022100c451cd65c88f55b1767941a247e849e12f5f4d4a93a07316659e22f5267d2088022009042a595c6bc8942cd9d729317b82b306edc259fb6b3a3cecb3dd1bd446e90601"),
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

  def randomBytes(size: Int): BinaryData = {
    val bin = new Array[Byte](size)
    Random.nextBytes(bin)
    bin
  }

  test("encode/decode all channel messages") {
    val msgs: List[FailureMessage] =
      InvalidRealm :: TemporaryNodeFailure :: PermanentNodeFailure :: RequiredNodeFeatureMissing ::
        InvalidOnionVersion(randomBytes(32)) :: InvalidOnionHmac(randomBytes(32)) :: InvalidOnionKey(randomBytes(32)) ::
        TemporaryChannelFailure(channelUpdate) :: PermanentChannelFailure :: RequiredChannelFeatureMissing :: UnknownNextPeer ::
        AmountBelowMinimum(123456, channelUpdate) :: FeeInsufficient(546463, channelUpdate) :: IncorrectCltvExpiry(1211, channelUpdate) :: ExpiryTooSoon(channelUpdate) ::
        UnknownPaymentHash :: IncorrectPaymentAmount :: FinalExpiryTooSoon :: FinalIncorrectCltvExpiry(1234) :: ChannelDisabled(0, 1, channelUpdate) :: ExpiryTooFar :: Nil

    msgs.foreach {
      case msg => {
        val encoded = FailureMessageCodecs.failureMessageCodec.encode(msg).require
        val decoded = FailureMessageCodecs.failureMessageCodec.decode(encoded).require
        assert(msg === decoded.value)
      }
    }
  }

  test("support encoding of channel_update with/without type in failure messages") {
    val tmp_channel_failure_notype = BinaryData("10070080cc3e80149073ed487c76e48e9622bf980f78267b8a34a3f61921f2d8fce6063b08e74f34a073a13f2097337e4915bb4c001f3b5c4d81e9524ed575e1f45782196fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d619000000000008260500041300005b91b52f0003000e00000000000003e80000000100000001")
    val tmp_channel_failure_withtype = BinaryData("100700820102cc3e80149073ed487c76e48e9622bf980f78267b8a34a3f61921f2d8fce6063b08e74f34a073a13f2097337e4915bb4c001f3b5c4d81e9524ed575e1f45782196fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d619000000000008260500041300005b91b52f0003000e00000000000003e80000000100000001")
    val ref = TemporaryChannelFailure(ChannelUpdate(BinaryData("3045022100cc3e80149073ed487c76e48e9622bf980f78267b8a34a3f61921f2d8fce6063b022008e74f34a073a13f2097337e4915bb4c001f3b5c4d81e9524ed575e1f457821901"), Block.LivenetGenesisBlock.hash, ShortChannelId(0x826050004130000L), 1536275759, 0, 3, 14, 1000, 1, 1, None))

    val u = FailureMessageCodecs.failureMessageCodec.decode(BitVector.apply(tmp_channel_failure_notype.data)).require.value
    assert(u === ref)
    val bin = BinaryData(FailureMessageCodecs.failureMessageCodec.encode(u).require.toByteArray)
    assert(bin === tmp_channel_failure_withtype)
    val u2 = FailureMessageCodecs.failureMessageCodec.decode(BitVector.apply(bin.data)).require.value
    assert(u2 === ref)
  }
}
