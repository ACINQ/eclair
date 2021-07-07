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

package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{ByteVector32, Crypto, SatoshiLong, Script, ScriptFlags, Transaction}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.{ChannelFeatures, ChannelTypes}
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.crypto.Generators
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.wire.protocol.UpdateAddHtlc
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, FeatureSupport, Features, MilliSatoshi, MilliSatoshiLong, TestConstants}
import grizzled.slf4j.Logging
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._

import scala.io.Source

trait TestVectorsSpec extends AnyFunSuite with Logging {

  // @formatter:off
  def filename: String
  def channelFeatures: ChannelFeatures
  def commitmentFormat: CommitmentFormat
  // @formatter:on

  val tests = {
    val tests = collection.mutable.HashMap.empty[String, Map[String, String]]
    val current = collection.mutable.HashMap.empty[String, String]
    var name = ""
    Source.fromInputStream(classOf[TestVectorsSpec].getResourceAsStream(filename)).getLines().toArray.map(s => s.dropWhile(_.isWhitespace)).foreach(line => {
      if (line.startsWith("name: ")) {
        val Array(_, n) = line.split(": ")
        if (name.nonEmpty) tests.put(name, current.toMap)
        name = n
        current.clear()
      } else {
        line.split(":") match {
          case Array(k, v) => current.put(k, v)
          case _ => ()
        }
      }
    })
    tests.put(name, current.toMap)
    tests
  }

  def getFeerate(name: String): FeeratePerKw = FeeratePerKw(tests(name)("local_feerate_per_kw").trim.toLong.sat)

  def getToLocal(name: String): MilliSatoshi = tests(name)("to_local_msat").trim.toLong.msat

  def getToRemote(name: String): MilliSatoshi = tests(name)("to_remote_msat").trim.toLong.msat

  /*
   # start bitcoin-qt or bitcoind in regtest mode with an empty data directory and priority set to false
   $ rm -rf /tmp/btc1 && mkdir /tmp/btc1 && ~/code/bitcoin/src/qt/bitcoin-qt -txindex -regtest --relaypriority=false -datadir=/tmp/btc1 -port=18441

   # import block #1 (you already have the genesis block #0, using the debug console:
   $ submitblock 0000002006226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910faf2d96b7d903bc86c5dd4b609ceee6ab0ca396cac66c2eac1f671d87a5bd3eed1b849458ffff7f20000000000102000000010000000000000000000000000000000000000000000000000000000000000000ffffffff03510101ffffffff0200f2052a010000002321023699c8328fd3b3071558b651fb18c51e2ea93ebd0e507966b912cb1babf3ff97ac0000000000000000266a24aa21a9ede2f61c3f71d1defd3fa999dfa36953755c690689799962b48bebd836974e8cf900000000
   $ submitblock 0000002006226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910fadbb20ea41a8423ea937e76e8151636bf6093b70eaff942930d20576600521fdc30f9858ffff7f20000000000101000000010000000000000000000000000000000000000000000000000000000000000000ffffffff03510101ffffffff0100f2052a010000001976a9143ca33c2e4446f4a305f23c80df8ad1afdcf652f988ac00000000

   # import the private key that can be used to spend the coinbase tx in block #1
   $ importprivkey cRCH7YNcarfvaiY1GWUKQrRGmoezvfAiqHtdRvxe16shzbd7LDMz

   # generate 500 blocks. this is necessary to activate segwit. You can check its activation status with getblockchaininfo
   $ generate 500
   */

  object Local {
    val commitTxNumber = 42
    val toSelfDelay = CltvExpiryDelta(144)
    val dustLimit = 546 sat
    val payment_basepoint_secret = PrivateKey(hex"1111111111111111111111111111111111111111111111111111111111111111")
    val payment_basepoint = payment_basepoint_secret.publicKey
    val revocation_basepoint_secret = PrivateKey(hex"2222222222222222222222222222222222222222222222222222222222222222")
    val revocation_basepoint = revocation_basepoint_secret.publicKey
    val delayed_payment_basepoint_secret = PrivateKey(hex"3333333333333333333333333333333333333333333333333333333333333333")
    val delayed_payment_basepoint = delayed_payment_basepoint_secret.publicKey
    val funding_privkey = PrivateKey(hex"30ff4956bbdd3222d44cc5e8a1261dab1e07957bdac5ae88fe3261ef321f374901")
    val funding_pubkey = funding_privkey.publicKey
    val per_commitment_point = PublicKey(hex"025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce1486")
    val htlc_privkey = Generators.derivePrivKey(payment_basepoint_secret, per_commitment_point)
    val payment_privkey = if (channelFeatures.hasFeature(Features.StaticRemoteKey)) payment_basepoint_secret else htlc_privkey
    val delayed_payment_privkey = Generators.derivePrivKey(delayed_payment_basepoint_secret, per_commitment_point)
    val revocation_pubkey = PublicKey(hex"0212a140cd0c6539d07cd08dfe09984dec3251ea808b892efeac3ede9402bf2b19")
    val feerate_per_kw = 15000
  }

