package fr.acinq.protos

import fr.acinq.bitcoin._

object Test extends App {
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
  def bobRedeemsPayment(tx : Transaction) : Unit = {
    val sigBob = Transaction.signInput(tx, 0, openingTx.txOut(0).publicKeyScript, SIGHASH_ALL, keyBob)
    val sigAlice = Script.parse(tx.txIn(0).signatureScript) match {
      case OP_PUSHDATA(value, _) :: Nil => value
      case _ => throw new RuntimeException("cannot find Alice's signature")
    }
    val sigScript = OP_0 :: OP_PUSHDATA(sigAlice) :: OP_PUSHDATA(sigBob) :: OP_0 :: Nil
    val tx1 = tx.updateSigScript(0, Script.write(sigScript))
    Transaction.correctlySpends(tx1, Seq(openingTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS | ScriptFlags.SCRIPT_VERIFY_CHECKLOCKTIMEVERIFY)
  }

  // bob can use this payment tx
  bobRedeemsPayment(paymentTx1)

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

  bobRedeemsPayment(paymentTx2)
}

object RevokablePaymentChannel extends App {
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
}
