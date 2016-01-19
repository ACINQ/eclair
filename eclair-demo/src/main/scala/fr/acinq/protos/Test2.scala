package fr.acinq.protos

import fr.acinq.bitcoin._

object Test2 extends App {
  // tx used for funding
  val previousTx = Transaction.read("0100000001bb4f5a244b29dc733c56f80c0fed7dd395367d9d3b416c01767c5123ef124f82000000006b4830450221009e6ed264343e43dfee2373b925915f7a4468e0bc68216606e40064561e6c097a022030f2a50546a908579d0fab539d5726a1f83cfd48d29b89ab078d649a8e2131a0012103c80b6c289bf0421d010485cec5f02636d18fb4ed0f33bfa6412e20918ebd7a34ffffffff0200093d00000000001976a9145dbf52b8d7af4fb5f9b75b808f0a8284493531b388acf0b0b805000000001976a914807c74c89592e8a260f04b5a3bc63e7bef8c282588ac00000000")
  // key that can spend this tx
  val key = SignData(previousTx.txOut(0).publicKeyScript, Base58Check.decode("cV7LGVeY2VPuCyCSarqEqFCUNig2NzwiAEBTTA89vNRQ4Vqjfurs")._2)

  object Alice {
    val (_, commitKey) = Base58Check.decode("cVuzKWCszfvjkoJyUasvsrRdECriz8hSd1BDinRNzytwnXmX7m1g")
    val (_, escapeKey) = Base58Check.decode("cRUfvpbRtMSqCFD1ADdvgPn5HfRLYuHCFYAr2noWnaRDNger2AoA")
    val commitPubKey = Crypto.publicKeyFromPrivateKey(commitKey)
    val escapePubKey = Crypto.publicKeyFromPrivateKey(escapeKey)
    val R = "this is Alice's R".getBytes("UTF-8")
    val H = Crypto.sha256(R)
  }

  object Bob {
    val (_, commitKey) = Base58Check.decode("cSupnaiBh6jgTcQf9QANCB5fZtXojxkJQczq5kwfSBeULjNd5Ypo")
    val (_, escapeKey) = Base58Check.decode("cQLk5fMydgVwJjygt9ta8GcUU4GXLumNiXJCQviibs2LE5vyMXey")
    val commitPubKey = Crypto.publicKeyFromPrivateKey(commitKey)
    val escapePubKey = Crypto.publicKeyFromPrivateKey(escapeKey)
    val R = "this is Bob's R".getBytes("UTF-8")
    val H = Crypto.sha256(R)
  }

  val anchorTx = {
    val redeemScript = OP_HASH256 :: OP_PUSHDATA(Alice.H) :: OP_EQUAL ::
      OP_IF ::
      OP_PUSHDATA(Bob.escapeKey) ::
      OP_ELSE ::
      OP_PUSHDATA(Bob.commitKey) ::
      OP_ENDIF ::
      OP_2 :: OP_SWAP :: OP_PUSHDATA(Alice.commitPubKey) :: OP_2 :: OP_CHECKMULTISIG :: Nil

    val tmpTx = Transaction(
      version = 1,
      txIn = TxIn(outPoint = OutPoint(previousTx.hash, 0), signatureScript = Array.empty[Byte], sequence = 0xffffffffL) :: Nil,
      txOut = TxOut(100, OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(Script.write(redeemScript))) :: OP_EQUAL :: Nil) :: Nil,
      lockTime = 0
    )
    val sigA = Transaction.signInput(tmpTx, 0, tmpTx.txOut(0).publicKeyScript, SIGHASH_ALL, Alice.commitKey)
  }
}
