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
import fr.acinq.bitcoin._
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.crypto.Generators
import fr.acinq.eclair.transactions.Transactions.{HtlcSuccessTx, HtlcTimeoutTx, TransactionWithInputInfo}
import fr.acinq.eclair.wire.UpdateAddHtlc
import grizzled.slf4j.Logging
import org.scalatest.FunSuite
import scodec.bits._

import scala.io.Source

class TestVectorsSpec extends FunSuite with Logging {

  val results = collection.mutable.HashMap.empty[String, Map[String, String]]
  val current = collection.mutable.HashMap.empty[String, String]
  var name = ""
  Source.fromInputStream(classOf[TestVectorsSpec].getResourceAsStream("/bolt3-tx-test-vectors.txt")).getLines().toArray.map(s => s.dropWhile(_.isWhitespace)).map(line => {
    if (line.startsWith("name: ")) {
      val Array(_, n) = line.split(": ")
      if (!name.isEmpty) results.put(name, current.toMap)
      name = n
      current.clear()
    } else {
      line.split(":") match {
        case Array(k, v) => current.put(k, v)
        case _ => ()
      }
    }
  })
  results.put(name, current.toMap)

  /*
   # start bitcoin-qt or bitcoind in regtest mode with an empty data directory and priority set to false
   $ rm -rf /tmp/btc1 && mkdir /tmp/btc1 && ~/code/bitcoin/src/qt/bitcoin-qt -txindex -regtest --relaypriority=false -datadir=/tmp/btc1 -port=18441

   # import block #1 (you already have the genesis block #0, using the debug console:
   submitblock 0000002006226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910faf2d96b7d903bc86c5dd4b609ceee6ab0ca396cac66c2eac1f671d87a5bd3eed1b849458ffff7f20000000000102000000010000000000000000000000000000000000000000000000000000000000000000ffffffff03510101ffffffff0200f2052a010000002321023699c8328fd3b3071558b651fb18c51e2ea93ebd0e507966b912cb1babf3ff97ac0000000000000000266a24aa21a9ede2f61c3f71d1defd3fa999dfa36953755c690689799962b48bebd836974e8cf900000000
   submitblock 0000002006226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910fadbb20ea41a8423ea937e76e8151636bf6093b70eaff942930d20576600521fdc30f9858ffff7f20000000000101000000010000000000000000000000000000000000000000000000000000000000000000ffffffff03510101ffffffff0100f2052a010000001976a9143ca33c2e4446f4a305f23c80df8ad1afdcf652f988ac00000000

   # import the private key that can be used to spend the coinbase tx in block #1
   importprivkey cRCH7YNcarfvaiY1GWUKQrRGmoezvfAiqHtdRvxe16shzbd7LDMz

   # generate 499 blocks. this is necessay to activate segwit. You can check its activation status wth getblockchaininfo
   generate 500
   */
  object Local {
    val commitTxNumber = 42
    val toSelfDelay = 144
    val dustLimit = Satoshi(546)
    val payment_basepoint_secret = PrivateKey(hex"1111111111111111111111111111111111111111111111111111111111111111")
    val payment_basepoint = payment_basepoint_secret.publicKey
    val revocation_basepoint_secret = PrivateKey(hex"2222222222222222222222222222222222222222222222222222222222222222")
    val revocation_basepoint = revocation_basepoint_secret.publicKey
    val delayed_payment_basepoint_secret = PrivateKey(hex"3333333333333333333333333333333333333333333333333333333333333333")
    val delayed_payment_basepoint = delayed_payment_basepoint_secret.publicKey
    val funding_privkey = PrivateKey(hex"30ff4956bbdd3222d44cc5e8a1261dab1e07957bdac5ae88fe3261ef321f374901")
    val funding_pubkey = funding_privkey.publicKey

