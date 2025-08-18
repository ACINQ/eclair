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

package fr.acinq.eclair.wire.internal.channel

import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{Block, BlockHash, ByteVector32, ByteVector64, Crypto, DeterministicWallet, OutPoint, SatoshiLong, Transaction, TxId}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.ChannelSpendSignature.IndividualSignature
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.crypto.keymanager.{ChannelKeyManager, LocalChannelKeyManager, LocalNodeKeyManager, NodeKeyManager}
import fr.acinq.eclair.json.JsonSerializers
import fr.acinq.eclair.reputation.Reputation
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.Transactions.ZeroFeeHtlcTxAnchorOutputsCommitmentFormat
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.internal.channel.ChannelCodecs._
import fr.acinq.eclair.wire.protocol.{CommonCodecs, UpdateAddHtlc}
import org.json4s.jackson.Serialization
import org.scalatest.funsuite.AnyFunSuite
import scodec.DecodeResult
import scodec.bits._
import scodec.codecs.byte

import java.io.{File, FileWriter}
import java.util.UUID
import scala.io.Source

/**
 * Created by PM on 31/05/2016.
 */

class ChannelCodecsSpec extends AnyFunSuite {

  test("nonreg for channel flags codec") {
    // make sure that we correctly decode data encoded with the previous standard 'byte' codec
    assert(CommonCodecs.channelflags.decode(byte.encode(0).require).require == DecodeResult(ChannelFlags(announceChannel = false), BitVector.empty))
    assert(CommonCodecs.channelflags.decode(byte.encode(1).require).require == DecodeResult(ChannelFlags(announceChannel = true), BitVector.empty))
  }

  test("backward compatibility older codecs (integrity)") {
    // It's not enough to just verify that codecs migrate without errors, we also need to make sure that the decoded
    // data is correct. To do that, we compare json-serialized representations of the data.

    // NB: If this test fails (it will fail if there was a change in the model), follow this method which will save you
    // a lot of time:
    // 1) set 'debug = true'
    // 2) run the test
    // 3) under test/resources/nonreg/codecs/<parenttestcasedir>/<testcasedir>/ you will find a file 'data.tmp.json'
    // 4) use intellij to compare 'data.json' and 'data.tmp.json' and fix data.json
    // 5) repeat until all test cases are fixed
    // 6) make sure all 'data.tmp.json' have been cleaned up (they will be if the test passes)
    // 7) set 'debug = false'

    val debug = false

    case class TestCase(dir: File, bin: ByteVector, json: String)

    val testCases = for {
      // there are two level of directories for organization purposes
      parentDir <- new File(getClass.getResource(s"/nonreg/codecs").getFile).listFiles()
      dir <- parentDir.listFiles()
    } yield {
      val srcBin = Source.fromFile(new File(dir, "data.bin"))
      val srcJson = Source.fromFile(new File(dir, "data.json"))
      try {
        TestCase(dir, ByteVector.fromValidHex(srcBin.mkString), srcJson.mkString)
      } finally {
        srcBin.close()
        srcJson.close()
      }
    }

    testCases.foreach { testCase =>
      withClue(testCase.dir.getParentFile.getName + "/" + testCase.dir.getName) {
        // we decode with compat codec
        val olddecoded = channelDataCodec.decode(testCase.bin.bits).require.value
        // we check that the decoded data is the same as before
        val oldjson = Serialization.writePretty(olddecoded)(JsonSerializers.formats)
        val tmpjsonfile_opt = if (debug) {
          val tmpjsonfile = new File(testCase.dir.getPath.replace("target\\test-classes", "src\\test\\resources"), "data.tmp.json")
          Serialization.writePretty(olddecoded, new FileWriter(tmpjsonfile))(JsonSerializers.formats)
          Some(tmpjsonfile)
        } else None

        def normalizeLineEndings(s: String) = s.replace("\r\n", "\n")

        assert(normalizeLineEndings(oldjson) == normalizeLineEndings(testCase.json))
        tmpjsonfile_opt.foreach(_.delete())
        // we then encode with new codec
        val newencoded = channelDataCodec.encode(olddecoded).require.bytes
        // and we decode with the new codec
        val newdecoded = channelDataCodec.decode(newencoded.bits).require.value
        // and we make sure that we obtain the same data
        assert(olddecoded == newdecoded)
      }
    }
  }

}

object ChannelCodecsSpec {
  val seed: ByteVector32 = ByteVector32(ByteVector.fill(32)(1))
  val nodeKeyManager: NodeKeyManager = LocalNodeKeyManager(seed, Block.RegtestGenesisBlock.hash)
  val channelKeyManager: ChannelKeyManager = LocalChannelKeyManager(seed, Block.RegtestGenesisBlock.hash)
  val localChannelParams: LocalChannelParams = LocalChannelParams(
    nodeKeyManager.nodeId,
    fundingKeyPath = DeterministicWallet.KeyPath(Seq(42L)),
    initialRequestedChannelReserve_opt = Some(10000 sat),
    upfrontShutdownScript_opt = None,
    isChannelOpener = true,
    paysCommitTxFees = true,
    initFeatures = Features.empty)
  val remoteChannelParams: RemoteChannelParams = RemoteChannelParams(
    nodeId = randomKey().publicKey,
    initialRequestedChannelReserve_opt = Some(10000 sat),
    revocationBasepoint = PrivateKey(ByteVector.fill(32)(2)).publicKey,
    paymentBasepoint = PrivateKey(ByteVector.fill(32)(3)).publicKey,
    delayedPaymentBasepoint = PrivateKey(ByteVector.fill(32)(4)).publicKey,
    htlcBasepoint = PrivateKey(ByteVector.fill(32)(6)).publicKey,
    initFeatures = Features.empty,
    upfrontShutdownScript_opt = None)

