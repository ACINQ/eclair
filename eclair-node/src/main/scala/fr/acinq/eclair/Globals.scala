package fr.acinq.eclair

import java.net.InetSocketAddress

import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.{BinaryData, DeterministicWallet}
import fr.acinq.eclair.router.Router

import scala.compat.Platform
import scala.concurrent.duration._


/**
  * Created by PM on 25/01/2016.
  */
object Globals {
  val config = ConfigFactory.load().getConfig("eclair")

  object Node {
    val seed: BinaryData = config.getString("node.seed")
    val master = DeterministicWallet.generate(seed)
    val extendedPrivateKey = DeterministicWallet.derivePrivateKey(master, DeterministicWallet.hardened(46) :: DeterministicWallet.hardened(0) :: Nil)
    val privateKey = extendedPrivateKey.privateKey
    val extendedPublicKey = DeterministicWallet.publicKey(extendedPrivateKey)
    val publicKey = extendedPublicKey.publicKey
    val id = publicKey.toBin.toString()
    val alias = config.getString("node.alias").take(32)
    val color: (Byte, Byte, Byte) = (config.getInt("node.color.r").toByte, config.getInt("node.color.g").toByte, config.getInt("node.color.b").toByte)
    val address = new InetSocketAddress(config.getString("server.host"), config.getInt("server.port"))
  }

  val expiry_delta_blocks = config.getInt("expiry-delta-blocks")
  val htlc_minimum_msat = config.getInt("htlc-minimum-msat")
  val delay_blocks = config.getInt("delay-blocks")
  val mindepth_blocks = config.getInt("mindepth-blocks")
  val feeratePerKw = 10000
  val fee_base_msat = config.getInt("fee-base-msat")
  val fee_proportional_millionth = config.getInt("fee-proportional-millionth")

  val default_anchor_amount = 1000000
  val autosign_interval = 300 milliseconds
}