    val per_commitment_point = PublicKey(hex"025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce1486")
    val payment_privkey = Generators.derivePrivKey(payment_basepoint_secret, per_commitment_point)
    val delayed_payment_privkey = Generators.derivePrivKey(delayed_payment_basepoint_secret, per_commitment_point)
    val revocation_pubkey = PublicKey(hex"0212a140cd0c6539d07cd08dfe09984dec3251ea808b892efeac3ede9402bf2b19")
    val feerate_per_kw = 15000
  }

  /*
   <!-- We derive the test vector values as per Key Derivation, though it's not
      required for this test.  They're included here for completeness and
    in case someone wants to reproduce the test vectors themselves:

  INTERNAL: remote_funding_privkey: 1552dfba4f6cf29a62a0af13c8d6981d36d0ef8d61ba10fb0fe90da7634d7e130101
  INTERNAL: local_payment_basepoint_secret: 111111111111111111111111111111111111111111111111111111111111111101
  INTERNAL: local_revocation_basepoint_secret: 222222222222222222222222222222222222222222222222222222222222222201
  INTERNAL: local_delayed_payment_basepoint_secret: 333333333333333333333333333333333333333333333333333333333333333301
  INTERNAL: remote_payment_basepoint_secret: 444444444444444444444444444444444444444444444444444444444444444401
  x_local_per_commitment_secret: 1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a0908070605040302010001
  # From local_revocation_basepoint_secret
  INTERNAL: local_revocation_basepoint: 02466d7fcae563e5cb09a0d1870bb580344804617879a14949cf22285f1bae3f27
  # From local_delayed_payment_basepoint_secret
  INTERNAL: local_delayed_payment_basepoint: 023c72addb4fdf09af94f0c94d7fe92a386a7e70cf8a1d85916386bb2535c7b1b1
  INTERNAL: local_per_commitment_point: 025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce1486
  INTERNAL: remote_secretkey: 839ad0480cde69fc721fb8e919dcf20bc4f2b3374c7b27ff37f200ddfa7b0edb01
  # From local_delayed_payment_basepoint_secret, local_per_commitment_point and local_delayed_payment_basepoint
  INTERNAL: local_delayed_secretkey: adf3464ce9c2f230fd2582fda4c6965e4993ca5524e8c9580e3df0cf226981ad01
  -->
  */

  object Remote {
    val commitTxNumber = 42
    val toSelfDelay = 144
    val dustLimit = Satoshi(546)
    val payment_basepoint_secret = PrivateKey(hex"4444444444444444444444444444444444444444444444444444444444444444")
    val payment_basepoint = payment_basepoint_secret.publicKey
    val revocation_basepoint_secret = PrivateKey(hex"2222222222222222222222222222222222222222222222222222222222222222")
    val revocation_basepoint = revocation_basepoint_secret.publicKey
    val funding_privkey = PrivateKey(hex"1552dfba4f6cf29a62a0af13c8d6981d36d0ef8d61ba10fb0fe90da7634d7e1301")
    val funding_pubkey = funding_privkey.publicKey
    val payment_privkey = Generators.derivePrivKey(payment_basepoint_secret, Local.per_commitment_point)
    val per_commitment_point = PublicKey(hex"022c76692fd70814a8d1ed9dedc833318afaaed8188db4d14727e2e99bc619d325")
  }

  val coinbaseTx = Transaction.read("01000000010000000000000000000000000000000000000000000000000000000000000000ffffffff03510101ffffffff0100f2052a010000001976a9143ca33c2e4446f4a305f23c80df8ad1afdcf652f988ac00000000")

  val fundingTx = Transaction.read("0200000001adbb20ea41a8423ea937e76e8151636bf6093b70eaff942930d20576600521fd000000006b48304502210090587b6201e166ad6af0227d3036a9454223d49a1f11839c1a362184340ef0240220577f7cd5cca78719405cbf1de7414ac027f0239ef6e214c90fcaab0454d84b3b012103535b32d5eb0a6ed0982a0479bbadc9868d9836f6ba94dd5a63be16d875069184ffffffff028096980000000000220020c015c4a6be010e21657068fc2e6a9d02b27ebe4d490a25846f7237f104d1a3cd20256d29010000001600143ca33c2e4446f4a305f23c80df8ad1afdcf652f900000000")
  val fundingAmount = fundingTx.txOut(0).amount
  logger.info(s"# funding-tx: $fundingTx}")


