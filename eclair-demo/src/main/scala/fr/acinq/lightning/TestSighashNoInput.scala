package fr.acinq.lightning

import fr.acinq.bitcoin._

import scala.util.Random

object TestSighashNoInput extends App {
  val SIGHASH_NOINPUT = 0 // does not work anymore on bitcoin-lib/master...
  val keyAlice: BinaryData = "C0B91A94A26DC9BE07374C2280E43B1DE54BE568B2509EF3CE1ADE5C9CF9E8AA"
  val pubAlice = Crypto.publicKeyFromPrivateKey(keyAlice)

  val keyBob: BinaryData = "5C3D081615591ABCE914D231BA009D8AE0174759E4A9AE821D97E28F122E2F8C"
  val pubBob = Crypto.publicKeyFromPrivateKey(keyBob)

  val openingTx = {
    val previousTx = Transaction.read("0100000001bb4f5a244b29dc733c56f80c0fed7dd395367d9d3b416c01767c5123ef124f82000000006b4830450221009e6ed264343e43dfee2373b925915f7a4468e0bc68216606e40064561e6c097a022030f2a50546a908579d0fab539d5726a1f83cfd48d29b89ab078d649a8e2131a0012103c80b6c289bf0421d010485cec5f02636d18fb4ed0f33bfa6412e20918ebd7a34ffffffff0200093d00000000001976a9145dbf52b8d7af4fb5f9b75b808f0a8284493531b388acf0b0b805000000001976a914807c74c89592e8a260f04b5a3bc63e7bef8c282588ac00000000")
    val key = SignData(previousTx.txOut(0).publicKeyScript, Base58Check.decode("cV7LGVeY2VPuCyCSarqEqFCUNig2NzwiAEBTTA89vNRQ4Vqjfurs")._2)


    // create a pub key script that can be redeemed either:
    // by Alice alone, in a tx which locktime is > 100
    // or by Alice and Bob, anytime
    val scriptPubKey = OP_IF ::
      OP_PUSHDATA(Seq(100: Byte)) :: OP_CHECKLOCKTIMEVERIFY :: OP_DROP :: OP_PUSHDATA(pubAlice) :: OP_CHECKSIG ::
      OP_ELSE ::
      OP_2 :: OP_PUSHDATA(pubAlice) :: OP_PUSHDATA(pubBob) :: OP_2 :: OP_CHECKMULTISIG :: OP_ENDIF :: Nil

    // create a tx that sends money to scriptPubKey
    val tx = {
      val tmpTx = Transaction(
        version = 1L,
        txIn = TxIn(OutPoint(previousTx.hash, 0), sequence = 0L, signatureScript = Array.empty[Byte]) :: Nil,
        txOut = TxOut(amount = 100, publicKeyScript = scriptPubKey) :: Nil,
        lockTime = 0
      )
      Transaction.sign(tmpTx, Seq(key))
    }

    Transaction.correctlySpends(tx, Seq(previousTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    tx
  }

  println(openingTx)

  // step #1: basic payment tx
  //

  // Alice signs a payment tx (99 BTC to Alice, 1 BTC to Bob)
  val paymentTx1 = {
    val tmpTx = Transaction(
      version = 1,
      txIn = TxIn(OutPoint(openingTx.hash, 0), sequence = 0L, signatureScript = Array.empty[Byte]) :: Nil,
      txOut = Seq(
        TxOut(amount = 99, publicKeyScript = OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(pubAlice)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil),
        TxOut(amount = 1, publicKeyScript = OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(pubBob)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil)
      ),
      lockTime = 0
    )

    val sigAlice = Transaction.signInput(tmpTx, 0, openingTx.txOut(0).publicKeyScript, SIGHASH_ALL, keyAlice)
    val sigScript = Script.write(OP_PUSHDATA(sigAlice) :: Nil)
    tmpTx.updateSigScript(0, sigScript)
  }

  // Bob can redeem a payment tx by adding his signature to its sig script
  def bobRedeemsPayment(tx : Transaction, key: BinaryData) : Transaction = {
    val sigBob = Transaction.signInput(tx, 0, openingTx.txOut(0).publicKeyScript, SIGHASH_ALL, key)
    val sigAlice = Script.parse(tx.txIn(0).signatureScript) match {
      case OP_PUSHDATA(value, _) :: Nil => value
      case _ => throw new RuntimeException("cannot find Alice's signature")
    }
    val sigScript = OP_0 :: OP_PUSHDATA(sigAlice) :: OP_PUSHDATA(sigBob) :: OP_0 :: Nil
    val tx1 = tx.updateSigScript(0, Script.write(sigScript))
    Transaction.correctlySpends(tx1, Seq(openingTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS | ScriptFlags.SCRIPT_VERIFY_CHECKLOCKTIMEVERIFY)
    tx1
  }

  // bob can use this payment tx
  bobRedeemsPayment(paymentTx1, keyBob)

  val paymentTx2 = {
    val tmpTx = Transaction(
      version = 1,
      txIn = TxIn(OutPoint(openingTx.hash, 0), sequence = 0L, signatureScript = Array.empty[Byte]) :: Nil,
      txOut = Seq(
        TxOut(amount = 98, publicKeyScript = OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(pubAlice)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil),
        TxOut(amount = 2, publicKeyScript = OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(pubBob)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil)
      ),
      lockTime = 0
    )

    val sigAlice = Transaction.signInput(tmpTx, 0, openingTx.txOut(0).publicKeyScript, SIGHASH_ALL, keyAlice)
    val sigScript = Script.write(OP_PUSHDATA(sigAlice) :: Nil)
    tmpTx.updateSigScript(0, sigScript)
  }

  bobRedeemsPayment(paymentTx2, keyBob)

  // step #2: revocable payment tx
  //
  val random = new Random()

  def newKeys : (BinaryData, BinaryData) = {
    val priv = new Array[Byte](32)
    random.nextBytes(priv)
    val pub = Crypto.publicKeyFromPrivateKey(priv)
    (priv, pub)
  }

  // Bob creates a temp address and sends it to Alice
  val (keyBobTmp1, bobTempPub1) = newKeys

  // Alice create a tx that spends our opening tx to
  // a multisig address 2-of-2 pubAlice bobTempPub1
  // this tx is not timelocked
  // this tx is sent to Bob
  val redeemScript1 = Script.createMultiSigMofN(2, List(pubAlice, bobTempPub1))

  val revocablePaymentTx1 = {
    val tmpTx = Transaction(
      version = 1,
      txIn = TxIn(OutPoint(openingTx.hash, 0), sequence = 0L, signatureScript = Array.empty[Byte]) :: Nil,
      txOut = Seq(
        TxOut(amount = 99, publicKeyScript = OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(pubAlice)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil),
        TxOut(amount = 1, publicKeyScript = OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(redeemScript1)) :: OP_EQUAL :: Nil)
      ),
      lockTime = 0
    )

    val sigAlice = Transaction.signInput(tmpTx, 0, openingTx.txOut(0).publicKeyScript, SIGHASH_ALL, keyAlice)
    val sigScript = Script.write(OP_PUSHDATA(sigAlice) :: Nil)
    tmpTx.updateSigScript(0, sigScript)
  }

  // Alice then creates a tx that spends from the tx defined above to Bob.
  // this tx is timelocked and cannot be spent right away
  // this tx is sent to Bob
  val revocablePaymentTx1a = {
    val tmpTx = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(revocablePaymentTx1.hash, 1), sequence = 0L, signatureScript = Array.empty[Byte]) :: Nil,
      txOut = TxOut(amount = 1, publicKeyScript = OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(pubBob)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) :: Nil,
      lockTime = 100
    )

    val sigAlice = Transaction.signInput(tmpTx, 0, redeemScript1, SIGHASH_ALL | SIGHASH_NOINPUT, keyAlice)
    val sigScript = Script.write(OP_PUSHDATA(sigAlice) :: Nil)
    tmpTx.updateSigScript(0, sigScript)
  }

