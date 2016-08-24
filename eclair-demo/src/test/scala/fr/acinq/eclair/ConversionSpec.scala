package fr.acinq.eclair

import fr.acinq.bitcoin.{BinaryData, Crypto}
import lightning.{sha256_hash, signature}
import org.junit.runner.RunWith
import org.scalatest.{FunSuite, Ignore}
import org.scalatest.junit.JUnitRunner

import scala.util.Random

@RunWith(classOf[JUnitRunner])
class ConversionSpec extends FunSuite {
  val random = new Random()

  def randomData(size: Int) = {
    val data = new Array[Byte](size)
    random.nextBytes(data)
    data
  }

  test("sha256 conversion") {
    val hash: BinaryData = randomData(32)
    val sha256: sha256_hash = hash
    val hash1: BinaryData = sha256
    assert(hash === hash1)
  }

  test("signature conversion") {
    for (i <- 0 to 100) {
      val priv: BinaryData = randomData(32)
      val pub: BinaryData = Crypto.publicKeyFromPrivateKey(priv)
      val data: BinaryData = randomData(73)
      val sig: BinaryData = Crypto.encodeSignature(Crypto.sign(data, priv))
      assert(Crypto.verifySignature(data, sig, pub))
      val protosig: signature = sig
      val sig1: BinaryData = protosig
      assert(Crypto.verifySignature(data, sig1, pub))
      assert(sig1.take(32) === sig.take(32))
    }
  }

  test("signature compatibility tests") {
    val priv: BinaryData = "a9fa8bb22b3ad32682beac860037ea1c1ea86c4598fdffbeb780e70fc19498bd"

    val datas: Seq[BinaryData] = Seq(
      "8d94049b0beeab2651e4c85db4c132ff38c540129b2f2a739c05c627a2520b25",
      "a397a25553bef1fcf9796b521413e9e22d518e1f56085727a705d4d052827775",
      "1b994aed583d6a5236d5244a688ead955f3c35b5c48cdd6c11323de2b4b459cf",
      "ce233d27dfa7f996fc1ee0662c0e7b8cca30428fbc9f7bced1b8b187ec8ad6bb",
      "2e15630e3cdca43a7a0620a7931b34dd4cf5ec889668d668a0096f8e9347c941",
      "dbaccf9789f3510579712c0e0d606b59d559e16cc1b95563424471550bba97e6",
      "6867fe715b507655c222634f02cea8d8a70a456a431a4d855f3edb6af873d161",
      "5ad0533521c98a63ec6db2eebcda4764e50d4ea8271b2f06598af0537dc234d7",
      "13878cb3d197183e844aacc0a5f4260b02f3b3a81062afe8eca03c6ae2f0c1f5",
      "78cea9c96641086a0c352cb12ad13cabc5ef545553833fbf24fa2b86eb6c7c65")

    val protobufs: Seq[BinaryData] = Seq(
      "0973d2b3f1c3496ff61122518ec6900e006d1968fd7e30c8cd0b5321670ceec2318563bf29a3faa1441c972eb1316e60e3e734b14ef139db513153a30a6b1d413070761616c78d34",
      "09276f16525b6d7365112a331157a23dbfb019ccaf539d0ffda4d121ee1b33d6fd01e0d829e0321e4cc682d7cd31abb31a8703937f5d39091a6bc7e7873fb3418ce60bc8cb852633",
      "09ba60e30fc33d205c1151734756a7a49115197a769e564d4235f5213af885d8903e6ca129b27aeb0068a239ab31c285471b79b415b439fef003f37c41a234418adda6d93c961152",
      "09d83e91dd59efe17d119c321fa1b1806a1619a190265331dfb19b214a0d61067a809dc729c5fd0a2864f8632e31db3f4f90a1be34aa39bd02c7e27e0bd2de419695d91042897c49",
      "0995c6df11611dc0f3112b58a58a8b44e54e19749d5f7c0c14c4f421d985dd793999bcdb298a449e0471fc0710315980e0a69e06478439a18193827e61b21c4113c6127f48503603",
      "09edf5abe5d9aff5341165455dcd4e04b21e19d215046818a95af821a6d565d6c31b494d29d332309c6b14b3613155e0a65b6aa40fb43907d0ff9da5d0deaa413d74e2d45240673a",
      "099653a6e8a8b2e12511c6576d324e0a5cf4199da11f503691007321ddc6dec5ac6e4d9c2979daa8c921a492a231b1038e0495e6f82e3906317339cf830b9e41b455931e6c1a8332",
      "095d14f187c26a28561120c8124a4fc1e5ca197d875b0333f3bcdb21f5fabdacad52cafe29de0d9e0c42bbcee631cd5fd7af87df4c47390c753ad7f5b8cf6141b9a6e96cd7527219",
      "0939a88ec980b80eaa11565a7144e3d79dcb1944ef30b7ddc096b021a59a7e8e6fac0402296dc9b21373cff9bd312d0ee2005612e18c39e488ad0930cdc4024108a009fb8d0a8f5d",
      "094d4a73c5c030b89c115578af9df0ae8c1f196ad4a2c0bb6bb35b21ce47040662f90d6f29d41d678a79235752312827856672cd6fec39cb9082b45d2fa4b5418f1bbea644a15229"
    )
    for (i <- 0 until datas.size) {
      val sig = signature2bin(lightning.signature.parseFrom(protobufs(i)))
      assert(Crypto.verifySignature(datas(i), sig, Crypto.publicKeyFromPrivateKey(priv)))
    }
  }
}