  object Remote {
    val commitTxNumber = 42
    val toSelfDelay = CltvExpiryDelta(144)
    val dustLimit = 546 sat
    val payment_basepoint_secret = PrivateKey(hex"4444444444444444444444444444444444444444444444444444444444444444")
    val payment_basepoint = payment_basepoint_secret.publicKey
    val revocation_basepoint_secret = PrivateKey(hex"2222222222222222222222222222222222222222222222222222222222222222")
    val revocation_basepoint = revocation_basepoint_secret.publicKey
    val funding_privkey = PrivateKey(hex"1552dfba4f6cf29a62a0af13c8d6981d36d0ef8d61ba10fb0fe90da7634d7e1301")
    val funding_pubkey = funding_privkey.publicKey
    val htlc_privkey = Generators.derivePrivKey(payment_basepoint_secret, Local.per_commitment_point)
    val payment_privkey = if (channelFeatures.hasFeature(Features.StaticRemoteKey)) payment_basepoint_secret else htlc_privkey
  }

  val coinbaseTx = Transaction.read("01000000010000000000000000000000000000000000000000000000000000000000000000ffffffff03510101ffffffff0100f2052a010000001976a9143ca33c2e4446f4a305f23c80df8ad1afdcf652f988ac00000000")

  val fundingTx = Transaction.read("0200000001adbb20ea41a8423ea937e76e8151636bf6093b70eaff942930d20576600521fd000000006b48304502210090587b6201e166ad6af0227d3036a9454223d49a1f11839c1a362184340ef0240220577f7cd5cca78719405cbf1de7414ac027f0239ef6e214c90fcaab0454d84b3b012103535b32d5eb0a6ed0982a0479bbadc9868d9836f6ba94dd5a63be16d875069184ffffffff028096980000000000220020c015c4a6be010e21657068fc2e6a9d02b27ebe4d490a25846f7237f104d1a3cd20256d29010000001600143ca33c2e4446f4a305f23c80df8ad1afdcf652f900000000")
  val fundingAmount = fundingTx.txOut(0).amount
  logger.info(s"# funding-tx: $fundingTx}")

  val commitmentInput = Funding.makeFundingInputInfo(fundingTx.hash, 0, fundingAmount, Local.funding_pubkey, Remote.funding_pubkey)

  val obscured_tx_number = Transactions.obscuredCommitTxNumber(42, isFunder = true, Local.payment_basepoint, Remote.payment_basepoint)
  assert(obscured_tx_number === (0x2bb038521914L ^ 42L))

  logger.info(s"local_payment_basepoint: ${Local.payment_basepoint}")
  logger.info(s"remote_payment_basepoint: ${Remote.payment_basepoint}")
  logger.info(s"local_funding_privkey: ${Local.funding_privkey}")
  logger.info(s"local_funding_pubkey: ${Local.funding_pubkey}")
  logger.info(s"remote_funding_privkey: ${Remote.funding_privkey}")
  logger.info(s"remote_funding_pubkey: ${Remote.funding_pubkey}")
  logger.info(s"local_secretkey: ${Local.payment_privkey}")
  logger.info(s"localkey: ${Local.payment_privkey.publicKey}")
  logger.info(s"remotekey: ${Remote.payment_privkey.publicKey}")
  logger.info(s"local_delayedkey: ${Local.delayed_payment_privkey.publicKey}")
  logger.info(s"local_revocation_key: ${Local.revocation_pubkey}")
  logger.info(s"# funding wscript = ${commitmentInput.redeemScript}")
  assert(commitmentInput.redeemScript == hex"5221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae")

