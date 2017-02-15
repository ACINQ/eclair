package fr.acinq.eclair.transactions

import fr.acinq.bitcoin._
import fr.acinq.bitcoin.Crypto.{Point, PrivateKey, PublicKey, Scalar}
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.crypto.Generators
import fr.acinq.eclair.transactions.Transactions.{HtlcSuccessTx, HtlcTimeoutTx, TransactionWithInputInfo}
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
    val payment_basepoint_secret = Scalar(BinaryData("4444444444444444444444444444444444444444444444444444444444444444"))
    val payment_basepoint = payment_basepoint_secret.toPoint
    val revocation_basepoint_secret = Scalar(BinaryData("2222222222222222222222222222222222222222222222222222222222222222"))
    val revocation_basepoint = revocation_basepoint_secret.toPoint
    val funding_privkey = PrivateKey(BinaryData("1552dfba4f6cf29a62a0af13c8d6981d36d0ef8d61ba10fb0fe90da7634d7e1301"))
    val funding_pubkey = funding_privkey.publicKey
    val private_key = Generators.derivePrivKey(payment_basepoint_secret, Local.per_commitment_point)
    val public_key = private_key.publicKey
    val per_commitment_point = Point(BinaryData("022c76692fd70814a8d1ed9dedc833318afaaed8188db4d14727e2e99bc619d325"))
  }

  val coinbaseTx = Transaction.read("01000000010000000000000000000000000000000000000000000000000000000000000000ffffffff03510101ffffffff0100f2052a010000001976a9143ca33c2e4446f4a305f23c80df8ad1afdcf652f988ac00000000")

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
    println(s"local_feerate_per_kw: ${spec.feeRatePerKw}")

    val commitTx = {
      val tx = Transactions.makeCommitTx(
        commitmentInput,
        Local.commitTxNumber, Local.payment_basepoint, Remote.payment_basepoint,
        true, Local.dustLimit,
        Local.public_key, Local.revocation_key, Local.toSelfDelay, Local.delayed_key,
        Remote.public_key,
        spec)

      val local_sig = Transactions.sign(tx, Local.funding_privkey)
      val remote_sig = Transactions.sign(tx, Remote.funding_privkey)

      Transactions.addSigs(tx, Local.funding_pubkey, Remote.funding_pubkey, local_sig, remote_sig)
    }

    val baseFee = Transactions.commitTxFee(Local.dustLimit, spec)
    println(s"# base commitment transaction fee = ${baseFee.toLong}")
    val actualFee = fundingAmount - commitTx.tx.txOut.map(_.amount).sum
    println(s"# actual commitment transaction fee = ${actualFee.toLong}")
    commitTx.tx.txOut.map(txOut => {
      txOut.publicKeyScript.length match {
        case 22 => println(s"# to-remote amount ${txOut.amount.toLong} P2WPKH(${Remote.public_key})")
        case 34 =>
          val index = htlcScripts.indexWhere(s => Script.write(Script.pay2wsh(s)) == txOut.publicKeyScript)
          if (index == -1) println(s"# to-local amount ${txOut.amount.toLong} wscript ${Script.write(Scripts.toLocalDelayed(Local.revocation_key, Local.toSelfDelay, Local.delayed_key))}")
          else println(s"# HTLC ${if (htlcs(index).direction == OUT) "offered" else "received"} amount ${txOut.amount.toLong} wscript ${Script.write(htlcScripts(index))}")
      }
    })

    {
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
      println(s"remote_signature: $remote_sig")
    }

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

    println(s"num_htlcs: ${(unsignedHtlcTimeoutTxs ++ unsignedHtlcSuccessTxs).length}")
    val htlcTxs: Seq[TransactionWithInputInfo] = (unsignedHtlcTimeoutTxs ++ unsignedHtlcSuccessTxs).sortBy(_.input.outPoint.index)


    htlcTxs.map(_ match {
      case tx: HtlcSuccessTx =>
        val remoteSig = Transactions.sign(tx, Remote.private_key)
        val htlcIndex = htlcScripts.indexOf(Script.parse(tx.input.redeemScript))
        println(s"# signature for output ${tx.input.outPoint.index} (htlc $htlcIndex)")
        println(s"remote_htlc_signature: $remoteSig")
      case tx: HtlcTimeoutTx =>
        val remoteSig = Transactions.sign(tx, Remote.private_key)
        val htlcIndex = htlcScripts.indexOf(Script.parse(tx.input.redeemScript))
        println(s"# signature for output ${tx.input.outPoint.index} (htlc $htlcIndex)")
        println(s"remote_htlc_signature: $remoteSig")
    })

    val signedTxs = htlcTxs.map(_ match {
      case tx: HtlcSuccessTx =>
        //val tx = tx0.copy(tx = tx0.tx.copy(txOut = tx0.tx.txOut(0).copy(amount = Satoshi(545)) :: Nil))
        val localSig = Transactions.sign(tx, Local.private_key)
        val remoteSig = Transactions.sign(tx, Remote.private_key)
        val preimage = paymentPreimages.find(p => Crypto.sha256(p) == tx.paymentHash).get
        val tx1 = Transactions.addSigs(tx, localSig, remoteSig, preimage)
        Transaction.correctlySpends(tx1.tx, Seq(commitTx.tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        val htlcIndex = htlcScripts.indexOf(Script.parse(tx.input.redeemScript))
        println(s"# local signature $localSig")
        println(s"output htlc_success_tx ${htlcIndex}: ${Transaction.write(tx1.tx)}")
        tx1
      case tx: HtlcTimeoutTx =>
        val localSig = Transactions.sign(tx, Local.private_key)
        val remoteSig = Transactions.sign(tx, Remote.private_key)
        val tx1 = Transactions.addSigs(tx, localSig, remoteSig)
        Transaction.correctlySpends(tx1.tx, Seq(commitTx.tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        println(s"# local signature $localSig")
        val htlcIndex = htlcScripts.indexOf(Script.parse(tx.input.redeemScript))
        println(s"output htlc_timeout_tx ${htlcIndex}: ${Transaction.write(tx1.tx)}")
        tx1
    })

    println
    (commitTx, signedTxs)
  }

  test("simple commitment tx with no HTLCs") {
    println("name: simple commitment tx with no HTLCs")
    val spec = CommitmentSpec(htlcs = Set.empty, feeRatePerKw = 15000, toLocalMsat = 7000000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 2)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b8002c0c62d0000000000160014ccf1af2f2aabee14bb40fa3851ab2301de84311054a56a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0400473044022051b75c73198c6deee1a875871c3961832909acd297c6b908d59e3319e5185a46022055c419379c5051a78d00dbbce11b5b664a0c22815fbcc6fcef6b1937c383693901483045022100f51d2e566a70ba740fc5d8c0f07b9b93d2ed741c3c0860c613173de7d39e7968022041376d520e9c0e1ad52248ddf4b22e12be8763007df977253ef45a4ca3bdb7c001475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }

  test("commitment tx with all 5 htlcs untrimmed (minimum feerate)") {
    println("name: commitment tx with all 5 htlcs untrimmed (minimum feerate")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 0, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 7)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b8007e8030000000000002200207eaf624c3ab8f5cad0589f46db3fed940bf79a88fb5ab7fa3a6e1d071b5845bfd00700000000000022002083975515b28ad8c03b0915cae90787ff5f1a0ad8f313806a71ef6152fd5ecc78d007000000000000220020edcdff3e4bb6b538c0ee9639f56dfc4f222e5077bface165abc48764160da0c2b80b000000000000220020311b8632d824446eb4104b5eac4c95ea8efc3f84f7863b772586c57b62450312a00f00000000000022002022ca70b9138696c383f9da5e3250280d26b993e13eb55f19cd841d7dc966d3c8c0c62d0000000000160014ccf1af2f2aabee14bb40fa3851ab2301de843110e0a06a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0400483045022100ce8a5a47e1377b7878c65209affe5645e400f0b834ddcbd2248a961c034686590220349d27b5a3bd2dac4117bdcf6a94449b0e4a5b179aef1d6b23f526081cdfe8ab014830450221008f60b91c64ffaeb498bca51827c378a5a0c3488888677cd8483b42bae7222269022028e7ff07936b62327bd43f5c27c2cbc28351242bcb3b4a9f77e0fc3ee8558c9301475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }

  test("commitment tx with 7 outputs untrimmed (maximum feerate)") {
    println("name: commitment tx with 7 outputs untrimmed (maximum feerate)")
    val feeRatePerKw = 454999 / Transactions.htlcSuccessWeight
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = feeRatePerKw, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 7)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b8007e8030000000000002200207eaf624c3ab8f5cad0589f46db3fed940bf79a88fb5ab7fa3a6e1d071b5845bfd00700000000000022002083975515b28ad8c03b0915cae90787ff5f1a0ad8f313806a71ef6152fd5ecc78d007000000000000220020edcdff3e4bb6b538c0ee9639f56dfc4f222e5077bface165abc48764160da0c2b80b000000000000220020311b8632d824446eb4104b5eac4c95ea8efc3f84f7863b772586c57b62450312a00f00000000000022002022ca70b9138696c383f9da5e3250280d26b993e13eb55f19cd841d7dc966d3c8c0c62d0000000000160014ccf1af2f2aabee14bb40fa3851ab2301de843110b29c6a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e04004830450221009fb6cb38db01817f77a5f973729948b8af0b3a6dad3429e2bd7a88b7b3d1de8b022025e1cd9f23dfe3f87e39e8c14fd054771758287e35aa1b4499de99427844abf201483045022100f46729e7a3126cf03d94691f814405b26cf896ecd6617d565aba6915c68de3a202204ca52c50b0c6fe424671b9986907f6180d8c65b289347fa02aac3c69065c6b9701475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }

  test("commitment tx with 6 outputs untrimmed (minimum feerate)") {
    println("name: commitment tx with 6 outputs untrimmed (minimum feerate)")
    val feeRatePerKw = 454999 / Transactions.htlcSuccessWeight
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = feeRatePerKw + 1, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 6)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b8006d00700000000000022002083975515b28ad8c03b0915cae90787ff5f1a0ad8f313806a71ef6152fd5ecc78d007000000000000220020edcdff3e4bb6b538c0ee9639f56dfc4f222e5077bface165abc48764160da0c2b80b000000000000220020311b8632d824446eb4104b5eac4c95ea8efc3f84f7863b772586c57b62450312a00f00000000000022002022ca70b9138696c383f9da5e3250280d26b993e13eb55f19cd841d7dc966d3c8c0c62d0000000000160014ccf1af2f2aabee14bb40fa3851ab2301de843110259d6a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e04004830450221008acdee277c284cacc3c0b64b0724d459bcae09e3390cd36767f6a65bb265ccfe0220608b5459263c4a80fa30ca3901c08642df793d3048bf985df7da66d6dbb5d4b901473044022025a153b4c6310fa5f1825a077625054f993e07540149ef76f39d41fdbfa3432402202ff44666e56a9cfc3dbca68d26d2174f09a7aad9f2ca0741f3e7373686ff7c9d01475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }

  test("commitment tx with 6 outputs untrimmed (maximum feerate)") {
    println("name: commitment tx with 6 outputs untrimmed (maximum feerate)")
    val feeRatePerKw = 1454999 / Transactions.htlcSuccessWeight
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = feeRatePerKw, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 6)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b8006d00700000000000022002083975515b28ad8c03b0915cae90787ff5f1a0ad8f313806a71ef6152fd5ecc78d007000000000000220020edcdff3e4bb6b538c0ee9639f56dfc4f222e5077bface165abc48764160da0c2b80b000000000000220020311b8632d824446eb4104b5eac4c95ea8efc3f84f7863b772586c57b62450312a00f00000000000022002022ca70b9138696c383f9da5e3250280d26b993e13eb55f19cd841d7dc966d3c8c0c62d0000000000160014ccf1af2f2aabee14bb40fa3851ab2301de843110f5946a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0400483045022100b42a3229202c8c5ddbff95efa6aa2d48c39b57d437ad4a8b2a917d11a3ca55ff02205bb9c65d06656222ced3bfd804145f658d1fa11804b20ef44962a9ea547bd6b701483045022100a9976e89763982487b7ff07a26347d398b9f19c0fb01046c8a787d7cd6068f440220224138e065ed31f248fd2756d3e209c0cab69ea5e1ede66d019e18072267284f01475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }

  test("commitment tx with 5 outputs untrimmed (minimum feerate)") {
    println("name: commitment tx with 5 outputs untrimmed (minimum feerate)")
    val feeRatePerKw = 1454999 / Transactions.htlcSuccessWeight
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = feeRatePerKw + 1, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 5)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b8005d00700000000000022002083975515b28ad8c03b0915cae90787ff5f1a0ad8f313806a71ef6152fd5ecc78b80b000000000000220020311b8632d824446eb4104b5eac4c95ea8efc3f84f7863b772586c57b62450312a00f00000000000022002022ca70b9138696c383f9da5e3250280d26b993e13eb55f19cd841d7dc966d3c8c0c62d0000000000160014ccf1af2f2aabee14bb40fa3851ab2301de84311068966a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0400483045022100bfcdea8720cb25031a4ffa9f44195b2b66922183af9fcf040281b60ebcaa1dac0220636987b0fbacd90ea9ba6262d675f97d77aeaf8808ed0aaeecca20991b19c7d501483045022100e7b45245c3b6079d0606000d1e340f6957621ab09fa8feb28ec69272851ed9650220299ecd0833d086d97a094b0e1b82be2b878fbd03b15616ee06e40ca8b909d84c01475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }

  test("commitment tx with 5 outputs untrimmed (maximum feerate)") {
    println("name: commitment tx with 5 outputs untrimmed (maximum feerate)")
    val feeRatePerKw = 1454999 / Transactions.htlcTimeoutWeight
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = feeRatePerKw, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 5)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b8005d00700000000000022002083975515b28ad8c03b0915cae90787ff5f1a0ad8f313806a71ef6152fd5ecc78b80b000000000000220020311b8632d824446eb4104b5eac4c95ea8efc3f84f7863b772586c57b62450312a00f00000000000022002022ca70b9138696c383f9da5e3250280d26b993e13eb55f19cd841d7dc966d3c8c0c62d0000000000160014ccf1af2f2aabee14bb40fa3851ab2301de843110c8956a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0400483045022100b9174ba09413297731a39e245d1b7fda4cb363c333b58dd6f7f780b9ec2497f102205da2fca746fa0b4516f5e0d4d9cb8ecdf5cf241b44dd33c4b24e8313e844df72014730440220204316f3553a265922a99c207addeae456349e0aca229d809a526193d5ebd03002206bb618812f43efff52bbf48ca4cbb92529ef0bd6dcfaae4235ff8aebde1b121f01475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }

  test("commitment tx with 4 outputs untrimmed (minimum feerate)") {
    println("name: commitment tx with 4 outputs untrimmed (minimum feerate)")
    val feeRatePerKw = 1454999 / Transactions.htlcTimeoutWeight
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = feeRatePerKw + 1, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 4)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b8004b80b000000000000220020311b8632d824446eb4104b5eac4c95ea8efc3f84f7863b772586c57b62450312a00f00000000000022002022ca70b9138696c383f9da5e3250280d26b993e13eb55f19cd841d7dc966d3c8c0c62d0000000000160014ccf1af2f2aabee14bb40fa3851ab2301de84311051976a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0400483045022100e0b270640f8fd88e51f75c5142443b943e6a349671fa7eae0325bdaff86a87c40220009796bfc452cb6c49a3286defea2ac8efaf4721bcc643eb92a7e93bb9c5b4d30148304502210085ef217e4ee408810c1be4994bb671b2c4868c37169a3d853f8f122bdfb87be9022003188677686ebf025849b67ad49babff11325b5255fe9b608fbfac16722e47a401475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }

  test("commitment tx with 4 outputs untrimmed (maximum feerate)") {
    println("name: commitment tx with 4 outputs untrimmed (maximum feerate)")
    val feeRatePerKw = 2454999 / Transactions.htlcTimeoutWeight
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = feeRatePerKw, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 4)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b8004b80b000000000000220020311b8632d824446eb4104b5eac4c95ea8efc3f84f7863b772586c57b62450312a00f00000000000022002022ca70b9138696c383f9da5e3250280d26b993e13eb55f19cd841d7dc966d3c8c0c62d0000000000160014ccf1af2f2aabee14bb40fa3851ab2301de843110c0906a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e040048304502210080b66478598786deb4bdb9d49574012b0a8c988d5d784f14a42e9329569ae52802207276e265d0c3a86d97cfe97d6491a51c3c2013ff762fb1caced12d5b31f0029a01483045022100efb46b8a0ab766a7c81de0deb00985eed8a9928d055485ef12bd554cf8afa84e02207dfcff213f6e6c5ef4c369a0aaafadfcc9fad3a21a7888919cfeee114755d03d01475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }

  test("commitment tx with 3 outputs untrimmed (minimum feerate)") {
    println("name: commitment tx with 3 outputs untrimmed (minimum feerate)")
    val feeRatePerKw = 2454999 / Transactions.htlcTimeoutWeight
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = feeRatePerKw + 1, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 3)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b8003a00f00000000000022002022ca70b9138696c383f9da5e3250280d26b993e13eb55f19cd841d7dc966d3c8c0c62d0000000000160014ccf1af2f2aabee14bb40fa3851ab2301de84311058936a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e040047304402201923a8d7909f2c8708863ba70b2ba5c20939abffd603cf937c54129e5c6b28b2022018ca56507178141663fe1fac2a55d9e9b4278b8324a5f880464d3e6edbc44a1b01473044022045b6ce3604bbd13d2bf83d003f721dd726bfb8357e5a68b6f8a49db5a86faf48022070b95df3fadd1244c53cca7a62d1e128085d5138bd9b70be61662c09d4a6085301475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }

  test("commitment tx with 3 outputs untrimmed (maximum feerate)") {
    println("name: commitment tx with 3 outputs untrimmed (maximum feerate)")
    val feeRatePerKw = 3454999 / Transactions.htlcSuccessWeight
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = feeRatePerKw, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 3)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b8003a00f00000000000022002022ca70b9138696c383f9da5e3250280d26b993e13eb55f19cd841d7dc966d3c8c0c62d0000000000160014ccf1af2f2aabee14bb40fa3851ab2301de843110e98e6a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0400483045022100b413ebb50e942ae53fea93578a0122603b79af5b1daac71a35e52cc176e8247d022066eca652a57ec48eab57ebfe719dcf17aad8e0e746f4b0fb10bad866693e201401483045022100f7164661832d55b28789b7b63690bee01b43bde46fd713ca7e8747258b00d7410220602329a65ab366e99ec2c68b91acf7162fff7d61298e78472d1544d7dd2204a801475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }

  test("commitment tx with 2 outputs untrimmed (minimum feerate)") {
    println("name: commitment tx with 2 outputs untrimmed (minimum feerate)")
    val feeRatePerKw = 3454999 / Transactions.htlcSuccessWeight
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = feeRatePerKw + 1, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 2)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b8002c0c62d0000000000160014ccf1af2f2aabee14bb40fa3851ab2301de8431105b926a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0400483045022100fc35aae81065b76858d692233d20fd3b249fefbacc14eb4caf001a0347cc00670220613311610016742e609e19d1bc1e6b5a1f5ff9dc080f443633afdbc953c119c001483045022100c386d933436598ea7c33491ef464300a214cff27a0f7312d99ab3768326d7b8d02204df07a4f71c5dbd697032c50b9819f2519d604219a097dd0fab61377ece322a201475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }

  test("commitment tx with 2 outputs untrimmed (maximum feerate)") {
    println("name: commitment tx with 2 outputs untrimmed (maximum feerate)")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 9651180, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 2)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b800222020000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80ec0c62d0000000000160014ccf1af2f2aabee14bb40fa3851ab2301de84311004004730440220514f977bf7edc442de8ce43ace9686e5ebdc0f893033f13e40fb46c8b8c6e1f90220188006227d175f5c35da0b092c57bea82537aed89f7778204dc5bacf4f29f2b901473044022037f83ff00c8e5fb18ae1f918ffc24e54581775a20ff1ae719297ef066c71caa9022039c529cccd89ff6c5ed1db799614533844bd6d101da503761c45c713996e3bbd01475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }

  test("commitment tx with 1 output untrimmed (minimum feerate)") {
    println("name: commitment tx with 1 output untrimmed (minimum feerate)")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 9651181, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 1)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b8001c0c62d0000000000160014ccf1af2f2aabee14bb40fa3851ab2301de8431100400473044022031a82b51bd014915fe68928d1abf4b9885353fb896cac10c3fdd88d7f9c7f2e00220716bda819641d2c63e65d3549b6120112e1aeaf1742eed94a471488e79e206b101473044022064901950be922e62cbe3f2ab93de2b99f37cff9fc473e73e394b27f88ef0731d02206d1dfa227527b4df44a07599289e207d6fd9cca60c0365682dcd3deaf739567e01475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }

  test("commitment tx with fee greater than funder amount") {
    println("name: commitment tx with fee greater than funder amount")
    val spec = CommitmentSpec(htlcs = htlcs.toSet, feeRatePerKw = 9651936, toLocalMsat = 6988000000L, toRemoteMsat = 3000000000L)

    val (commitTx, htlcTxs) = run(spec)
    assert(commitTx.tx.txOut.length == 1)
    assert(Transaction.write(commitTx.tx) == BinaryData("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b8001c0c62d0000000000160014ccf1af2f2aabee14bb40fa3851ab2301de8431100400473044022031a82b51bd014915fe68928d1abf4b9885353fb896cac10c3fdd88d7f9c7f2e00220716bda819641d2c63e65d3549b6120112e1aeaf1742eed94a471488e79e206b101473044022064901950be922e62cbe3f2ab93de2b99f37cff9fc473e73e394b27f88ef0731d02206d1dfa227527b4df44a07599289e207d6fd9cca60c0365682dcd3deaf739567e01475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"))
  }
}
