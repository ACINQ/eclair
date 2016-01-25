package fr.acinq.eclair.compat

import java.security.Security
import javax.crypto.Cipher
import javax.crypto.spec.{SecretKeySpec, IvParameterSpec}

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.channel.{NORMAL_LOWPRIO, TestHelper}
import fr.acinq.eclair.crypto.LightningCrypto
import fr.acinq.eclair.io.AuthHandler
import AuthHandler.Secrets
import lightning.pkt
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

/**
  * Created by PM on 25/01/2016.
  */
@RunWith(classOf[JUnitRunner])
class OpenConnection extends FunSuite {

  test("decrypt payload") {
    val secrets_in = Secrets(aes_key = "8a77c0555bfd5854217e7e13731cf47a", hmac_key = "1c11b544091d5ec1805ba8367df557dfd7c31aa4dc52603caf515169d18bde76", aes_iv = "09b1b593db2dd7debc71c197e2cf8e2a")
    val chunk0 = BinaryData("65642e38cc01919a76d587e8716a426837cda0f6f9c74135d8ba4087550651a572000000000000000febff01b9a897ce7c03dce3a172b99fe94f5261832724e2d12cf01e51c6481cb99c5ea68034826e1bead90445cc0e51c6924f19d74e4c2c067ffefce802725b19e94f7f0f76a91e547364184cb6404635c42b166cec118e33a3d586955241779bdeb10535f2ef68b53cf38f503a56e65d741dc641ce7bb11ad638d9d1f20331")
    val chunk1 = BinaryData("19454d83b1bdd7f4f6719a61834d486b6407d0a1ec06f19c8ff55ebdbcf9fbd1f10000000000000044b3a66e2c1b724dba218a61ffd7ce165e64a1726da3c42177652629432cc81c149d9d69cc1178f492373245f1e376678fbf15622f4762c9871586f186c8f18d5b468e964d08ced15329a692a8889556456cbba1e0f9ccf1f0f3d7824415f5292c3fe21e6bd5a0486995bcdbd8b589295dcb31e25bf8a54c7cd0063a8afa4a4c")

    var totlen_in = 0
    def f(m: BinaryData) = {
      val n = pkt.parseFrom(m)
      println(n)
    }
    //val (rest0, totlen0) = AuthHandler.split(chunk0, secrets_in, totlen_in, f)
    //AuthHandler.split(chunk1, secrets_out, totlen0, f)

    val enc0 = BinaryData("0febff01b9a897ce7c03dce3a172b99fe94f5261832724e2d12cf01e51c6481cb99c5ea68034826e1bead90445cc0e51c6924f19d74e4c2c067ffefce802725b19e94f7f0f76a91e547364184cb6404635c42b166cec118e33a3d586955241779bdeb10535f2ef68b53cf38f503a56e65d741dc641ce7bb11ad638d9d1f20331")
    val enc1 = BinaryData("44b3a66e2c1b724dba218a61ffd7ce165e64a1726da3c42177652629432cc81c149d9d69cc1178f492373245f1e376678fbf15622f4762c9871586f186c8f18d5b468e964d08ced15329a692a8889556456cbba1e0f9ccf1f0f3d7824415f5292c3fe21e6bd5a0486995bcdbd8b589295dcb31e25bf8a54c7cd0063a8afa4a4c")

    Security.addProvider(new BouncyCastleProvider())
    val ivSpec = new IvParameterSpec(secrets_in.aes_iv)
    val cipher = Cipher.getInstance("AES/CTR/NoPadding ", "BC")
    val secretKeySpec = new SecretKeySpec(secrets_in.aes_key, "AES")
    cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivSpec)

    val res0 = BinaryData(cipher.update(enc0.data.toArray))
    val res1 = BinaryData(cipher.update(enc1.data.toArray))

    val m0 = pkt.parseFrom(res0.take(114).toArray)
    val m1 = pkt.parseFrom(res1.take(127).toArray)
    val a = 1
  }
}
