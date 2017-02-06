package fr.acinq.eclair.transactions

import fr.acinq.bitcoin._
import fr.acinq.bitcoin.Crypto.{Point, PrivateKey, PublicKey, Scalar}
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.crypto.Generators
import fr.acinq.eclair.wire.UpdateAddHtlc
import org.scalatest.FunSuite

class TestVectorsSpec extends FunSuite {

  /*
   # start bitcoin-qt or bitcoind in regtest mode with an empty data directory and priority set to false
   $ rm -rf /tmp/btc1 && mkdir /tmp/btc1 && ~/code/bitcoin/src/qt/bitcoin-qt -txindex -regtest --relaypriority=false -datadir=/tmp/btc1 -port=18441

   # import block #1 (you already have the genesis block #0, using the debug console:
   submitblock 0000002006226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910faf2d96b7d903bc86c5dd4b609ceee6ab0ca396cac66c2eac1f671d87a5bd3eed1b849458ffff7f20000000000102000000010000000000000000000000000000000000000000000000000000000000000000ffffffff03510101ffffffff0200f2052a010000002321023699c8328fd3b3071558b651fb18c51e2ea93ebd0e507966b912cb1babf3ff97ac0000000000000000266a24aa21a9ede2f61c3f71d1defd3fa999dfa36953755c690689799962b48bebd836974e8cf900000000

   # import the private key that can be used to spend the coinbase tx in block #1
   importprivkey cR3LefrJ1cM4D5H4UVpSdgNRrX9nVM22EAQDSNj1H3fPhcUJ4GRv

   # generate 499 blocks. this is necessay to activate segwit. You can check its activation status wth getblockchaininfo
   generate 500
   */
  object Local {
    val commitTxNumber = 42
    val toSelfDelay = 144
    val dustLimit = Satoshi(546)
    val payment_basepoint_secret = Scalar(BinaryData("1111111111111111111111111111111111111111111111111111111111111111"))
    val payment_basepoint = payment_basepoint_secret.toPoint
    val revocation_basepoint_secret = Scalar(BinaryData("2222222222222222222222222222222222222222222222222222222222222222"))
    val revocation_basepoint = revocation_basepoint_secret.toPoint
    val delayed_payment_basepoint_secret = Scalar(BinaryData("3333333333333333333333333333333333333333333333333333333333333333"))
    val delayed_payment_basepoint = delayed_payment_basepoint_secret.toPoint
    val funding_privkey = PrivateKey(BinaryData("30ff4956bbdd3222d44cc5e8a1261dab1e07957bdac5ae88fe3261ef321f374901"))
    val funding_pubkey = funding_privkey.publicKey

    val per_commitment_point = Point(BinaryData("025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce1486"))
    val private_key = Generators.derivePrivKey(payment_basepoint_secret, per_commitment_point)
    val public_key = private_key.publicKey
    val delayed_private_key = Generators.derivePrivKey(delayed_payment_basepoint_secret, per_commitment_point)
    val delayed_key = delayed_private_key.publicKey
    val revocation_key = PublicKey(BinaryData("0212a140cd0c6539d07cd08dfe09984dec3251ea808b892efeac3ede9402bf2b19"))
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
 INTERNAL: remote_per_commit_secret: 444444444444444444444444444444444444444444444444444444444444444401
 # From local_revocation_basepoint_secret
 INTERNAL: local_revocation_basepoint: 02466d7fcae563e5cb09a0d1870bb580344804617879a14949cf22285f1bae3f27
 # From local_delayed_payment_basepoint_secret
 INTERNAL: local_delayed_payment_basepoint: 023c72addb4fdf09af94f0c94d7fe92a386a7e70cf8a1d85916386bb2535c7b1b1
 INTERNAL: local_per_commitment_point: 025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce1486
 INTERNAL: remote_per_commitment_point: 022c76692fd70814a8d1ed9dedc833318afaaed8188db4d14727e2e99bc619d325
 INTERNAL: remote_secretkey: 839ad0480cde69fc721fb8e919dcf20bc4f2b3374c7b27ff37f200ddfa7b0edb01
 # From local_delayed_payment_basepoint_secret, local_per_commitment_point and local_delayed_payment_basepoint
 INTERNAL: local_delayed_secretkey: adf3464ce9c2f230fd2582fda4c6965e4993ca5524e8c9580e3df0cf226981ad01
-->

  */