  // we can spend this tx if we know Bob's temporary private key
  val revocablePaymentTx1updated = {
    val tmpTx = revocablePaymentTx1a
    val sigBob = Transaction.signInput(tmpTx, 0, redeemScript1, SIGHASH_ALL | SIGHASH_NOINPUT, keyBobTmp1)
    val sigAlice = Script.parse(tmpTx.txIn(0).signatureScript) match {
      case OP_PUSHDATA(value, _) :: Nil => value
      case _ => throw new RuntimeException("cannot find Alice's signature")
    }
    val sigScript = OP_0 :: OP_PUSHDATA(sigAlice) :: OP_PUSHDATA(sigBob) :: OP_PUSHDATA(redeemScript1) :: Nil
    tmpTx.updateSigScript(0, Script.write(sigScript))
  }

  Transaction.correctlySpends(revocablePaymentTx1updated, Map(OutPoint(revocablePaymentTx1.hash, 1) -> revocablePaymentTx1.txOut(1).publicKeyScript), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)


  // now we want to update the channel and send 2 BTC to Bob instead of 1
  // Bob creates a new temp address and sends it to Alice
  // Bob also sends his first temp key to Alice !!
  val (keyBobTmp2, bobTempPub2) = newKeys

  val redeemScript2 = Script.createMultiSigMofN(2, List(pubAlice, bobTempPub2))

