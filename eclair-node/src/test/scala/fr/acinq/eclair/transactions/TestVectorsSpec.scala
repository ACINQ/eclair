package fr.acinq.eclair.transactions

import fr.acinq.bitcoin._
import fr.acinq.bitcoin.Crypto.{Point, PrivateKey, PublicKey, Scalar}
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.crypto.Generators
import fr.acinq.eclair.wire.UpdateAddHtlc
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
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

  val fundingTx = Transaction.read("0200000001adbb20ea41a8423ea937e76e8151636bf6093b70eaff942930d20576600521fd000000006b48304502210090587b6201e166ad6af0227d3036a9454223d49a1f11839c1a362184340ef0240220577f7cd5cca78719405cbf1de7414ac027f0239ef6e214c90fcaab0454d84b3b012103535b32d5eb0a6ed0982a0479bbadc9868d9836f6ba94dd5a63be16d875069184ffffffff028096980000000000220020c015c4a6be010e21657068fc2e6a9d02b27ebe4d490a25846f7237f104d1a3cd20256d29010000001600143ca33c2e4446f4a305f23c80df8ad1afdcf652f900000000")
  val fundingAmount = fundingTx.txOut(0).amount
  println(s"# funding-tx: ${Transaction.write(fundingTx)}")


  val commitmentInput = Funding.makeFundingInputInfo(fundingTx.hash, 0, fundingAmount, Local.funding_pubkey, Remote.funding_pubkey)

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
  assert(commitmentInput.redeemScript == BinaryData("5221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae"))

  val paymentPreimages = Seq(
    BinaryData("0000000000000000000000000000000000000000000000000000000000000000"),
    BinaryData("0101010101010101010101010101010101010101010101010101010101010101"),
    BinaryData("0202020202020202020202020202020202020202020202020202020202020202"),
    BinaryData("0303030303030303030303030303030303030303030303030303030303030303"),
    BinaryData("0404040404040404040404040404040404040404040404040404040404040404")
  )

  val htlcs = Seq(
    Htlc(IN, UpdateAddHtlc(0, 0, MilliSatoshi(1000000).amount, 500, Crypto.sha256(paymentPreimages(0)), BinaryData("")), None),
    Htlc(IN, UpdateAddHtlc(0, 0, MilliSatoshi(2000000).amount, 501, Crypto.sha256(paymentPreimages(1)), BinaryData("")), None),
    Htlc(OUT, UpdateAddHtlc(0, 0, MilliSatoshi(2000000).amount, 502, Crypto.sha256(paymentPreimages(2)), BinaryData("")), None),
    Htlc(OUT, UpdateAddHtlc(0, 0, MilliSatoshi(3000000).amount, 503, Crypto.sha256(paymentPreimages(3)), BinaryData("")), None),
    Htlc(IN, UpdateAddHtlc(0, 0, MilliSatoshi(4000000).amount, 504, Crypto.sha256(paymentPreimages(4)), BinaryData("")), None)
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

  def run(spec: CommitmentSpec) = {
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

    (commitTx, hlcTimeoutTxs, htlcSuccessTxs)
  }

  test("simple commitment tx with no HTLCs") {
    println("name: simple commitment tx with no HTLCs")
    val spec = CommitmentSpec(htlcs = Set.empty, feeRatePerKw = 15000, toLocalMsat = 7000000000L, toRemoteMsat = 3000000000L)

    val (commitTx, hlcTimeoutTxs, htlcSuccessTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 2)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b8002c0c62d0000000000160014e2f14ead9ca9a2f4c8b8a3f9bd109762ed33a03654a56a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e040047304402205fdea103b8eb092e46362bbc8d80c790dd3756db2474baaf538bf96039a2670c02206dc19fb7e152382887018f5f76047d0b0d75e0876f06663a49c59d8f6d40895401483045022100f732ff890ea9af685f9577bd38f11ceb77f5ead254af663638bbf80bbfa180da022005bb3493d2ba28e6ea43db36d156f5c2befa5de469d118a321a3fd3f3f356dcd01475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }

  test("commitment tx with all 5 htlcs untrimmed (minimum feerate)") {
    println("name: commitment tx with all 5 htlcs untrimmed (minimum feerate")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 0, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, hlcTimeoutTxs, htlcSuccessTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 7)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b8007e80300000000000022002070b024855cc882f19cadb563400cb24cc07987c917daf702bb5b2e6f52e04318d0070000000000002200204117dad487a3c3bc07e34db505d567fcc263b30075f0b4679a1b27c297b4b147d007000000000000220020a736f71c05ae323c2d1821f88e8b3b5563f9048ad6d63b27ce528722eda10f14b80b0000000000002200205e984a3f84e6f0e09d7b3f4685c37f9c78eae32dd1a97e3fdd55d78e414d6c39a00f00000000000022002024bec9455b911553c1200bbf925db2d5fe047130c80da32a7d05abb490996e22c0c62d0000000000160014e2f14ead9ca9a2f4c8b8a3f9bd109762ed33a036e0a06a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0400473044022002ae4ffeca449455d06bd20192d70a107b68faed1a57ba0702ca77755f9da32102202e667e6b079d7d529a3b33af89428e4469d7d58db609f8d678113b1be1f5153401483045022100b8aa92242b6f9f414b3d9c06d2bb03ae2d8b458232dae446ece5c250823e8619022078f1bf9d0fe59434517df3adf46acce524e79db6759e70ecce8cc26612bf5bbd01475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }

  test("commitment tx with 7 outputs untrimmed (maximum feerate)") {
    println("name: commitment tx with 7 outputs untrimmed (maximum feerate)")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 678, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, hlcTimeoutTxs, htlcSuccessTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 7)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b8007e80300000000000022002070b024855cc882f19cadb563400cb24cc07987c917daf702bb5b2e6f52e04318d0070000000000002200204117dad487a3c3bc07e34db505d567fcc263b30075f0b4679a1b27c297b4b147d007000000000000220020a736f71c05ae323c2d1821f88e8b3b5563f9048ad6d63b27ce528722eda10f14b80b0000000000002200205e984a3f84e6f0e09d7b3f4685c37f9c78eae32dd1a97e3fdd55d78e414d6c39a00f00000000000022002024bec9455b911553c1200bbf925db2d5fe047130c80da32a7d05abb490996e22c0c62d0000000000160014e2f14ead9ca9a2f4c8b8a3f9bd109762ed33a036af9c6a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0400483045022100ce89d603b86ab055ad0546fe3e3407566598f6a671368223a0aaa449eb36a4b902204cc65a61c8f799b8e541d3190f3951bd2dbd5a4af36698ff469cc68dd2b696750147304402202e737723eaa3b291bbdf9e66623b2ca9a571034d92a0403c3140ada9d536c25f022012a21463bd9767f3645145fbd2507231908d4a76a5beb00d944da7b954e0ad7701475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }

  test("commitment tx with 6 outputs untrimmed (minimum feerate)") {
    println("name: commitment tx with 6 outputs untrimmed (minimum feerate)")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 679, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, hlcTimeoutTxs, htlcSuccessTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 6)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b8006d0070000000000002200204117dad487a3c3bc07e34db505d567fcc263b30075f0b4679a1b27c297b4b147d007000000000000220020a736f71c05ae323c2d1821f88e8b3b5563f9048ad6d63b27ce528722eda10f14b80b0000000000002200205e984a3f84e6f0e09d7b3f4685c37f9c78eae32dd1a97e3fdd55d78e414d6c39a00f00000000000022002024bec9455b911553c1200bbf925db2d5fe047130c80da32a7d05abb490996e22c0c62d0000000000160014e2f14ead9ca9a2f4c8b8a3f9bd109762ed33a036229d6a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e04004730440220241985f3095d908e8d92d935bc2fdfa2c06983c889c31c09dcedbc4336cc5086022056d0325ab2898ca9cd460c0f730ba027867d7ae58a10ab9bffc500652add187b01483045022100baadda7b5f7dfd01f26546d2cbdd570fc4325026ff4f981150ad7ca94ae3614e02206a0a999ae63d06550894ba4b2347e1b3ee52e8837496c7c92cb51a54950385d801475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }

  test("commitment tx with 6 outputs untrimmed (maximum feerate)") {
    println("name: commitment tx with 6 outputs untrimmed (maximum feerate)")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 2168, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, hlcTimeoutTxs, htlcSuccessTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 6)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b8006d0070000000000002200204117dad487a3c3bc07e34db505d567fcc263b30075f0b4679a1b27c297b4b147d007000000000000220020a736f71c05ae323c2d1821f88e8b3b5563f9048ad6d63b27ce528722eda10f14b80b0000000000002200205e984a3f84e6f0e09d7b3f4685c37f9c78eae32dd1a97e3fdd55d78e414d6c39a00f00000000000022002024bec9455b911553c1200bbf925db2d5fe047130c80da32a7d05abb490996e22c0c62d0000000000160014e2f14ead9ca9a2f4c8b8a3f9bd109762ed33a036eb946a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e040048304502210081e3e359864c94003bacf4c34cfac9a6698188aace28688b2329a2ff2bc6865b02206cdf48dd2ab4cc2e1049094857387646d995f7775cf22b53168769360a45f93f0148304502210088e8509b60534065e2097be1a4fc8590864850424f482306e33e4beef6c284ba022009a13b8984819c812f0dcacbcfc26c266d6c1a4c4ca966295896f75597a1eec701475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }

  test("commitment tx with 5 outputs untrimmed (minimum feerate)") {
    println("name: commitment tx with 5 outputs untrimmed (minimum feerate)")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 2169, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, hlcTimeoutTxs, htlcSuccessTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 5)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b8005d007000000000000220020a736f71c05ae323c2d1821f88e8b3b5563f9048ad6d63b27ce528722eda10f14b80b0000000000002200205e984a3f84e6f0e09d7b3f4685c37f9c78eae32dd1a97e3fdd55d78e414d6c39a00f00000000000022002024bec9455b911553c1200bbf925db2d5fe047130c80da32a7d05abb490996e22c0c62d0000000000160014e2f14ead9ca9a2f4c8b8a3f9bd109762ed33a0365f966a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0400483045022100970682c827d19dc2c0b3a0dc5b5423ed9bbfdf70536177401da31d9edf050c2602200c6754465782f62ee48e95387c9a86b82ae4bd04813ddf0485a8646ab670f16c01483045022100c5b421beaa860fb40fa14127ce51b01f63f0359f3c6fb83a1fa9cfd78ce876c302204644c1fef1299ac051559c542aed6903bcd8c58c0587296e2d57025e4e354ba901475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }

  test("commitment tx with 5 outputs untrimmed (maximum feerate)") {
    println("name: commitment tx with 5 outputs untrimmed (maximum feerate)")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 2294, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, hlcTimeoutTxs, htlcSuccessTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 5)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b8005d007000000000000220020a736f71c05ae323c2d1821f88e8b3b5563f9048ad6d63b27ce528722eda10f14b80b0000000000002200205e984a3f84e6f0e09d7b3f4685c37f9c78eae32dd1a97e3fdd55d78e414d6c39a00f00000000000022002024bec9455b911553c1200bbf925db2d5fe047130c80da32a7d05abb490996e22c0c62d0000000000160014e2f14ead9ca9a2f4c8b8a3f9bd109762ed33a036c4956a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e04004730440220412f4f5e7738e0822e87093570297b4be7f486f0dd5e204ba59d3fe9732686f4022048734cbdaf8a8a29a0dbcbb11a47a2b913a15d83eac3eca2fe82a8a62fd0958f0147304402207e387e7e3646ba0cd45db3e35a768d294cbb96f59225f3151cf12ae4856788c602203c3cb2dbc2d18425b5c78969a8a3e6ff179a0af6e26f7cd260981845184b0ffb01475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }

  test("commitment tx with 4 outputs untrimmed (minimum feerate)") {
    println("name: commitment tx with 4 outputs untrimmed (minimum feerate)")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 2295, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, hlcTimeoutTxs, htlcSuccessTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 4)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b8004b80b0000000000002200205e984a3f84e6f0e09d7b3f4685c37f9c78eae32dd1a97e3fdd55d78e414d6c39a00f00000000000022002024bec9455b911553c1200bbf925db2d5fe047130c80da32a7d05abb490996e22c0c62d0000000000160014e2f14ead9ca9a2f4c8b8a3f9bd109762ed33a0364d976a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0400483045022100a1a83337bc08abc2e1e0ec076fd8955cd52654c4fecfda5085f4c1a80a795c0502207803dc3e7a16714386ccca408716def503d4d06f04ce2db317035c1f561b949201483045022100e858d59c407479f1fbe64849ef80f5535848c9b509329fc3895357641720e67c02207c6018822d79c59a46416a790fd841deb78729a9fe129ef9b954480601a0ae3201475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }

  test("commitment tx with 4 outputs untrimmed (maximum feerate)") {
    println("name: commitment tx with 4 outputs untrimmed (maximum feerate)")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 3872, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, hlcTimeoutTxs, htlcSuccessTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 4)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b8004b80b0000000000002200205e984a3f84e6f0e09d7b3f4685c37f9c78eae32dd1a97e3fdd55d78e414d6c39a00f00000000000022002024bec9455b911553c1200bbf925db2d5fe047130c80da32a7d05abb490996e22c0c62d0000000000160014e2f14ead9ca9a2f4c8b8a3f9bd109762ed33a036b9906a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0400483045022100d102f5830a8c342028e6814b182945e8f5a089b2ee916c292a1b60b9dc65081902203c28957d11f8ebacd6ec4c7b7fa95c6f1dbe80a38d066625e1f1e906aca0ada501483045022100cfa71cb1cc0523056bc195ab9d41faf9fdbde3ea793fcf686b10da8a63893b490220102a99f828ae77aa5ddb1aeffb33196d13e552167ddd090b37667783f9d6d9da01475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }

  test("commitment tx with 3 outputs untrimmed (minimum feerate)") {
    println("name: commitment tx with 3 outputs untrimmed (minimum feerate)")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 3873, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, hlcTimeoutTxs, htlcSuccessTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 3)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b8003a00f00000000000022002024bec9455b911553c1200bbf925db2d5fe047130c80da32a7d05abb490996e22c0c62d0000000000160014e2f14ead9ca9a2f4c8b8a3f9bd109762ed33a03652936a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e04004730440220670c9bbffc08bf3b7afaa11b54b6da3a9ec2c104ad68c63a060200caeeb056ed02206c4f2e16735ad2517aa1051797cd90e1b684f1d7472948033308e3754dace6be0147304402204aea8340a74b1ad2ccc149db8cae4ea8ca87bee3874291f238a8b3575fb4d94702203885c9b27fa44819c02767a50536e7dde2badcbff58492abd71eeb158668128a01475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }

  test("commitment tx with 3 outputs untrimmed (maximum feerate)") {
    println("name: commitment tx with 3 outputs untrimmed (maximum feerate)")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 5149, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, hlcTimeoutTxs, htlcSuccessTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 3)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b8003a00f00000000000022002024bec9455b911553c1200bbf925db2d5fe047130c80da32a7d05abb490996e22c0c62d0000000000160014e2f14ead9ca9a2f4c8b8a3f9bd109762ed33a036db8e6a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e040047304402203ced9eb9d0ef8aed60615853a4c29c526b4f74515a763ad76d94d866192528ca0220301023778619919648a009809dfb45adb76f55cd83350c5461a0113a48eba85d01483045022100a1886113f74051fff92e6c549a5d39ace6b9ee51c6a6b86518ad4bb5d1158720022059421e01a12838946e03da07e29307c72bf8b486c9d80359e1235ec582a5f47901475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }

  test("commitment tx with 2 outputs untrimmed (minimum feerate)") {
    println("name: commitment tx with 2 outputs untrimmed (minimum feerate)")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 5150, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, hlcTimeoutTxs, htlcSuccessTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 2)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b8002c0c62d0000000000160014e2f14ead9ca9a2f4c8b8a3f9bd109762ed33a03650926a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e040047304402202f5f5d8b3abbbfab4781d7b18ea0bbe9f3fd0e4fb538afdfab61e269bc1921760220357518aa91c1cda40f24ec64f32e685427f886d40ab22b9c4f1df9b87746f10301483045022100bd729fa3790528e36f6518f5efdcedc0b0e25650e156ca5684960de37fd1cf9c022004069b1771651d8ea77ac18d1d86219a1b1bd46e635db85d3b73a8293e42c19101475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }

  test("commitment tx with 2 outputs untrimmed (maximum feerate)") {
    println("name: commitment tx with 2 outputs untrimmed (maximum feerate)")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 9651180, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, hlcTimeoutTxs, htlcSuccessTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 2)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b800222020000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80ec0c62d0000000000160014e2f14ead9ca9a2f4c8b8a3f9bd109762ed33a0360400483045022100bab11758e8182f7957047c19033df1b8294bc623a474efe4e1eb6519e49c7147022018af25c278ed3e9809dbf7f0b132ffccce6ff7b59a4a67f507a3648c46e5b3e501473044022017f82cdb8e5b1c443afe9191efdde7aa742e8f03c265bdab7df18a74b30711a7022009a5b4c676778c6bda8d87db551ae5d89ac792aff62011734afa1caf4bc857dd01475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }

  test("commitment tx with 1 output untrimmed (minimum feerate)") {
    println("name: commitment tx with 1 output untrimmed (minimum feerate)")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 9651181, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, hlcTimeoutTxs, htlcSuccessTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 1)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b8001c0c62d0000000000160014e2f14ead9ca9a2f4c8b8a3f9bd109762ed33a036040047304402204788ebe839058b6d917999d82ffa7ad235710d49b8f99aea7c8d95fe60ecc26502200c6ad2bcec214d83e66570bf22fa383f8e71b8991cd63feea018d2cd610b86f6014830450221008dc967ec76f7a4837f00bdab1dc3e93c62cd28ec9931649dbb5f0b9105615bf702203fa4646c7f85b19d0bd4691a7ab89ee7243aa6f14a3a3744bed6fd6e0b6b17b901475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }

  test("commitment tx with fee greater than funder amount") {
    println("name: commitment tx with fee greater than funder amount")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 9651936, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, hlcTimeoutTxs, htlcSuccessTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 1)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b8001c0c62d0000000000160014e2f14ead9ca9a2f4c8b8a3f9bd109762ed33a036040047304402204788ebe839058b6d917999d82ffa7ad235710d49b8f99aea7c8d95fe60ecc26502200c6ad2bcec214d83e66570bf22fa383f8e71b8991cd63feea018d2cd610b86f6014830450221008dc967ec76f7a4837f00bdab1dc3e93c62cd28ec9931649dbb5f0b9105615bf702203fa4646c7f85b19d0bd4691a7ab89ee7243aa6f14a3a3744bed6fd6e0b6b17b901475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }
}
