package fr.acinq.eclair

import fr.acinq.bitcoin._
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class PermuteOutputSpec extends FlatSpec {
  "permuteOutputs" should "permute tx output in a determinstic way" in {
    val pub1: BinaryData = "0394D30868076AB1EA7736ED3BDBEC99497A6AD30B25AFD709CDF3804CD389996A"
    val pub2 : BinaryData = "032C58BC9615A6FF24E9132CEF33F1EF373D97DC6DA7933755BC8BB86DBEE9F55C"
    val pub3: BinaryData = "02C4D72D99CA5AD12C17C9CFE043DC4E777075E8835AF96F46D8E3CCD929FE1926"

    val outputs = Seq(
      TxOut(5, OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(pub1)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil),
      TxOut(7, OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(pub2)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil),
      TxOut(11, OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(pub3)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil)
    )

    val tx = Transaction(version = 1, txIn = Seq.empty[TxIn], txOut = Seq.empty[TxOut], lockTime = 0)
    val txs = Seq(
      tx.copy(txOut = Seq(outputs(0), outputs(1), outputs(2))),
      tx.copy(txOut = Seq(outputs(0), outputs(2), outputs(1))),
      tx.copy(txOut = Seq(outputs(1), outputs(0), outputs(2))),
      tx.copy(txOut = Seq(outputs(1), outputs(2), outputs(0))),
      tx.copy(txOut = Seq(outputs(2), outputs(0), outputs(1))),
      tx.copy(txOut = Seq(outputs(2), outputs(1), outputs(0)))
    )
    assert(txs.map(permuteOutputs).map(_.hash).toSet.size === 1)
  }
}