  val revocablePaymentTx2 = {
    val tmpTx = Transaction(
      version = 1,
      txIn = TxIn(OutPoint(openingTx.hash, 0), sequence = 0L, signatureScript = Array.empty[Byte]) :: Nil,
      txOut = Seq(
        TxOut(amount = 98, publicKeyScript = OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(pubAlice)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil),
        TxOut(amount = 2, publicKeyScript = OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(redeemScript2)) :: OP_EQUAL :: Nil)
      ),
      lockTime = 0
    )

    val sigAlice = Transaction.signInput(tmpTx, 0, openingTx.txOut(0).publicKeyScript, SIGHASH_ALL, keyAlice)
    val sigScript = Script.write(OP_PUSHDATA(sigAlice) :: Nil)
    tmpTx.updateSigScript(0, sigScript)
  }

  // Alice then creates a tx that spends from the tx defined above to Bob.
  // this tx is timelocked and cannot be spent right away
  val revocablePaymentTx2a = {
    val tmpTx = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(revocablePaymentTx2.hash, 1), sequence = 0L, signatureScript = Array.empty[Byte]) :: Nil,
      txOut = TxOut(amount = 1, publicKeyScript = OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(pubBob)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) :: Nil,
      lockTime = 100
    )

    val sigAlice = Transaction.signInput(tmpTx, 0, redeemScript2, SIGHASH_ALL | SIGHASH_NOINPUT, keyAlice)
    val sigScript = Script.write(OP_PUSHDATA(sigAlice) :: Nil)
    tmpTx.updateSigScript(0, sigScript)
  }

  // so what happens if Bob wants to spend tx1a and  ?
  // first, he must sign the first revocable payment tx and publish it
  val revocablePaymentTx1Bob = bobRedeemsPayment(revocablePaymentTx1, keyBob)

  def updateOutPoint(tx: Transaction, i: Int, outPoint: OutPoint) : Transaction = {
    tx.copy(txIn = tx.txIn.updated(i, tx.txIn(i).copy(outPoint = outPoint)))
  }

  // he must now claim the multisig output
  val revocablePaymentTx1aBob = {
    val tmpTx = updateOutPoint(revocablePaymentTx1a, 0, OutPoint(revocablePaymentTx1Bob.hash, 1))
    val sigBob = Transaction.signInput(tmpTx, 0, redeemScript1, SIGHASH_ALL | SIGHASH_NOINPUT, keyBobTmp1)
    val sigAlice = Script.parse(tmpTx.txIn(0).signatureScript) match {
      case OP_PUSHDATA(value, _) :: Nil => value
      case _ => throw new RuntimeException("cannot find Alice's signature")
    }
    val sigScript = OP_0 :: OP_PUSHDATA(sigAlice) :: OP_PUSHDATA(sigBob) :: OP_PUSHDATA(redeemScript1) :: Nil
    tmpTx.updateSigScript(0, Script.write(sigScript))
  }
  Transaction.correctlySpends(revocablePaymentTx1aBob, Seq(revocablePaymentTx1Bob),  ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

  // but his tx has a locktime !!

  // Alice, as soon as shes sees that revocablePaymentTx1Bob hash been published, can claim its output right away
  // her tx is not timelocked
  val txAliceTakesAll = {
    val tmpTx = Transaction(
      version = 1,
      txIn = TxIn(OutPoint(revocablePaymentTx1Bob.hash, 1), sequence = 0xffffffffL, signatureScript = Array.empty[Byte]) :: Nil,
      txOut = TxOut(1, OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(pubAlice)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) :: Nil,
      lockTime = 0
    )
    val sigAlice = Transaction.signInput(tmpTx, 0, redeemScript1, SIGHASH_ALL, keyAlice)
    val sigBob = Transaction.signInput(tmpTx, 0, redeemScript1, SIGHASH_ALL, keyBobTmp1)
    val sigScript = OP_0 :: OP_PUSHDATA(sigAlice) :: OP_PUSHDATA(sigBob) :: OP_PUSHDATA(redeemScript1) :: Nil
    tmpTx.updateSigScript(0, Script.write(sigScript))
  }
  Transaction.correctlySpends(txAliceTakesAll, Seq(revocablePaymentTx1Bob),  ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

}
