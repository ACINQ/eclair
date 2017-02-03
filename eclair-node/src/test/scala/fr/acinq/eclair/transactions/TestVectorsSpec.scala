package fr.acinq.eclair.transactions

import fr.acinq.bitcoin._
import fr.acinq.bitcoin.Crypto.{Point, PrivateKey, PublicKey}
import fr.acinq.eclair.channel.Helpers.Funding
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
    val payment_basepoint = Point(BinaryData("034f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa"))
    val funding_privkey = PrivateKey(BinaryData("1552dfba4f6cf29a62a0af13c8d6981d36d0ef8d61ba10fb0fe90da7634d7e1301"))
    val funding_pubkey = funding_privkey.publicKey
    val private_key = PrivateKey(BinaryData("bb13b121cdc357cd2e608b0aea294afca36e2b34cf958e2e6451a2f27469449101"))
    val public_key = private_key.publicKey
    val delayed_key = PublicKey(BinaryData("03fd5960528dc152014952efdb702a88f71e3c1653b2314431701ec77e57fde83c"))
    val revocation_key = PublicKey(BinaryData("0212a140cd0c6539d07cd08dfe09984dec3251ea808b892efeac3ede9402bf2b19"))
    val feerate_per_kw = 15000
  }

  object Remote {
    val commitTxNumber = 42
    val toSelfDelay = 144
    val dustLimit = Satoshi(546)
    val payment_basepoint = Point(BinaryData("032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991"))
    val funding_privkey = PrivateKey(BinaryData("31ff4956bbdd3222d44cc5e8a1261dab1e07957bdac5ae88fe3261ef321f374901"))
    val funding_pubkey = funding_privkey.publicKey
    val private_key = PrivateKey(BinaryData("839ad0480cde69fc721fb8e919dcf20bc4f2b3374c7b27ff37f200ddfa7b0edb01"))
    val public_key = private_key.publicKey
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

  test("simple commitment tx with no HTLCs") {
    println("name: simple commitment tx with no HTLCs")

    val to_local_msat = 7000000000L millisatoshi
    val to_remote_msat = 3000000000L millisatoshi
    val feerate_per_kw = 15000

    println(s"to_local_msat: $to_local_msat")
    println(s"to_remote_msat: $to_remote_msat")
    println(s"feerate_per_kw: $feerate_per_kw")

    val spec = CommitmentSpec(
      htlcs = Set.empty,
      feeRatePerKw = feerate_per_kw,
      toLocalMsat = to_local_msat.amount,
      toRemoteMsat = to_remote_msat.amount)

    val commitTx = Transactions.makeCommitTx(
      commitmentInput,
      Local.commitTxNumber, Local.payment_basepoint, Remote.payment_basepoint,
      true, Local.dustLimit,
      Local.public_key, Local.revocation_key, Local.toSelfDelay, Local.delayed_key,
      Remote.public_key,
      spec)

    val fee = fundingAmount - commitTx.tx.txOut.map(_.amount).sum
    println(s"# base commitment transaction fee = $fee")
    commitTx.tx.txOut.map(txOut => {
      txOut.publicKeyScript.length match {
        case 22 => println(s"# to-remote amount ${txOut.amount}")
        case 34 => println(s"# to-local amount ${txOut.amount}")
      }
    })

    assert(Transactions.getCommitTxNumber(commitTx.tx, Local.payment_basepoint, Remote.payment_basepoint) === Local.commitTxNumber)

    val local_sig = Transactions.sign(commitTx, Local.funding_privkey)
    println(s"# local_signature = $local_sig")
    val remote_sig = Transactions.sign(commitTx, Remote.funding_privkey)
    println(s"# remote_signature = $remote_sig")

    val signedTx = Transactions.addSigs(commitTx, Local.funding_pubkey, Remote.funding_pubkey, local_sig, remote_sig)
    Transaction.correctlySpends(signedTx.tx, Seq(fundingTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    println(s"output commit_tx: ${Transaction.write(signedTx.tx)}")
    println()
  }

  test("commitment tx with all 5 htlcs untrimmed (minimum feerate") {
    println("name: commitment tx with all 5 htlcs untrimmed (minimum feerate")
    val to_local_msat = 6988000000L millisatoshi
    val to_remote_msat = 3000000000L millisatoshi
    val feerate_per_kw = 0

    println(s"to_local_msat: $to_local_msat")
    println(s"to_remote_msat: $to_remote_msat")
    println(s"feerate_per_kw: $feerate_per_kw")

    val spec = CommitmentSpec(
      htlcs = htlcs.toSet,
      feeRatePerKw = feerate_per_kw,
      toLocalMsat = to_local_msat.amount,
      toRemoteMsat = to_remote_msat.amount)

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

    val fee = fundingAmount - commitTx.tx.txOut.map(_.amount).sum
    println(s"# base commitment transaction fee = $fee")
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
}
