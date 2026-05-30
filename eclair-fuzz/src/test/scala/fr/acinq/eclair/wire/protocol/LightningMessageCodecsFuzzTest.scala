package fr.acinq.eclair.wire.protocol

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import fr.acinq.bitcoin.scalacompat.Protocol
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs.lightningMessageCodec
import scodec.bits.ByteVector

import java.nio.ByteOrder

/**
 * Fuzz tests for Lightning message codecs.
 *
 * Each test prepends the two-byte big-endian message type to the fuzz data,
 * then verifies that the codec does not throw on arbitrary input and that the
 * canonical encoding is stable.
 */
class LightningMessageCodecsFuzzTest {

  private def codecRoundTrip(data: FuzzedDataProvider, msgType: Int): Unit = {
    val wire = Protocol.writeUInt16(msgType, ByteOrder.BIG_ENDIAN) ++ ByteVector(data.consumeRemainingAsBytes())

    val decoded = lightningMessageCodec.decode(wire.bits)
    if (decoded.isFailure) return

    val encoded1 = lightningMessageCodec.encode(decoded.require.value).require
    val encoded2 = lightningMessageCodec.encode(lightningMessageCodec.decode(encoded1).require.value).require
    assert(encoded1 == encoded2)
  }

  // Setup messages

  @FuzzTest(maxDuration = "")
  def fuzzWarning(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 1)

  @FuzzTest(maxDuration = "")
  def fuzzStfu(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 2)

  @FuzzTest(maxDuration = "")
  def fuzzPeerStorageStore(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 7)

  @FuzzTest(maxDuration = "")
  def fuzzPeerStorageRetrieval(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 9)

  @FuzzTest(maxDuration = "")
  def fuzzInit(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 16)

  @FuzzTest(maxDuration = "")
  def fuzzError(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 17)

  @FuzzTest(maxDuration = "")
  def fuzzPing(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 18)

  @FuzzTest(maxDuration = "")
  def fuzzPong(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 19)

  @FuzzTest(maxDuration = "")
  def fuzzRecommendedFeerates(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 39409)

  // Channel messages (single-funded)

  @FuzzTest(maxDuration = "")
  def fuzzOpenChannel(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 32)

  @FuzzTest(maxDuration = "")
  def fuzzAcceptChannel(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 33)

  @FuzzTest(maxDuration = "")
  def fuzzFundingCreated(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 34)

  @FuzzTest(maxDuration = "")
  def fuzzFundingSigned(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 35)

  @FuzzTest(maxDuration = "")
  def fuzzChannelReady(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 36)

  @FuzzTest(maxDuration = "")
  def fuzzShutdown(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 38)

  @FuzzTest(maxDuration = "")
  def fuzzClosingSigned(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 39)

  @FuzzTest(maxDuration = "")
  def fuzzClosingComplete(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 40)

  @FuzzTest(maxDuration = "")
  def fuzzClosingSig(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 41)

  // Interactive transaction messages (dual-funded)

  @FuzzTest(maxDuration = "")
  def fuzzOpenDualFundedChannel(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 64)

  @FuzzTest(maxDuration = "")
  def fuzzAcceptDualFundedChannel(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 65)

  @FuzzTest(maxDuration = "")
  def fuzzTxAddInput(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 66)

  @FuzzTest(maxDuration = "")
  def fuzzTxAddOutput(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 67)

  @FuzzTest(maxDuration = "")
  def fuzzTxRemoveInput(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 68)

  @FuzzTest(maxDuration = "")
  def fuzzTxRemoveOutput(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 69)

  @FuzzTest(maxDuration = "")
  def fuzzTxComplete(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 70)

  @FuzzTest(maxDuration = "")
  def fuzzTxSignatures(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 71)

  @FuzzTest(maxDuration = "")
  def fuzzTxInitRbf(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 72)

  @FuzzTest(maxDuration = "")
  def fuzzTxAckRbf(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 73)

  @FuzzTest(maxDuration = "")
  def fuzzTxAbort(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 74)

  // HTLC messages

  @FuzzTest(maxDuration = "")
  def fuzzStartBatch(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 127)

  @FuzzTest(maxDuration = "")
  def fuzzUpdateAddHtlc(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 128)

  @FuzzTest(maxDuration = "")
  def fuzzUpdateFulfillHtlc(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 130)

  @FuzzTest(maxDuration = "")
  def fuzzUpdateFailHtlc(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 131)

  @FuzzTest(maxDuration = "")
  def fuzzCommitSigBatch(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 53011)

  @FuzzTest(maxDuration = "")
  def fuzzCommitSig(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 132)

  @FuzzTest(maxDuration = "")
  def fuzzRevokeAndAck(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 133)

  @FuzzTest(maxDuration = "")
  def fuzzUpdateFee(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 134)

  @FuzzTest(maxDuration = "")
  def fuzzUpdateFailMalformedHtlc(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 135)

  @FuzzTest(maxDuration = "")
  def fuzzChannelReestablish(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 136)

  // Routing / gossip messages

  @FuzzTest(maxDuration = "")
  def fuzzChannelAnnouncement(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 256)

  @FuzzTest(maxDuration = "")
  def fuzzNodeAnnouncement(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 257)

  @FuzzTest(maxDuration = "")
  def fuzzChannelUpdate(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 258)

  @FuzzTest(maxDuration = "")
  def fuzzAnnouncementSignatures(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 259)

  @FuzzTest(maxDuration = "")
  def fuzzQueryShortChannelIds(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 261)

  @FuzzTest(maxDuration = "")
  def fuzzReplyShortChannelIdsEnd(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 262)

  @FuzzTest(maxDuration = "")
  def fuzzQueryChannelRange(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 263)

  @FuzzTest(maxDuration = "")
  def fuzzReplyChannelRange(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 264)

  @FuzzTest(maxDuration = "")
  def fuzzGossipTimestampFilter(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 265)

  // Onion messages

  @FuzzTest(maxDuration = "")
  def fuzzOnionMessage(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 513)

  // On-the-fly funding messages

  @FuzzTest(maxDuration = "")
  def fuzzWillAddHtlc(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 41041)

  @FuzzTest(maxDuration = "")
  def fuzzWillFailHtlc(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 41042)

  @FuzzTest(maxDuration = "")
  def fuzzWillFailMalformedHtlc(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 41043)

  @FuzzTest(maxDuration = "")
  def fuzzCancelOnTheFlyFunding(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 41044)

  @FuzzTest(maxDuration = "")
  def fuzzAddFeeCredit(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 41045)

  @FuzzTest(maxDuration = "")
  def fuzzCurrentFeeCredit(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 41046)

  // Splice messages

  @FuzzTest(maxDuration = "")
  def fuzzSpliceInit(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 37000)

  @FuzzTest(maxDuration = "")
  def fuzzSpliceAck(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 37002)

  @FuzzTest(maxDuration = "")
  def fuzzSpliceLocked(data: FuzzedDataProvider): Unit =
    codecRoundTrip(data, 37004)
}