  object Remote {
    val commitTxNumber = 42
    val toSelfDelay = 144
    val dustLimit = Satoshi(546)
    val payment_basepoint_secret = Scalar(BinaryData("4444444444444444444444444444444444444444444444444444444444444444"))
    val payment_basepoint = payment_basepoint_secret.toPoint
    val revocation_basepoint_secret = Scalar(BinaryData("2222222222222222222222222222222222222222222222222222222222222222"))
    val revocation_basepoint = revocation_basepoint_secret.toPoint
    val funding_privkey = PrivateKey(BinaryData("1552dfba4f6cf29a62a0af13c8d6981d36d0ef8d61ba10fb0fe90da7634d7e1301"))
    val funding_pubkey = funding_privkey.publicKey
    val private_key = PrivateKey(BinaryData("839ad0480cde69fc721fb8e919dcf20bc4f2b3374c7b27ff37f200ddfa7b0edb01"))
    val public_key = private_key.publicKey
    val per_commitment_point = Point(BinaryData("022c76692fd70814a8d1ed9dedc833318afaaed8188db4d14727e2e99bc619d325"))
  }

  val coinbaseTx = Transaction.read("02000000010000000000000000000000000000000000000000000000000000000000000000ffffffff03510101ffffffff0200f2052a010000002321023699c8328fd3b3071558b651fb18c51e2ea93ebd0e507966b912cb1babf3ff97ac0000000000000000266a24aa21a9ede2f61c3f71d1defd3fa999dfa36953755c690689799962b48bebd836974e8cf900000000")
  val fundingAmount = 10000000L satoshi

  val fundingTx = {
    val priv = PrivateKey.fromBase58("cR3LefrJ1cM4D5H4UVpSdgNRrX9nVM22EAQDSNj1H3fPhcUJ4GRv", Base58.Prefix.SecretKeyTestnet)
    val tx = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(BinaryData("ed3ebda5871d671fac2e6cc6ca96a30cabe6ee9c604bddc586bc03d9b7962daf").reverse, 0), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
      txOut = Seq(
        TxOut(fundingAmount, Script.pay2wsh(Scripts.multiSig2of2(Local.funding_pubkey, Remote.funding_pubkey))),
        TxOut(coinbaseTx.txOut(0).amount - fundingAmount, Script.pay2pkh(priv.publicKey))
      ),
      lockTime = 0)
    val sig = Transaction.signInput(tx, 0, coinbaseTx.txOut(0).publicKeyScript, SIGHASH_ALL, coinbaseTx.txOut(0).amount, SigVersion.SIGVERSION_BASE, priv)
    val tx1 = tx.updateSigScript(0, OP_PUSHDATA(sig) :: Nil)
    val ctx = new Script.Context(tx, 0, coinbaseTx.txOut(0).amount)
    val runner = new Script.Runner(ctx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS, None)
    assert(runner.verifyScripts(tx1.txIn(0).signatureScript, coinbaseTx.txOut(0).publicKeyScript, tx1.txIn(0).witness))

    Transaction.correctlySpends(tx1, coinbaseTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    tx1
  }

  println(s"# funding-tx: ${Transaction.write(fundingTx)}")


  val commitmentInput = Funding.makeFundingInputInfo(fundingTx.hash, 0, MilliBtc(100), Local.funding_pubkey, Remote.funding_pubkey)

  val obscured_tx_number = Transactions.obscuredCommitTxNumber(42, Local.payment_basepoint, Remote.payment_basepoint)
  assert(obscured_tx_number === (0x2bb038521914L ^ 42L))

  println(s"local_payment_basepoint: ${Local.payment_basepoint}")
  println(s"remote_payment_basepoint: ${Remote.payment_basepoint}")
  println(s"local_funding_privkey: ${Local.funding_privkey}")
  println(s"local_funding_pubkey: ${Local.funding_pubkey}")
  println(s"remote_funding_privkey: ${Remote.funding_privkey}")
  println(s"remote_funding_pubkey: ${Remote.funding_pubkey}")
  println(s"local_secretkey: ${Local.private_key}")
  println(s"localkey: ${Local.public_key}")
  println(s"remotekey: ${Remote.public_key}")
  println(s"local_delayedkey: ${Local.delayed_key}")
  println(s"local_revocation_key: ${Local.revocation_key}")
  println(s"# funding wscript = ${commitmentInput.redeemScript}")

  val paymentPreimages = Seq(
    BinaryData("0000000000000000000000000000000000000000000000000000000000000000"),
    BinaryData("0101010101010101010101010101010101010101010101010101010101010101"),
    BinaryData("0202020202020202020202020202020202020202020202020202020202020202"),
    BinaryData("0303030303030303030303030303030303030303030303030303030303030303"),
    BinaryData("0404040404040404040404040404040404040404040404040404040404040404")
  )