  val paymentPreimages: Seq[ByteVector32] = Seq(
    ByteVector32(hex"0000000000000000000000000000000000000000000000000000000000000000"),
    ByteVector32(hex"0101010101010101010101010101010101010101010101010101010101010101"),
    ByteVector32(hex"0202020202020202020202020202020202020202020202020202020202020202"),
    ByteVector32(hex"0303030303030303030303030303030303030303030303030303030303030303"),
    ByteVector32(hex"0404040404040404040404040404040404040404040404040404040404040404")
  )

  val htlcs: Seq[DirectedHtlc] = Seq[DirectedHtlc](
    IncomingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 0, 1000000 msat, Crypto.sha256(paymentPreimages(0)), CltvExpiry(500), TestConstants.emptyOnionPacket, None, accountable = false, None)),
    IncomingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 1, 2000000 msat, Crypto.sha256(paymentPreimages(1)), CltvExpiry(501), TestConstants.emptyOnionPacket, None, accountable = false, None)),
    OutgoingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 30, 2000000 msat, Crypto.sha256(paymentPreimages(2)), CltvExpiry(502), TestConstants.emptyOnionPacket, None, accountable = false, None)),
    OutgoingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 31, 3000000 msat, Crypto.sha256(paymentPreimages(3)), CltvExpiry(503), TestConstants.emptyOnionPacket, None, accountable = false, None)),
    IncomingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 2, 4000000 msat, Crypto.sha256(paymentPreimages(4)), CltvExpiry(504), TestConstants.emptyOnionPacket, None, accountable = false, None))
  )

  val normal: DATA_NORMAL = {
    val origins = Map(
      42L -> Origin.Cold(Upstream.Local(UUID.randomUUID)),
      15000L -> Origin.Cold(Upstream.Cold.Channel(ByteVector32(ByteVector.fill(32)(42)), 43, 11_000_000 msat))
    )
    makeChannelDataNormal(htlcs, origins)
  }

  def makeChannelDataNormal(htlcs: Seq[DirectedHtlc], origins: Map[Long, Origin]): DATA_NORMAL = {
    val channelUpdate = Announcements.makeChannelUpdate(BlockHash(ByteVector32(ByteVector.fill(32)(1))), randomKey(), randomKey().publicKey, ShortChannelId(142553), CltvExpiryDelta(42), 15 msat, 575 msat, 53, Channel.MAX_FUNDING_WITHOUT_WUMBO.toMilliSatoshi)
    val fundingTx = Transaction.read("0200000001adbb20ea41a8423ea937e76e8151636bf6093b70eaff942930d20576600521fd000000006b48304502210090587b6201e166ad6af0227d3036a9454223d49a1f11839c1a362184340ef0240220577f7cd5cca78719405cbf1de7414ac027f0239ef6e214c90fcaab0454d84b3b012103535b32d5eb0a6ed0982a0479bbadc9868d9836f6ba94dd5a63be16d875069184ffffffff028096980000000000220020c015c4a6be010e21657068fc2e6a9d02b27ebe4d490a25846f7237f104d1a3cd20256d29010000001600143ca33c2e4446f4a305f23c80df8ad1afdcf652f900000000")
    val fundingAmount = fundingTx.txOut.head.amount
    val fundingTxIndex = 0
    val remoteFundingPubKey = PrivateKey(ByteVector32(ByteVector.fill(32)(1)) :+ 1.toByte).publicKey
    val remoteSig = ByteVector64(hex"2148d2d4aac8c793eb82d31bcf22d4db707b9fd7eee1b89b4b1444c9e19ab7172bab8c3d997d29163fa0cb255c75afb8ade13617ad1350c1515e9be4a222a04d")
    val localCommitParams = CommitParams(546 sat, 10_000 msat, UInt64(50_000_000), 50, CltvExpiryDelta(144))
    val localCommit = LocalCommit(0, CommitmentSpec(htlcs.toSet, FeeratePerKw(1500 sat), 50000000 msat, 70000000 msat), TxId.fromValidHex("2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a"), IndividualSignature(remoteSig), Nil)
    val remoteCommitParams = CommitParams(546 sat, 5_000 msat, UInt64(5_000_000), 50, CltvExpiryDelta(144))
    val remoteCommit = RemoteCommit(0, CommitmentSpec(htlcs.map(_.opposite).toSet, FeeratePerKw(1500 sat), 50000 msat, 700000 msat), TxId.fromValidHex("0303030303030303030303030303030303030303030303030303030303030303"), PrivateKey(ByteVector.fill(32)(4)).publicKey)
    val channelId = htlcs.headOption.map(_.add.channelId).getOrElse(ByteVector32.Zeroes)
    val channelFlags = ChannelFlags(announceChannel = true)
    val commitments = Commitments(
      ChannelParams(channelId, ChannelConfig.standard, ChannelFeatures(), localChannelParams, remoteChannelParams, channelFlags),
      CommitmentChanges(LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil, Nil), localNextHtlcId = 32, remoteNextHtlcId = 4),
      Seq(Commitment(fundingTxIndex, 0, OutPoint(fundingTx.txid, 0), fundingAmount, remoteFundingPubKey, LocalFundingStatus.SingleFundedUnconfirmedFundingTx(None), RemoteFundingStatus.NotLocked, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat, localCommitParams, localCommit, remoteCommitParams, remoteCommit, None)),
      remoteNextCommitInfo = Right(randomKey().publicKey),
      remotePerCommitmentSecrets = ShaChain.init,
      originChannels = origins)
    DATA_NORMAL(commitments, ShortIdAliases(ShortChannelId.generateLocalAlias(), None), None, channelUpdate, SpliceStatus.NoSplice, None, None, None)
  }

}