  val commitmentInput = Funding.makeFundingInputInfo(fundingTx.hash, 0, fundingAmount, Local.funding_pubkey, Remote.funding_pubkey)

  val obscured_tx_number = Transactions.obscuredCommitTxNumber(42, true, Local.payment_basepoint, Remote.payment_basepoint)
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
    ByteVector32(hex"0404040404040404040404040404040404040404040404040404040404040404")
  )

  val htlcs = Seq(
    DirectedHtlc(IN, UpdateAddHtlc(ByteVector32.Zeroes, 0, MilliSatoshi(1000000).amount, Crypto.sha256(paymentPreimages(0)), 500, ByteVector.empty)),
    DirectedHtlc(IN, UpdateAddHtlc(ByteVector32.Zeroes, 0, MilliSatoshi(2000000).amount, Crypto.sha256(paymentPreimages(1)), 501, ByteVector.empty)),
    DirectedHtlc(OUT, UpdateAddHtlc(ByteVector32.Zeroes, 0, MilliSatoshi(2000000).amount, Crypto.sha256(paymentPreimages(2)), 502, ByteVector.empty)),
    DirectedHtlc(OUT, UpdateAddHtlc(ByteVector32.Zeroes, 0, MilliSatoshi(3000000).amount, Crypto.sha256(paymentPreimages(3)), 503, ByteVector.empty)),
    DirectedHtlc(IN, UpdateAddHtlc(ByteVector32.Zeroes, 0, MilliSatoshi(4000000).amount, Crypto.sha256(paymentPreimages(4)), 504, ByteVector.empty))
  )
  val htlcScripts = htlcs.map(htlc => htlc.direction match {
    case OUT => Scripts.htlcOffered(Local.payment_privkey.publicKey, Remote.payment_privkey.publicKey, Local.revocation_pubkey, Crypto.ripemd160(htlc.add.paymentHash))
    case IN => Scripts.htlcReceived(Local.payment_privkey.publicKey, Remote.payment_privkey.publicKey, Local.revocation_pubkey, Crypto.ripemd160(htlc.add.paymentHash), htlc.add.cltvExpiry)
  })

  def dir2string(dir: Direction) = dir match {
    case IN => "remote->local"
    case OUT => "local->remote"
  }

  for (i <- 0 until htlcs.length) {
    logger.info(s"htlc $i direction: ${dir2string(htlcs(i).direction)}")
    logger.info(s"htlc $i amount_msat: ${htlcs(i).add.amountMsat}")
    logger.info(s"htlc $i expiry: ${htlcs(i).add.cltvExpiry}")
    logger.info(s"htlc $i payment_preimage: ${paymentPreimages(i)}")
  }

  def run(spec: CommitmentSpec) = {
    logger.info(s"to_local_msat: ${spec.toLocalMsat}")
    logger.info(s"to_remote_msat: ${spec.toRemoteMsat}")
    logger.info(s"local_feerate_per_kw: ${spec.feeratePerKw}")

    val commitTx = {
      val tx = Transactions.makeCommitTx(
        commitmentInput,
        Local.commitTxNumber, Local.payment_basepoint, Remote.payment_basepoint,
        true, Local.dustLimit,
        Local.revocation_pubkey, Local.toSelfDelay,
        Local.delayed_payment_privkey.publicKey, Remote.payment_privkey.publicKey,
        Local.payment_privkey.publicKey, Remote.payment_privkey.publicKey, // note: we have payment_key = htlc_key
        spec)

      val local_sig = Transactions.sign(tx, Local.funding_privkey)
      val remote_sig = Transactions.sign(tx, Remote.funding_privkey)

      Transactions.addSigs(tx, Local.funding_pubkey, Remote.funding_pubkey, local_sig, remote_sig)
    }

    val baseFee = Transactions.commitTxFee(Local.dustLimit, spec)
    logger.info(s"# base commitment transaction fee = ${baseFee.toLong}")
    val actualFee = fundingAmount - commitTx.tx.txOut.map(_.amount).sum
    logger.info(s"# actual commitment transaction fee = ${actualFee.toLong}")
    commitTx.tx.txOut.map(txOut => {
      txOut.publicKeyScript.length match {
        case 22 => logger.info(s"# to-remote amount ${txOut.amount.toLong} P2WPKH(${Remote.payment_privkey.publicKey})")
        case 34 =>
          val index = htlcScripts.indexWhere(s => Script.write(Script.pay2wsh(s)) == txOut.publicKeyScript)
          if (index == -1) logger.info(s"# to-local amount ${txOut.amount.toLong} wscript ${Script.write(Scripts.toLocalDelayed(Local.revocation_pubkey, Local.toSelfDelay, Local.delayed_payment_privkey.publicKey))}")
          else logger.info(s"# HTLC ${if (htlcs(index).direction == OUT) "offered" else "received"} amount ${txOut.amount.toLong} wscript ${Script.write(htlcScripts(index))}")
      }
    })

    {
      val tx = Transactions.makeCommitTx(
        commitmentInput,
        Local.commitTxNumber, Local.payment_basepoint, Remote.payment_basepoint,
        true, Local.dustLimit,
        Local.revocation_pubkey, Local.toSelfDelay,
        Local.delayed_payment_privkey.publicKey, Remote.payment_privkey.publicKey,
        Local.payment_privkey.publicKey, Remote.payment_privkey.publicKey, // note: we have payment_key = htlc_key
        spec)

      val local_sig = Transactions.sign(tx, Local.funding_privkey)
      logger.info(s"# local_signature = ${local_sig.dropRight(1).toHex}")
      val remote_sig = Transactions.sign(tx, Remote.funding_privkey)
      logger.info(s"remote_signature: ${remote_sig.dropRight(1).toHex}")
    }

    assert(Transactions.getCommitTxNumber(commitTx.tx, true, Local.payment_basepoint, Remote.payment_basepoint) === Local.commitTxNumber)
    Transaction.correctlySpends(commitTx.tx, Seq(fundingTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    logger.info(s"output commit_tx: ${commitTx.tx}")

    val (unsignedHtlcTimeoutTxs, unsignedHtlcSuccessTxs) = Transactions.makeHtlcTxs(
      commitTx.tx,
      Satoshi(Local.dustLimit.toLong),
      Local.revocation_pubkey,
      Local.toSelfDelay, Local.delayed_payment_privkey.publicKey,
      Local.payment_privkey.publicKey, Remote.payment_privkey.publicKey, // note: we have payment_key = htlc_key
      spec)

    logger.info(s"num_htlcs: ${(unsignedHtlcTimeoutTxs ++ unsignedHtlcSuccessTxs).length}")
    val htlcTxs: Seq[TransactionWithInputInfo] = (unsignedHtlcTimeoutTxs ++ unsignedHtlcSuccessTxs).sortBy(_.input.outPoint.index)


    htlcTxs.collect {
      case tx: HtlcSuccessTx =>
        val remoteSig = Transactions.sign(tx, Remote.payment_privkey)
        val htlcIndex = htlcScripts.indexOf(Script.parse(tx.input.redeemScript))
        logger.info(s"# signature for output ${tx.input.outPoint.index} (htlc $htlcIndex)")
        logger.info(s"remote_htlc_signature: ${remoteSig.dropRight(1).toHex}")
      case tx: HtlcTimeoutTx =>
        val remoteSig = Transactions.sign(tx, Remote.payment_privkey)
        val htlcIndex = htlcScripts.indexOf(Script.parse(tx.input.redeemScript))
        logger.info(s"# signature for output ${tx.input.outPoint.index} (htlc $htlcIndex)")
        logger.info(s"remote_htlc_signature: ${remoteSig.dropRight(1).toHex}")
    }

    val signedTxs = htlcTxs collect {
      case tx: HtlcSuccessTx =>
        //val tx = tx0.copy(tx = tx0.tx.copy(txOut = tx0.tx.txOut(0).copy(amount = Satoshi(545)) :: Nil))
        val localSig = Transactions.sign(tx, Local.payment_privkey)
        val remoteSig = Transactions.sign(tx, Remote.payment_privkey)
        val preimage = paymentPreimages.find(p => Crypto.sha256(p) == tx.paymentHash).get
        val tx1 = Transactions.addSigs(tx, localSig, remoteSig, preimage)
        Transaction.correctlySpends(tx1.tx, Seq(commitTx.tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        val htlcIndex = htlcScripts.indexOf(Script.parse(tx.input.redeemScript))
        logger.info(s"# local_signature = ${localSig.dropRight(1).toHex}")
        logger.info(s"output htlc_success_tx ${htlcIndex}: ${tx1.tx}")
        tx1
      case tx: HtlcTimeoutTx =>
        val localSig = Transactions.sign(tx, Local.payment_privkey)
        val remoteSig = Transactions.sign(tx, Remote.payment_privkey)
        val tx1 = Transactions.addSigs(tx, localSig, remoteSig)
        Transaction.correctlySpends(tx1.tx, Seq(commitTx.tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        logger.info(s"# local_signature = ${localSig.dropRight(1).toHex}")
        val htlcIndex = htlcScripts.indexOf(Script.parse(tx.input.redeemScript))
        logger.info(s"output htlc_timeout_tx ${htlcIndex}: ${tx1.tx}")
        tx1
    }

    (commitTx, signedTxs)
  }

  test("simple commitment tx with no HTLCs") {
    val name = "simple commitment tx with no HTLCs"
    logger.info(s"name: $name")
    val spec = CommitmentSpec(htlcs = Set.empty, feeratePerKw = 15000, toLocalMsat = 7000000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 2)
    assert(commitTx.tx == Transaction.read(results(name)("output commit_tx")))

  }

  test("commitment tx with all 5 htlcs untrimmed (minimum feerate)") {
    val name = "commitment tx with all 5 htlcs untrimmed (minimum feerate)"
    logger.info(s"name: $name")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeratePerKw = 0, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 7)
    assert(commitTx.tx == Transaction.read(results(name)("output commit_tx")))
  }

  test("commitment tx with 7 outputs untrimmed (maximum feerate)") {
    val name = "commitment tx with 7 outputs untrimmed (maximum feerate)"
    logger.info(s"name: $name")
    val feeratePerKw = 454999 / Transactions.htlcSuccessWeight
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeratePerKw = feeratePerKw, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 7)
    assert(commitTx.tx == Transaction.read(results(name)("output commit_tx")))

    val check = (0 to 4).map(i => results(name).get(s"output htlc_success_tx $i").toSeq ++ results(name).get(s"output htlc_timeout_tx $i").toSeq).flatten.toSet.map { s: String => Transaction.read(s) }
    assert(htlcTxs.map(_.tx).toSet == check)
  }

  test("commitment tx with 6 outputs untrimmed (minimum feerate)") {
    val name = "commitment tx with 6 outputs untrimmed (minimum feerate)"
    logger.info(s"name: $name")
    val feeratePerKw = 454999 / Transactions.htlcSuccessWeight
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeratePerKw = feeratePerKw + 1, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 6)
    assert(commitTx.tx == Transaction.read(results(name)("output commit_tx")))

    val check = (0 to 4).map(i => results(name).get(s"output htlc_success_tx $i").toSeq ++ results(name).get(s"output htlc_timeout_tx $i").toSeq).flatten.toSet.map { s: String => Transaction.read(s) }
    assert(htlcTxs.map(_.tx).toSet == check)
  }

  test("commitment tx with 6 outputs untrimmed (maximum feerate)") {
    val name = "commitment tx with 6 outputs untrimmed (maximum feerate)"
    logger.info(s"name: $name")
    val feeratePerKw = 1454999 / Transactions.htlcSuccessWeight
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeratePerKw = feeratePerKw, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 6)
    assert(commitTx.tx == Transaction.read(results(name)("output commit_tx")))

    val check = (0 to 4).map(i => results(name).get(s"output htlc_success_tx $i").toSeq ++ results(name).get(s"output htlc_timeout_tx $i").toSeq).flatten.toSet.map { s: String => Transaction.read(s) }
    assert(htlcTxs.map(_.tx).toSet == check)
  }

  test("commitment tx with 5 outputs untrimmed (minimum feerate)") {
    val name = "commitment tx with 5 outputs untrimmed (minimum feerate)"
    logger.info(s"name: $name")
    val feeratePerKw = 1454999 / Transactions.htlcSuccessWeight
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeratePerKw = feeratePerKw + 1, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 5)
    assert(commitTx.tx == Transaction.read(results(name)("output commit_tx")))

    val check = (0 to 4).map(i => results(name).get(s"output htlc_success_tx $i").toSeq ++ results(name).get(s"output htlc_timeout_tx $i").toSeq).flatten.toSet.map { s: String => Transaction.read(s) }
    assert(htlcTxs.map(_.tx).toSet == check)
  }

  test("commitment tx with 5 outputs untrimmed (maximum feerate)") {
    val name = "commitment tx with 5 outputs untrimmed (maximum feerate)"
    logger.info(s"name: $name")
    val feeratePerKw = 1454999 / Transactions.htlcTimeoutWeight
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeratePerKw = feeratePerKw, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 5)
    assert(commitTx.tx == Transaction.read(results(name)("output commit_tx")))

    val check = (0 to 4).map(i => results(name).get(s"output htlc_success_tx $i").toSeq ++ results(name).get(s"output htlc_timeout_tx $i").toSeq).flatten.toSet.map { s: String => Transaction.read(s) }
    assert(htlcTxs.map(_.tx).toSet == check)
  }

  test("commitment tx with 4 outputs untrimmed (minimum feerate)") {
    val name = "commitment tx with 4 outputs untrimmed (minimum feerate)"
    logger.info(s"name: $name")
    val feeratePerKw = 1454999 / Transactions.htlcTimeoutWeight
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeratePerKw = feeratePerKw + 1, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 4)
    assert(commitTx.tx == Transaction.read(results(name)("output commit_tx")))

    val check = (0 to 4).map(i => results(name).get(s"output htlc_success_tx $i").toSeq ++ results(name).get(s"output htlc_timeout_tx $i").toSeq).flatten.toSet.map { s: String => Transaction.read(s) }
    assert(htlcTxs.map(_.tx).toSet == check)
  }

  test("commitment tx with 4 outputs untrimmed (maximum feerate)") {
    val name = "commitment tx with 4 outputs untrimmed (maximum feerate)"
    logger.info(s"name: $name")
    val feeratePerKw = 2454999 / Transactions.htlcTimeoutWeight
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeratePerKw = feeratePerKw, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 4)
    assert(commitTx.tx == Transaction.read(results(name)("output commit_tx")))

    val check = (0 to 4).map(i => results(name).get(s"output htlc_success_tx $i").toSeq ++ results(name).get(s"output htlc_timeout_tx $i").toSeq).flatten.toSet.map { s: String => Transaction.read(s) }
    assert(htlcTxs.map(_.tx).toSet == check)
  }

  test("commitment tx with 3 outputs untrimmed (minimum feerate)") {
    val name = "commitment tx with 3 outputs untrimmed (minimum feerate)"
    logger.info(s"name: $name")
    val feeratePerKw = 2454999 / Transactions.htlcTimeoutWeight
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeratePerKw = feeratePerKw + 1, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 3)
    assert(commitTx.tx == Transaction.read(results(name)("output commit_tx")))

    val check = (0 to 4).map(i => results(name).get(s"output htlc_success_tx $i").toSeq ++ results(name).get(s"output htlc_timeout_tx $i").toSeq).flatten.toSet.map { s: String => Transaction.read(s) }
    assert(htlcTxs.map(_.tx).toSet == check)
  }

  test("commitment tx with 3 outputs untrimmed (maximum feerate)") {
    val name = "commitment tx with 3 outputs untrimmed (maximum feerate)"
    logger.info(s"name: $name")
    val feeratePerKw = 3454999 / Transactions.htlcSuccessWeight
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeratePerKw = feeratePerKw, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 3)
    assert(commitTx.tx == Transaction.read(results(name)("output commit_tx")))

    val check = (0 to 4).map(i => results(name).get(s"output htlc_success_tx $i").toSeq ++ results(name).get(s"output htlc_timeout_tx $i").toSeq).flatten.toSet.map { s: String => Transaction.read(s) }
    assert(htlcTxs.map(_.tx).toSet == check)
  }

  test("commitment tx with 2 outputs untrimmed (minimum feerate)") {
    val name = "commitment tx with 2 outputs untrimmed (minimum feerate)"
    logger.info(s"name: $name")
    val feeratePerKw = 3454999 / Transactions.htlcSuccessWeight
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeratePerKw = feeratePerKw + 1, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 2)
    assert(commitTx.tx == Transaction.read(results(name)("output commit_tx")))

    val check = (0 to 4).map(i => results(name).get(s"output htlc_success_tx $i").toSeq ++ results(name).get(s"output htlc_timeout_tx $i").toSeq).flatten.toSet.map { s: String => Transaction.read(s) }
    assert(htlcTxs.map(_.tx).toSet == check)
  }

  test("commitment tx with 2 outputs untrimmed (maximum feerate)") {
    val name = "commitment tx with 2 outputs untrimmed (maximum feerate)"
    logger.info(s"name: $name")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeratePerKw = 9651180, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 2)
    assert(commitTx.tx == Transaction.read(results(name)("output commit_tx")))

    val check = (0 to 4).map(i => results(name).get(s"output htlc_success_tx $i").toSeq ++ results(name).get(s"output htlc_timeout_tx $i").toSeq).flatten.toSet.map { s: String => Transaction.read(s) }
    assert(htlcTxs.map(_.tx).toSet == check)
  }

  test("commitment tx with 1 output untrimmed (minimum feerate)") {
    val name = "commitment tx with 1 output untrimmed (minimum feerate)"
    logger.info(s"name: $name")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeratePerKw = 9651181, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 1)
    assert(commitTx.tx == Transaction.read(results(name)("output commit_tx")))

    val check = (0 to 4).map(i => results(name).get(s"output htlc_success_tx $i").toSeq ++ results(name).get(s"output htlc_timeout_tx $i").toSeq).flatten.toSet.map { s: String => Transaction.read(s) }
    assert(htlcTxs.map(_.tx).toSet == check)
  }

  test("commitment tx with fee greater than funder amount") {
    val name = "commitment tx with fee greater than funder amount"
    logger.info(s"name: $name")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeratePerKw = 9651936, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 1)
    assert(commitTx.tx == Transaction.read(results(name)("output commit_tx")))

    val check = (0 to 4).map(i => results(name).get(s"output htlc_success_tx $i").toSeq ++ results(name).get(s"output htlc_timeout_tx $i").toSeq).flatten.toSet.map { s: String => Transaction.read(s) }
    assert(htlcTxs.map(_.tx).toSet == check)
  }
}