  val htlcs = Seq(
    Htlc(IN, UpdateAddHtlc(0, 0, MilliSatoshi(1000000).amount, 550, Crypto.sha256(paymentPreimages(0)), BinaryData("")), None),
    Htlc(IN, UpdateAddHtlc(0, 0, MilliSatoshi(2000000).amount, 551, Crypto.sha256(paymentPreimages(1)), BinaryData("")), None),
    Htlc(OUT, UpdateAddHtlc(0, 0, MilliSatoshi(2000000).amount, 552, Crypto.sha256(paymentPreimages(2)), BinaryData("")), None),
    Htlc(OUT, UpdateAddHtlc(0, 0, MilliSatoshi(3000000).amount, 553, Crypto.sha256(paymentPreimages(3)), BinaryData("")), None),
    Htlc(IN, UpdateAddHtlc(0, 0, MilliSatoshi(4000000).amount, 554, Crypto.sha256(paymentPreimages(4)), BinaryData("")), None)
  )
  val htlcScripts = htlcs.map(htlc => htlc.direction match {
    case OUT => Scripts.htlcOffered(Local.public_key, Remote.public_key, Crypto.ripemd160(htlc.add.paymentHash))
    case IN => Scripts.htlcReceived(Local.public_key, Remote.public_key, Crypto.ripemd160(htlc.add.paymentHash), htlc.add.expiry)
  })

  def dir2string(dir: Direction) = dir match {
    case IN => "remote->local"
    case OUT => "local->remote"
  }

  for (i <- 0 until htlcs.length) {
    println(s"htlc $i direction: ${dir2string(htlcs(i).direction)}")
    println(s"htlc $i amount_msat: ${htlcs(i).add.amountMsat}")
    println(s"htlc $i expiry: ${htlcs(i).add.expiry}")
    println(s"htlc $i payment_preimage: ${paymentPreimages(i)}")
  }
  println()

