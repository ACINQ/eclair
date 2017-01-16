package fr.acinq.eclair

import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.{BinaryData, DeterministicWallet}

import scala.concurrent.duration._


/**
  * Created by PM on 25/01/2016.
  */
object Globals {
  val config = ConfigFactory.load()

  object Node {
    val seed: BinaryData = config.getString("eclair.node.seed")
    val master = DeterministicWallet.generate(seed)
    val extendedPrivateKey = DeterministicWallet.derivePrivateKey(master, DeterministicWallet.hardened(46) :: DeterministicWallet.hardened(0) :: Nil)
    val privateKey = extendedPrivateKey.privateKey
    val extendedPublicKey = DeterministicWallet.publicKey(extendedPrivateKey)
    val publicKey = extendedPublicKey.publicKey
    val id = publicKey.toString()
  }

  val default_locktime = 144
  val default_mindepth = 3
  val default_feeratePerKw = 10000
  val base_fee = config.getInt("eclair.base-fee")
  val proportional_fee = config.getInt("eclair.proportional-fee")
  val default_anchor_amount = 1000000
  val autosign_interval = 300 milliseconds
}
