package fr.acinq.eclair

import fr.acinq.bitcoin.Crypto._
import fr.acinq.bitcoin._
import fr.acinq.eclair.channel.Scripts._
import lightning._
import lightning.locktime.Locktime.Blocks
import lightning.open_channel.anchor_offer
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ProtocolSpec extends FlatSpec {
  val previousTx = Transaction.read("0100000001bb4f5a244b29dc733c56f80c0fed7dd395367d9d3b416c01767c5123ef124f82000000006b4830450221009e6ed264343e43dfee2373b925915f7a4468e0bc68216606e40064561e6c097a022030f2a50546a908579d0fab539d5726a1f83cfd48d29b89ab078d649a8e2131a0012103c80b6c289bf0421d010485cec5f02636d18fb4ed0f33bfa6412e20918ebd7a34ffffffff0200093d00000000001976a9145dbf52b8d7af4fb5f9b75b808f0a8284493531b388acf0b0b805000000001976a914807c74c89592e8a260f04b5a3bc63e7bef8c282588ac00000000")
  // key that can spend this tx
  val key = SignData(previousTx.txOut(0).publicKeyScript, Base58Check.decode("cV7LGVeY2VPuCyCSarqEqFCUNig2NzwiAEBTTA89vNRQ4Vqjfurs")._2)

  object Alice {
    val (_, commitKey) = Base58Check.decode("cVuzKWCszfvjkoJyUasvsrRdECriz8hSd1BDinRNzytwnXmX7m1g")
    val (_, finalKey) = Base58Check.decode("cRUfvpbRtMSqCFD1ADdvgPn5HfRLYuHCFYAr2noWnaRDNger2AoA")
    val commitPubKey = Crypto.publicKeyFromPrivateKey(commitKey)
    val finalPubKey = Crypto.publicKeyFromPrivateKey(finalKey)
    val R = "this is Alice's R".getBytes("UTF-8")
    val H = Crypto.sha256(R)
  }

  object Bob {
    val (_, commitKey) = Base58Check.decode("cSupnaiBh6jgTcQf9QANCB5fZtXojxkJQczq5kwfSBeULjNd5Ypo")
    val (_, finalKey) = Base58Check.decode("cQLk5fMydgVwJjygt9ta8GcUU4GXLumNiXJCQviibs2LE5vyMXey")
    val commitPubKey = Crypto.publicKeyFromPrivateKey(commitKey)
    val finalPubKey = Crypto.publicKeyFromPrivateKey(finalKey)
    val R = "this is Bob's R".getBytes("UTF-8")
    val H = Crypto.sha256(R)
  }

  "Protocol" should "implement anchor tx" in {

    val (anchor, anchorOutputIndex) = makeAnchorTx(Alice.commitPubKey, Bob.commitPubKey, 10, OutPoint(previousTx, 0), key)

    val spending = Transaction(version = 1,
      txIn = TxIn(OutPoint(anchor, anchorOutputIndex), Array.emptyByteArray, 0xffffffffL) :: Nil,
      txOut = TxOut(10, OP_DUP :: OP_HASH160 :: OP_PUSHDATA(hash160(Alice.commitPubKey)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) :: Nil,
      lockTime = 0)

    // we only need 2 signatures because this is a 2-on-3 multisig
    val redeemScript = Script.createMultiSigMofN(2, Seq(Alice.commitPubKey, Bob.commitPubKey))

    val sig1 = Transaction.signInput(spending, 0, redeemScript, SIGHASH_ALL, Alice.commitKey, randomize = false)
    val sig2 = Transaction.signInput(spending, 0, redeemScript, SIGHASH_ALL, Bob.commitKey, randomize = false)
    val scriptSig = Script.write(OP_0 :: OP_PUSHDATA(sig1) :: OP_PUSHDATA(sig2) :: OP_PUSHDATA(redeemScript) :: Nil)
    val signedTx = spending.updateSigScript(0, scriptSig)
    Transaction.correctlySpends(signedTx, Seq(anchor), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }
  it should "implement commit tx" in {
    val (anchor, anchorOutputIndex) = makeAnchorTx(Alice.commitPubKey, Bob.commitPubKey, 10, OutPoint(previousTx, 0), key)
    val ours = open_channel(
      delay = locktime(Blocks(100)),
      revocationHash = Alice  .H,
      commitKey = Alice.commitPubKey,
      finalKey = Alice.finalPubKey,
      anch = anchor_offer.WILL_CREATE_ANCHOR,
      nextRevocationHash = null,
      initialFeeRate = 1)
    val theirs = open_channel(
      delay = locktime(Blocks(100)),
      revocationHash = Bob.H,
      commitKey = Bob.commitPubKey,
      finalKey = Bob.finalPubKey,
      anch = anchor_offer.WONT_CREATE_ANCHOR,
      nextRevocationHash = null,
      initialFeeRate = 1)

    // we assume that Alice knows Bob's H
    val openAnchor = open_anchor(anchor.hash, anchorOutputIndex, 10, signature.defaultInstance) // commit sig will be computed later
    val channelState = initialFunding(ours, theirs, openAnchor, fee = 0)
    val tx = makeCommitTx(ours.finalKey, theirs.finalKey, theirs.delay, openAnchor.txid, openAnchor.outputIndex, Bob.H, channelState)
    val redeemScript = multiSig2of2(Alice.commitPubKey, Bob.commitPubKey)
    val sigA = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, Alice.commitKey)
    val openAnchor1 = openAnchor.copy(commitSig = sigA)

    // now Bob receives open anchor and wants to check that Alice's commit sig is valid
    // Bob can sign too and check that he can spend the anchox tx
    val sigB = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, Bob.commitKey)
    val scriptSig = sigScript2of2(openAnchor1.commitSig, sigB, Alice.commitPubKey, Bob.commitPubKey)
    val commitTx = tx.updateSigScript(0, scriptSig)
    Transaction.correctlySpends(commitTx, Seq(anchor), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    // or Bob can just check that Alice's sig is valid
    val hash = Transaction.hashForSigning(commitTx, 0, redeemScript, SIGHASH_ALL)
    assert(Crypto.verifySignature(hash, Crypto.decodeSignature(sigA.dropRight(1)), Alice.commitPubKey))

    // how do we spend our commit tx ?

    // we can spend it by providing Bob's R and his signature
    val spendingTx = {
      val tx = Transaction(version = 1,
        txIn = TxIn(OutPoint(commitTx, 0), Array.emptyByteArray, 0xffffffffL) :: Nil,
        txOut = TxOut(10, OP_DUP :: OP_HASH160 :: OP_PUSHDATA(hash160(Bob.finalPubKey)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) :: Nil,
        lockTime = 0)
      val redeemScript = redeemSecretOrDelay(ours.finalKey, locktime2long_csv(theirs.delay), theirs.finalKey, Bob.H)
      val sig = Transaction.signInput(tx, 0, Script.write(redeemScript), SIGHASH_ALL, Bob.finalKey)
      val sigScript = OP_PUSHDATA(sig) :: OP_PUSHDATA(Bob.R) :: OP_PUSHDATA(Script.write(redeemScript)) :: Nil
      tx.updateSigScript(0, Script.write(sigScript))
    }

    // or

    Transaction.correctlySpends(spendingTx, Seq(commitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS | ScriptFlags.SCRIPT_VERIFY_CHECKLOCKTIMEVERIFY | ScriptFlags.SCRIPT_VERIFY_CHECKSEQUENCEVERIFY)
  }
  it should "sort binary data" in {
    assert(!isLess(Array.emptyByteArray, Array.emptyByteArray))
    assert(isLess(fromHexString("aa"), fromHexString("bb")))
    assert(isLess(fromHexString("aabbcc"), fromHexString("bbbbcc")))
    assert(isLess(fromHexString("aa"), fromHexString("11aa")))
  }
}
