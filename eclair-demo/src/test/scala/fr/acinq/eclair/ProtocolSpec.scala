package fr.acinq.eclair

import fr.acinq.bitcoin.Crypto._
import fr.acinq.bitcoin._
import fr.acinq.eclair.channel.ChannelState
import fr.acinq.eclair.channel.Scripts._
import lightning._
import lightning.locktime.Locktime.Blocks
import lightning.open_channel.anchor_offer
import org.junit.runner.RunWith
import org.scalatest.{FlatSpec, FunSuite}
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ProtocolSpec extends FunSuite {
  val previousTx = Transaction.read("010000000190b491456fe93621c0576784bca98a2a63a0cb72035a34b4ffdd48a086dfee18000000006a473044022042ccc84c0faa8f3013862eb1e4327f73766c2c8a1a923ecd8b09a0e50b37449e022076476d1ce3240af8636adc5f6fd550fbed6bff61fbc2f530098120af5aba64660121038d847f4ecb4c297457b149485814d6bb8fa52fb86733bcc4d8f1a302437bfc01feffffff0240420f000000000017a914b5494294ea8ec4c4a00906c69187744d924b61de87e8387c44000000001976a914e3e20826a5dc4dfb7bb7b236a7ba62b55ec0ea6a88accf010000")
  val key: BinaryData = "9a d3 b5 0e fb 03 d9 de 58 7b df 91 8c dd 42 d8 69 03 2d 15 4d 4c 22 1c 88 ac ca f0 d4 a7 8c a0 01".filterNot(_.isSpaceChar)

  object Alice {
    val (_, commitKey) = Base58Check.decode("cVuzKWCszfvjkoJyUasvsrRdECriz8hSd1BDinRNzytwnXmX7m1g")
    val (_, finalKey) = Base58Check.decode("cRUfvpbRtMSqCFD1ADdvgPn5HfRLYuHCFYAr2noWnaRDNger2AoA")
    val commitPubKey = Crypto.publicKeyFromPrivateKey(commitKey)
    val finalPubKey = Crypto.publicKeyFromPrivateKey(finalKey)
    val R: BinaryData = "this is Alice's R".getBytes("UTF-8")
    val H: BinaryData = Crypto.sha256(R)
  }

  object Bob {
    val (_, commitKey) = Base58Check.decode("cSupnaiBh6jgTcQf9QANCB5fZtXojxkJQczq5kwfSBeULjNd5Ypo")
    val (_, finalKey) = Base58Check.decode("cQLk5fMydgVwJjygt9ta8GcUU4GXLumNiXJCQviibs2LE5vyMXey")
    val commitPubKey = Crypto.publicKeyFromPrivateKey(commitKey)
    val finalPubKey = Crypto.publicKeyFromPrivateKey(finalKey)
    val R: BinaryData = "this is Bob's R".getBytes("UTF-8")
    val H: BinaryData = Crypto.sha256(R)
  }
  test("create anchor tx pubscript") {
    val pubkey1: BinaryData = "02eb1a4be1a738f1808093279c7b055a944acdb573f22748cb262b9e374441dcbc"
    val pubkey2: BinaryData = "0255952997073d71d0912b140fe43dc13b93889db5223312076efce173b8188a69"
    assert(anchorPubkeyScript(pubkey1, pubkey2) === BinaryData("0020ee5923cf81831016ae7fe319a8b5b33596b23b25363fbd1e6246e142a5b3d6a8"))
  }


  test("create and spend anchor tx") {
    val (anchor, anchorOutputIndex) = makeAnchorTx(Alice.commitPubKey, Bob.commitPubKey, 10 * 1000, previousTx, 0, key)

    val spending = Transaction(version = 1,
      txIn = TxIn(OutPoint(anchor, anchorOutputIndex), Array.emptyByteArray, 0xffffffffL) :: Nil,
      txOut = TxOut(10 satoshi, OP_DUP :: OP_HASH160 :: OP_PUSHDATA(hash160(Alice.commitPubKey)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) :: Nil,
      lockTime = 0)

    // we only need 2 signatures because this is a 2-on-3 multisig
    val redeemScript = multiSig2of2(Alice.commitPubKey, Bob.commitPubKey)
    val sig1 = Transaction.signInput(spending, 0, redeemScript, SIGHASH_ALL, anchor.txOut(anchorOutputIndex).amount.toLong, 1, Alice.commitKey)
    val sig2 = Transaction.signInput(spending, 0, redeemScript, SIGHASH_ALL, anchor.txOut(anchorOutputIndex).amount.toLong, 1, Bob.commitKey)
    val witness = if (isLess(Alice.commitPubKey, Bob.commitPubKey))
      ScriptWitness(Seq(BinaryData.empty, sig1, sig2, redeemScript))
    else
      ScriptWitness(Seq(BinaryData.empty, sig2, sig1, redeemScript))

    val signedTx = spending.copy(witness = Seq(witness))
    Transaction.correctlySpends(signedTx, Seq(anchor), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }

  test("create and spend commit tx") {
    val (anchor, anchorOutputIndex) = makeAnchorTx(Alice.commitPubKey, Bob.commitPubKey, 100000, previousTx, 0, key)
    val ours = open_channel(
      delay = locktime(Blocks(100)),
      revocationHash = Alice.H,
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
    val openAnchor = open_anchor(anchor.hash, anchorOutputIndex, 1000*1000, signature.defaultInstance) // commit sig will be computed later
    val channelState = ChannelState.initialFunding(1000, ours.initialFeeRate) //   initialFunding(ours, theirs, openAnchor, fee = 0)
    val tx = makeCommitTx(ours.finalKey, theirs.finalKey, theirs.delay, openAnchor.txid, openAnchor.outputIndex, Bob.H, channelState)
    val redeemScript = multiSig2of2(Alice.commitPubKey, Bob.commitPubKey)
    val sigA: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, anchor.txOut(anchorOutputIndex).amount.toLong, 1, Alice.commitKey)
    //val sigA: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, Alice.commitKey)
    val openAnchor1 = openAnchor.copy(commitSig = sigA)

    // now Bob receives open anchor and wants to check that Alice's commit sig is valid
    // Bob can sign too and check that he can spend the anchox tx
    val sigB: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, anchor.txOut(anchorOutputIndex).amount.toLong, 1, Bob.commitKey)
    //val sigB = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, Bob.commitKey)
    val witness = witness2of2(openAnchor1.commitSig, sigB, Alice.commitPubKey, Bob.commitPubKey)
    val commitTx = tx.copy(witness = Seq(witness))
    Transaction.correctlySpends(commitTx, Seq(anchor), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    // or Bob can just check that Alice's sig is valid
    val hash = Transaction.hashForSigning(commitTx, 0, redeemScript, SIGHASH_ALL, anchor.txOut(anchorOutputIndex).amount.toLong, signatureVersion = 1)
    assert(Crypto.verifySignature(hash, Crypto.decodeSignature(sigA.dropRight(1)), Alice.commitPubKey))

    // how do we spend our commit tx ?

    // we can spend it by providing Bob's R and his signature
    val spendingTx = {
      val tx = Transaction(version = 2,
        txIn = TxIn(OutPoint(commitTx, 0), Array.emptyByteArray, 0xffffffffL) :: Nil,
        txOut = TxOut(10 satoshi, OP_DUP :: OP_HASH160 :: OP_PUSHDATA(hash160(Bob.finalPubKey)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) :: Nil,
        lockTime = 0)
      val redeemScript = redeemSecretOrDelay(ours.finalKey, locktime2long_csv(theirs.delay), theirs.finalKey, Bob.H)
      val sig: BinaryData = Transaction.signInput(tx, 0, Script.write(redeemScript), SIGHASH_ALL, commitTx.txOut(0).amount.toLong, 1,  Bob.finalKey)
      val witness = ScriptWitness(sig :: Bob.R :: BinaryData(Script.write(redeemScript)) :: Nil)
      val sigScript = OP_PUSHDATA(sig) :: OP_PUSHDATA(Bob.R) :: OP_PUSHDATA(Script.write(redeemScript)) :: Nil
      //tx.updateSigScript(0, Script.write(sigScript))
      tx.copy(witness = Seq(witness))
    }

    // or

    Transaction.correctlySpends(spendingTx, Seq(commitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS | ScriptFlags.SCRIPT_VERIFY_CHECKLOCKTIMEVERIFY | ScriptFlags.SCRIPT_VERIFY_CHECKSEQUENCEVERIFY)
  }

  test("sort binary data") {
    assert(!isLess(Array.emptyByteArray, Array.emptyByteArray))
    assert(isLess(fromHexString("aa"), fromHexString("bb")))
    assert(isLess(fromHexString("aabbcc"), fromHexString("bbbbcc")))
    assert(isLess(fromHexString("aa"), fromHexString("11aa")))
  }
}