  def run(spec: CommitmentSpec, expectedNumberOfOutputs: Int): Unit = {
    println(s"to_local_msat: ${spec.toLocalMsat}")
    println(s"to_remote_msat: ${spec.toRemoteMsat}")
    println(s"feerate_per_kw: ${spec.feeRatePerKw}")

    val commitTx = {
      val tx = Transactions.makeCommitTx(
        commitmentInput,
        Local.commitTxNumber, Local.payment_basepoint, Remote.payment_basepoint,
        true, Local.dustLimit,
        Local.public_key, Local.revocation_key, Local.toSelfDelay, Local.delayed_key,
        Remote.public_key,
        spec)

      assert(tx.tx.txOut.length == expectedNumberOfOutputs)
      val local_sig = Transactions.sign(tx, Local.funding_privkey)
      println(s"# local_signature = $local_sig")
      val remote_sig = Transactions.sign(tx, Remote.funding_privkey)
      println(s"# remote_signature = $remote_sig")

      Transactions.addSigs(tx, Local.funding_pubkey, Remote.funding_pubkey, local_sig, remote_sig)
    }

    val baseFee = Transactions.commitTxFee(spec.feeRatePerKw, Local.dustLimit, spec)
    println(s"# base commitment transaction fee = $baseFee")
    val actualFee = fundingAmount - commitTx.tx.txOut.map(_.amount).sum
    println(s"# actual commitment transaction fee = $actualFee")
    commitTx.tx.txOut.map(txOut => {
      txOut.publicKeyScript.length match {
        case 22 => println(s"# to-remote amount ${txOut.amount}")
        case 34 => println(s"# to-local amount ${txOut.amount}")
      }
    })

    assert(Transactions.getCommitTxNumber(commitTx.tx, Local.payment_basepoint, Remote.payment_basepoint) === Local.commitTxNumber)
    Transaction.correctlySpends(commitTx.tx, Seq(fundingTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    println(s"output commit_tx: ${Transaction.write(commitTx.tx)}")

    val (unsignedHtlcTimeoutTxs, unsignedHtlcSuccessTxs) = Transactions.makeHtlcTxs(
      commitTx.tx,
      Satoshi(Local.dustLimit.toLong),
      Local.revocation_key,
      Local.toSelfDelay, Local.public_key, Local.delayed_key,
      Remote.public_key,
      spec)

    val hlcTimeoutTxs = for (i <- 0 until unsignedHtlcTimeoutTxs.length) yield {
      val tx = unsignedHtlcTimeoutTxs(i)
      val localSig = Transactions.sign(tx, Local.private_key)
      val remoteSig = Transactions.sign(tx, Remote.private_key)
      val tx1 = Transactions.addSigs(tx, localSig, remoteSig)
      Transaction.correctlySpends(tx1.tx, Seq(commitTx.tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      println(s"# htlc timeout tx $i spends commit tx output ${tx.input.outPoint.index} amount ${tx.input.txOut.amount}")
      println(s"# htlc timeout tx $i local signature $localSig")
      println(s"# htlc timeout tx $i remote signature $remoteSig")
      println(s"# htlc timeout tx $i ${Transaction.write(tx1.tx)}")
      tx1
    }

    val htlcSuccessTxs = for (i <- 0 until unsignedHtlcSuccessTxs.length) yield {
      val tx = unsignedHtlcSuccessTxs(i)
      val localSig = Transactions.sign(tx, Local.private_key)
      val remoteSig = Transactions.sign(tx, Remote.private_key)
      val preimage = paymentPreimages.find(p => Crypto.sha256(p) == tx.paymentHash).get
      val tx1 = Transactions.addSigs(tx, localSig, remoteSig, preimage)
      Transaction.correctlySpends(tx1.tx, Seq(commitTx.tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      println(s"# htlc success tx $i spends commit tx output ${tx.input.outPoint.index} amount ${tx.input.txOut.amount}")
      println(s"# htlc success tx $i local signature $localSig")
      println(s"# htlc success tx $i remote signature $remoteSig")
      println(s"# htlc success tx $i ${Transaction.write(tx1.tx)}")
      tx1
    }
  }

  test("simple commitment tx with no HTLCs") {
    println("name: simple commitment tx with no HTLCs")
    val spec = CommitmentSpec(htlcs = Set.empty, feeRatePerKw = 15000, toLocalMsat = 7000000000L, toRemoteMsat = 3000000000L)

    run(spec, 2)
  }

  test("commitment tx with all 5 htlcs untrimmed (minimum feerate)") {
    println("name: commitment tx with all 5 htlcs untrimmed (minimum feerate")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 0, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    run(spec, 7)
  }

  test("commitment tx with 7 outputs untrimmed (maximum feerate)") {
    println("name: commitment tx with 7 outputs untrimmed (maximum feerate)")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 678, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    run(spec, 7)
  }

  test("commitment tx with 6 outputs untrimmed (minimum feerate)") {
    println("name: commitment tx with 6 outputs untrimmed (minimum feerate)")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 679, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    run(spec, 6)
  }

  test("commitment tx with 6 outputs untrimmed (maximum feerate)") {
    println("name: commitment tx with 6 outputs untrimmed (maximum feerate)")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 2168, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    run(spec, 6)
  }

  test("commitment tx with 5 outputs untrimmed (minimum feerate)") {
    println("name: commitment tx with 5 outputs untrimmed (minimum feerate)")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 2169, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    run(spec, 5)
  }

  test("commitment tx with 5 outputs untrimmed (maximum feerate)") {
    println("name: commitment tx with 5 outputs untrimmed (maximum feerate)")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 2294, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    run(spec, 5)
  }

  test("commitment tx with 4 outputs untrimmed (minimum feerate)") {
    println("name: commitment tx with 4 outputs untrimmed (minimum feerate)")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 2295, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    run(spec, 4)
  }

  test("commitment tx with 4 outputs untrimmed (maximum feerate)") {
    println("name: commitment tx with 4 outputs untrimmed (maximum feerate)")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 3872, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    run(spec, 4)
  }

  test("commitment tx with 3 outputs untrimmed (minimum feerate)") {
    println("name: commitment tx with 3 outputs untrimmed (minimum feerate)")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 3873, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    run(spec, 3)
  }

  test("commitment tx with 3 outputs untrimmed (maximum feerate)") {
    println("name: commitment tx with 3 outputs untrimmed (maximum feerate)")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 5149, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    run(spec, 3)
  }

  test("commitment tx with 2 outputs untrimmed (minimum feerate)") {
    println("name: commitment tx with 2 outputs untrimmed (minimum feerate)")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 5150, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    run(spec, 2)
  }

  test("commitment tx with 2 outputs untrimmed (maximum feerate)") {
    println("name: commitment tx with 2 outputs untrimmed (maximum feerate)")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 9651180, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    run(spec, 2)
  }

  test("commitment tx with 1 output untrimmed (minimum feerate)") {
    println("name: commitment tx with 1 output untrimmed (minimum feerate)")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 9651181, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    run(spec, 1)
  }

  test("commitment tx with fee greater than funder amount") {
    println("name: commitment tx with fee greater than funder amount")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 9651936, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    run(spec, 1)
  }
}