  val paymentPreimages = Seq(
    ByteVector32(hex"0000000000000000000000000000000000000000000000000000000000000000"),
    ByteVector32(hex"0101010101010101010101010101010101010101010101010101010101010101"),
    ByteVector32(hex"0202020202020202020202020202020202020202020202020202020202020202"),
    ByteVector32(hex"0303030303030303030303030303030303030303030303030303030303030303"),
    ByteVector32(hex"0404040404040404040404040404040404040404040404040404040404040404"),
    ByteVector32(hex"0505050505050505050505050505050505050505050505050505050505050505")
  )

  val htlcs = Seq[DirectedHtlc](
    IncomingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 0, 1000000 msat, Crypto.sha256(paymentPreimages(0)), CltvExpiry(500), TestConstants.emptyOnionPacket)),
    IncomingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 1, 2000000 msat, Crypto.sha256(paymentPreimages(1)), CltvExpiry(501), TestConstants.emptyOnionPacket)),
    OutgoingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 0, 2000000 msat, Crypto.sha256(paymentPreimages(2)), CltvExpiry(502), TestConstants.emptyOnionPacket)),
    OutgoingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 1, 3000000 msat, Crypto.sha256(paymentPreimages(3)), CltvExpiry(503), TestConstants.emptyOnionPacket)),
    IncomingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 2, 4000000 msat, Crypto.sha256(paymentPreimages(4)), CltvExpiry(504), TestConstants.emptyOnionPacket)),
    OutgoingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 2, 5000001.msat, Crypto.sha256(paymentPreimages(5)), CltvExpiry(505), TestConstants.emptyOnionPacket)),
    OutgoingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 3, 5000000.msat, Crypto.sha256(paymentPreimages(5)), CltvExpiry(506), TestConstants.emptyOnionPacket))
  )
  val htlcScripts = htlcs.map {
    case OutgoingHtlc(add) => Scripts.htlcOffered(Local.htlc_privkey.publicKey, Remote.htlc_privkey.publicKey, Local.revocation_pubkey, Crypto.ripemd160(add.paymentHash), commitmentFormat)
    case IncomingHtlc(add) => Scripts.htlcReceived(Local.htlc_privkey.publicKey, Remote.htlc_privkey.publicKey, Local.revocation_pubkey, Crypto.ripemd160(add.paymentHash), add.cltvExpiry, commitmentFormat)
  }
  val defaultHtlcs = htlcs.take(5) // most test cases only use the first 5 htlcs

  def dir2string(htlc: DirectedHtlc): String = htlc match {
    case _: IncomingHtlc => "remote->local"
    case _: OutgoingHtlc => "local->remote"
  }

  for (i <- htlcs.indices) {
    logger.info(s"htlc $i direction: ${dir2string(htlcs(i))}")
    logger.info(s"htlc $i amount_msat: ${htlcs(i).add.amountMsat}")
    logger.info(s"htlc $i expiry: ${htlcs(i).add.cltvExpiry}")
    logger.info(s"htlc $i payment_preimage: ${if (i < paymentPreimages.size) paymentPreimages(i) else paymentPreimages.last}")
  }

  def run(name: String, specHtlcs: Set[DirectedHtlc]): (CommitTx, Seq[TransactionWithInputInfo]) = {
    logger.info(s"name: $name")
    val spec = CommitmentSpec(specHtlcs, getFeerate(name), getToLocal(name), getToRemote(name))
    logger.info(s"to_local_msat: ${spec.toLocal}")
    logger.info(s"to_remote_msat: ${spec.toRemote}")
    logger.info(s"local_feerate_per_kw: ${spec.feeratePerKw}")

    val outputs = Transactions.makeCommitTxOutputs(
      localIsFunder = true,
      localDustLimit = Local.dustLimit,
      localRevocationPubkey = Local.revocation_pubkey,
      toLocalDelay = Local.toSelfDelay,
      localDelayedPaymentPubkey = Local.delayed_payment_privkey.publicKey,
      remotePaymentPubkey = Remote.payment_privkey.publicKey,
      localHtlcPubkey = Local.htlc_privkey.publicKey,
      remoteHtlcPubkey = Remote.htlc_privkey.publicKey,
      localFundingPubkey = Local.funding_pubkey,
      remoteFundingPubkey = Remote.funding_pubkey,
      spec,
      commitmentFormat)

    val commitTx = {
      val tx = Transactions.makeCommitTx(
        commitTxInput = commitmentInput,
        commitTxNumber = Local.commitTxNumber,
        localPaymentBasePoint = Local.payment_basepoint,
        remotePaymentBasePoint = Remote.payment_basepoint,
        localIsFunder = true,
        outputs = outputs)
      val local_sig = Transactions.sign(tx, Local.funding_privkey, TxOwner.Local, commitmentFormat)
      logger.info(s"# local_signature = ${Scripts.der(local_sig).dropRight(1).toHex}")
      val remote_sig = Transactions.sign(tx, Remote.funding_privkey, TxOwner.Remote, commitmentFormat)
      logger.info(s"remote_signature: ${Scripts.der(remote_sig).dropRight(1).toHex}")
      Transactions.addSigs(tx, Local.funding_pubkey, Remote.funding_pubkey, local_sig, remote_sig)
    }

    val baseFee = Transactions.commitTxFeeMsat(Local.dustLimit, spec, commitmentFormat)
    logger.info(s"# base commitment transaction fee = ${baseFee.toLong}")
    val actualFee = fundingAmount - commitTx.tx.txOut.map(_.amount).sum
    logger.info(s"# actual commitment transaction fee = ${actualFee.toLong}")
    commitTx.tx.txOut.foreach(txOut => {
      txOut.publicKeyScript.length match {
        case 22 => logger.info(s"# to-remote amount ${txOut.amount.toLong} P2WPKH(${Remote.payment_privkey.publicKey})")
        case 34 =>
          val index = htlcScripts.indexWhere(s => Script.write(Script.pay2wsh(s)) == txOut.publicKeyScript)
          if (index == -1) logger.info(s"# to-local amount ${txOut.amount.toLong} wscript ${Script.write(Scripts.toLocalDelayed(Local.revocation_pubkey, Local.toSelfDelay, Local.delayed_payment_privkey.publicKey))}")
          else logger.info(s"# HTLC #${if (htlcs(index).isInstanceOf[OutgoingHtlc]) "offered" else "received"} amount ${txOut.amount.toLong} wscript ${Script.write(htlcScripts(index))}")
      }
    })

    assert(Transactions.getCommitTxNumber(commitTx.tx, isFunder = true, Local.payment_basepoint, Remote.payment_basepoint) === Local.commitTxNumber)
    Transaction.correctlySpends(commitTx.tx, Seq(fundingTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    logger.info(s"output commit_tx: ${commitTx.tx}")

    val unsignedHtlcTxs = Transactions.makeHtlcTxs(
      commitTx.tx,
      Local.dustLimit,
      Local.revocation_pubkey,
      Local.toSelfDelay, Local.delayed_payment_privkey.publicKey,
      spec.feeratePerKw,
      outputs,
      commitmentFormat)

    val htlcTxs: Seq[TransactionWithInputInfo] = unsignedHtlcTxs.sortBy(_.input.outPoint.index)
    logger.info(s"num_htlcs: ${htlcTxs.length}")

    val signedTxs = htlcTxs.collect {
      case tx: HtlcSuccessTx =>
        val localSig = Transactions.sign(tx, Local.htlc_privkey, TxOwner.Local, commitmentFormat)
        val remoteSig = Transactions.sign(tx, Remote.htlc_privkey, TxOwner.Remote, commitmentFormat)
        val htlcIndex = htlcScripts.indexOf(Script.parse(tx.input.redeemScript))
        val preimage = paymentPreimages.find(p => Crypto.sha256(p) == tx.paymentHash).get
        val tx1 = Transactions.addSigs(tx, localSig, remoteSig, preimage, commitmentFormat)
        Transaction.correctlySpends(tx1.tx, Seq(commitTx.tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        logger.info(s"# signature for output #${tx.input.outPoint.index} (htlc-success for htlc #$htlcIndex)")
        logger.info(s"remote_htlc_signature = ${Scripts.der(remoteSig).dropRight(1).toHex}")
        logger.info(s"# local_htlc_signature = ${Scripts.der(localSig).dropRight(1).toHex}")
        logger.info(s"htlc_success_tx (htlc #$htlcIndex): ${tx1.tx}")
        tx1
      case tx: HtlcTimeoutTx =>
        val localSig = Transactions.sign(tx, Local.htlc_privkey, TxOwner.Local, commitmentFormat)
        val remoteSig = Transactions.sign(tx, Remote.htlc_privkey, TxOwner.Remote, commitmentFormat)
        val htlcIndex = htlcScripts.indexOf(Script.parse(tx.input.redeemScript))
        val tx1 = Transactions.addSigs(tx, localSig, remoteSig, commitmentFormat)
        Transaction.correctlySpends(tx1.tx, Seq(commitTx.tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        logger.info(s"# signature for output #${tx.input.outPoint.index} (htlc-timeout for htlc #$htlcIndex)")
        logger.info(s"remote_htlc_signature = ${Scripts.der(remoteSig).dropRight(1).toHex}")
        logger.info(s"# local_htlc_signature = ${Scripts.der(localSig).dropRight(1).toHex}")
        logger.info(s"htlc_timeout_tx (htlc #$htlcIndex): ${tx1.tx}")
        tx1
    }

    (commitTx, signedTxs)
  }

  def verifyHtlcTxs(name: String, htlcTxs: Seq[TransactionWithInputInfo]): Unit = {
    val check = (0 to 4).flatMap(i =>
      tests(name).get(s"htlc_success_tx (htlc #$i)").toSeq ++ tests(name).get(s"htlc_timeout_tx (htlc #$i)").toSeq
    ).toSet.map((tx: String) => Transaction.read(tx))
    assert(htlcTxs.map(_.tx).toSet === check)
  }

  test("simple commitment tx with no HTLCs") {
    val name = "simple commitment tx with no HTLCs"
    val (commitTx, _) = run(name, Set.empty[DirectedHtlc])
    assert(commitTx.tx == Transaction.read(tests(name)("output commit_tx")))
  }

  test("commitment tx with all five HTLCs untrimmed (minimum feerate)") {
    val name = "commitment tx with all five HTLCs untrimmed (minimum feerate)"
    val (commitTx, htlcTxs) = run(name, defaultHtlcs.toSet)
    assert(commitTx.tx == Transaction.read(tests(name)("output commit_tx")))
    verifyHtlcTxs(name, htlcTxs)
  }

  test("commitment tx with seven outputs untrimmed (maximum feerate)") {
    val name = "commitment tx with seven outputs untrimmed (maximum feerate)"
    val (commitTx, htlcTxs) = run(name, defaultHtlcs.toSet)
    assert(commitTx.tx == Transaction.read(tests(name)("output commit_tx")))
    verifyHtlcTxs(name, htlcTxs)
  }

  test("commitment tx with six outputs untrimmed (minimum feerate)") {
    val name = "commitment tx with six outputs untrimmed (minimum feerate)"
    val (commitTx, htlcTxs) = run(name, defaultHtlcs.toSet)
    assert(commitTx.tx == Transaction.read(tests(name)("output commit_tx")))
    verifyHtlcTxs(name, htlcTxs)
  }

  test("commitment tx with six outputs untrimmed (maximum feerate)") {
    val name = "commitment tx with six outputs untrimmed (maximum feerate)"
    val (commitTx, htlcTxs) = run(name, defaultHtlcs.toSet)
    assert(commitTx.tx == Transaction.read(tests(name)("output commit_tx")))
    verifyHtlcTxs(name, htlcTxs)
  }

  test("commitment tx with five outputs untrimmed (minimum feerate)") {
    val name = "commitment tx with five outputs untrimmed (minimum feerate)"
    val (commitTx, htlcTxs) = run(name, defaultHtlcs.toSet)
    assert(commitTx.tx == Transaction.read(tests(name)("output commit_tx")))
    verifyHtlcTxs(name, htlcTxs)
  }

  test("commitment tx with five outputs untrimmed (maximum feerate)") {
    val name = "commitment tx with five outputs untrimmed (maximum feerate)"
    val (commitTx, htlcTxs) = run(name, defaultHtlcs.toSet)
    assert(commitTx.tx == Transaction.read(tests(name)("output commit_tx")))
    verifyHtlcTxs(name, htlcTxs)
  }

  test("commitment tx with four outputs untrimmed (minimum feerate)") {
    val name = "commitment tx with four outputs untrimmed (minimum feerate)"
    val (commitTx, htlcTxs) = run(name, defaultHtlcs.toSet)
    assert(commitTx.tx == Transaction.read(tests(name)("output commit_tx")))
    verifyHtlcTxs(name, htlcTxs)
  }

  test("commitment tx with four outputs untrimmed (maximum feerate)") {
    val name = "commitment tx with four outputs untrimmed (maximum feerate)"
    val (commitTx, htlcTxs) = run(name, defaultHtlcs.toSet)
    assert(commitTx.tx == Transaction.read(tests(name)("output commit_tx")))
    verifyHtlcTxs(name, htlcTxs)
  }

  test("commitment tx with three outputs untrimmed (minimum feerate)") {
    val name = "commitment tx with three outputs untrimmed (minimum feerate)"
    val (commitTx, htlcTxs) = run(name, defaultHtlcs.toSet)
    assert(commitTx.tx == Transaction.read(tests(name)("output commit_tx")))
    verifyHtlcTxs(name, htlcTxs)
  }

  test("commitment tx with three outputs untrimmed (maximum feerate)") {
    val name = "commitment tx with three outputs untrimmed (maximum feerate)"
    val (commitTx, htlcTxs) = run(name, defaultHtlcs.toSet)
    assert(commitTx.tx == Transaction.read(tests(name)("output commit_tx")))
    verifyHtlcTxs(name, htlcTxs)
  }

  test("commitment tx with two outputs untrimmed (minimum feerate)") {
    val name = "commitment tx with two outputs untrimmed (minimum feerate)"
    val (commitTx, htlcTxs) = run(name, defaultHtlcs.toSet)
    assert(commitTx.tx == Transaction.read(tests(name)("output commit_tx")))
    verifyHtlcTxs(name, htlcTxs)
  }

  test("commitment tx with two outputs untrimmed (maximum feerate)") {
    val name = "commitment tx with two outputs untrimmed (maximum feerate)"
    val (commitTx, htlcTxs) = run(name, defaultHtlcs.toSet)
    assert(commitTx.tx == Transaction.read(tests(name)("output commit_tx")))
    verifyHtlcTxs(name, htlcTxs)
  }

  test("commitment tx with one output untrimmed (minimum feerate)") {
    val name = "commitment tx with one output untrimmed (minimum feerate)"
    val (commitTx, htlcTxs) = run(name, defaultHtlcs.toSet)
    assert(commitTx.tx == Transaction.read(tests(name)("output commit_tx")))
    verifyHtlcTxs(name, htlcTxs)
  }

  test("commitment tx with fee greater than funder amount") {
    val name = "commitment tx with fee greater than funder amount"
    val (commitTx, htlcTxs) = run(name, defaultHtlcs.toSet)
    assert(commitTx.tx == Transaction.read(tests(name)("output commit_tx")))
    verifyHtlcTxs(name, htlcTxs)
  }

  test("commitment tx with 3 htlc outputs, 2 offered having the same amount and preimage") {
    val name = "commitment tx with 3 htlc outputs, 2 offered having the same amount and preimage"
    val someHtlcs = Seq(htlcs(1), htlcs(6), htlcs(5))
    val (commitTx, htlcTxs) = run(name, someHtlcs.toSet[DirectedHtlc])
    assert(commitTx.tx == Transaction.read(tests(name)("output commit_tx")))

    assert(htlcTxs.size == 3) // one htlc-success-tx + two htlc-timeout-tx
    assert(htlcTxs(0).tx == Transaction.read(tests(name)("htlc_success_tx (htlc #1)")))
    assert(htlcTxs(1).tx == Transaction.read(tests(name)("htlc_timeout_tx (htlc #5)")))
    assert(htlcTxs(2).tx == Transaction.read(tests(name)("htlc_timeout_tx (htlc #6)")))
  }

}

class DefaultCommitmentTestVectorSpec extends TestVectorsSpec {
  // @formatter:off
  override def filename: String = "/bolt3-tx-test-vectors-default-commitment-format.txt"
  override def channelFeatures: ChannelFeatures = ChannelFeatures(ChannelTypes.Standard.features)
  override def commitmentFormat: CommitmentFormat = DefaultCommitmentFormat
  // @formatter:on
}

class StaticRemoteKeyTestVectorSpec extends TestVectorsSpec {
  // @formatter:off
  override def filename: String = "/bolt3-tx-test-vectors-static-remotekey-format.txt"
  override def channelFeatures: ChannelFeatures = ChannelFeatures(ChannelTypes.StaticRemoteKey.features)
  override def commitmentFormat: CommitmentFormat = DefaultCommitmentFormat
  // @formatter:on
}

class AnchorOutputsTestVectorSpec extends TestVectorsSpec {
  // @formatter:off
  override def filename: String = "/bolt3-tx-test-vectors-anchor-outputs-format.txt"
  override def channelFeatures: ChannelFeatures = ChannelFeatures(ChannelTypes.AnchorOutputs.features)
  override def commitmentFormat: CommitmentFormat = AnchorOutputsCommitmentFormat
  // @formatter:on
}
