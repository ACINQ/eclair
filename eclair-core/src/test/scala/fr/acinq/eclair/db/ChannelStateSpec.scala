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

package fr.acinq.eclair.db

import fr.acinq.bitcoin.Crypto.{PrivateKey, Scalar}
import fr.acinq.bitcoin.{Block, ByteVector32, Crypto, DeterministicWallet, MilliSatoshi, Satoshi, Transaction}
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.{LocalKeyManager, ShaChain, Sphinx}
import fr.acinq.eclair.payment.{Local, Relayed}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.Transactions.CommitTx
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.{ChannelCodecs, UpdateAddHtlc}
import fr.acinq.eclair.{ShortChannelId, UInt64, randomKey}
import org.scalatest.FunSuite
import scodec.bits._

/**
  * Created by fabrice on 07/02/17.
  */

class ChannelStateSpec extends FunSuite {

  import ChannelStateSpec._

  test("basic serialization test (NORMAL)") {
    val data = normal
    val bin = ChannelCodecs.DATA_NORMAL_Codec.encode(data).require
    val check = ChannelCodecs.DATA_NORMAL_Codec.decodeValue(bin).require
    assert(data.commitments.localCommit.spec === check.commitments.localCommit.spec)
    assert(data === check)
  }
}

object ChannelStateSpec {
  val keyManager = new LocalKeyManager(ByteVector32(ByteVector.fill(32)(1)), Block.RegtestGenesisBlock.hash)
  val localParams = LocalParams(
    keyManager.nodeId,
    channelKeyPath = DeterministicWallet.KeyPath(Seq(42L)),
    dustLimitSatoshis = Satoshi(546).toLong,
    maxHtlcValueInFlightMsat = UInt64(50000000),
    channelReserveSatoshis = 10000,
    htlcMinimumMsat = 10000,
    toSelfDelay = 144,
    maxAcceptedHtlcs = 50,
    defaultFinalScriptPubKey = ByteVector.empty,
    isFunder = true,
    globalFeatures = hex"dead",
    localFeatures = hex"beef")

  val remoteParams = RemoteParams(
    nodeId = randomKey.publicKey,
    dustLimitSatoshis = Satoshi(546).toLong,
    maxHtlcValueInFlightMsat = UInt64(5000000),
    channelReserveSatoshis = 10000,
    htlcMinimumMsat = 5000,
    toSelfDelay = 144,
    maxAcceptedHtlcs = 50,
    fundingPubKey = PrivateKey(ByteVector32(ByteVector.fill(32)(1)) :+ 1.toByte).publicKey,
    revocationBasepoint = Scalar(ByteVector.fill(32)(2)).toPoint,
    paymentBasepoint = Scalar(ByteVector.fill(32)(3)).toPoint,
    delayedPaymentBasepoint = Scalar(ByteVector.fill(32)(4)).toPoint,
    htlcBasepoint = Scalar(ByteVector.fill(32)(6)).toPoint,
    globalFeatures = hex"dead",
    localFeatures = hex"beef")

  val paymentPreimages = Seq(
    ByteVector32(hex"0000000000000000000000000000000000000000000000000000000000000000"),
    ByteVector32(hex"0101010101010101010101010101010101010101010101010101010101010101"),
    ByteVector32(hex"0202020202020202020202020202020202020202020202020202020202020202"),
    ByteVector32(hex"0303030303030303030303030303030303030303030303030303030303030303"),
    ByteVector32(hex"0404040404040404040404040404040404040404040404040404040404040404")
  )

  val htlcs = Seq(
    DirectedHtlc(IN, UpdateAddHtlc(ByteVector32.Zeroes, 0, MilliSatoshi(1000000).amount, Crypto.sha256(paymentPreimages(0)), 500, ByteVector.fill(Sphinx.PacketLength)(0))),
    DirectedHtlc(IN, UpdateAddHtlc(ByteVector32.Zeroes, 1, MilliSatoshi(2000000).amount, Crypto.sha256(paymentPreimages(1)), 501, ByteVector.fill(Sphinx.PacketLength)(0))),
    DirectedHtlc(OUT, UpdateAddHtlc(ByteVector32.Zeroes, 30, MilliSatoshi(2000000).amount, Crypto.sha256(paymentPreimages(2)), 502, ByteVector.fill(Sphinx.PacketLength)(0))),
    DirectedHtlc(OUT, UpdateAddHtlc(ByteVector32.Zeroes, 31, MilliSatoshi(3000000).amount, Crypto.sha256(paymentPreimages(3)), 503, ByteVector.fill(Sphinx.PacketLength)(0))),
    DirectedHtlc(IN, UpdateAddHtlc(ByteVector32.Zeroes, 2, MilliSatoshi(4000000).amount, Crypto.sha256(paymentPreimages(4)), 504, ByteVector.fill(Sphinx.PacketLength)(0)))
  )

  val fundingTx = Transaction.read("0200000001adbb20ea41a8423ea937e76e8151636bf6093b70eaff942930d20576600521fd000000006b48304502210090587b6201e166ad6af0227d3036a9454223d49a1f11839c1a362184340ef0240220577f7cd5cca78719405cbf1de7414ac027f0239ef6e214c90fcaab0454d84b3b012103535b32d5eb0a6ed0982a0479bbadc9868d9836f6ba94dd5a63be16d875069184ffffffff028096980000000000220020c015c4a6be010e21657068fc2e6a9d02b27ebe4d490a25846f7237f104d1a3cd20256d29010000001600143ca33c2e4446f4a305f23c80df8ad1afdcf652f900000000")
  val fundingAmount = fundingTx.txOut(0).amount
  val commitmentInput = Funding.makeFundingInputInfo(fundingTx.hash, 0, fundingAmount, keyManager.fundingPublicKey(localParams.channelKeyPath).publicKey, remoteParams.fundingPubKey)

  val localCommit = LocalCommit(0, CommitmentSpec(htlcs.toSet, 1500, 50000000, 70000000), PublishableTxs(CommitTx(commitmentInput, Transaction(2, Nil, Nil, 0)), Nil))
  val remoteCommit = RemoteCommit(0, CommitmentSpec(htlcs.map(htlc => htlc.copy(direction = htlc.direction.opposite)).toSet, 1500, 50000, 700000), ByteVector32(hex"0303030303030303030303030303030303030303030303030303030303030303"), Scalar(ByteVector.fill(32)(4)).toPoint)
  val commitments = Commitments(localParams, remoteParams, channelFlags = 0x01.toByte, localCommit, remoteCommit, LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil, Nil),
    localNextHtlcId = 32L,
    remoteNextHtlcId = 4L,
    originChannels = Map(42L -> Local(None), 15000L -> Relayed(ByteVector32(ByteVector.fill(32)(42)), 43, 11000000L, 10000000L)),
    remoteNextCommitInfo = Right(randomKey.publicKey),
    commitInput = commitmentInput, remotePerCommitmentSecrets = ShaChain.init, channelId = ByteVector32.Zeroes)

  val channelUpdate = Announcements.makeChannelUpdate(ByteVector32(ByteVector.fill(32)(1)), randomKey, randomKey.publicKey, ShortChannelId(142553), 42, 15, 575, 53, Channel.MAX_FUNDING_SATOSHIS * 1000L)

  val normal = DATA_NORMAL(commitments, ShortChannelId(42), true, None, channelUpdate, None, None)
}
