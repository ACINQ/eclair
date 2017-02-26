package fr.acinq.eclair

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{BinaryData, DeterministicWallet}


/**
  * Created by PM on 25/01/2016.
  */
object Globals {
  val config = ConfigFactory.load().getConfig("eclair")

  val seed: BinaryData = config.getString("node.seed")
  val master = DeterministicWallet.generate(seed)
  val extendedPrivateKey = DeterministicWallet.derivePrivateKey(master, DeterministicWallet.hardened(46) :: DeterministicWallet.hardened(0) :: Nil)

  val nodeParams = NodeParams(privateKey = extendedPrivateKey.privateKey,
    alias = config.getString("node.alias").take(32),
    color = (config.getInt("node.color.r").toByte, config.getInt("node.color.g").toByte, config.getInt("node.color.b").toByte),
    address = new InetSocketAddress(config.getString("server.host"), config.getInt("server.port")),
    globalFeatures = BinaryData(""),
    localFeatures = BinaryData("05"), // channels_public and initial_routing_sync
    expiryDeltaBlocks = config.getInt("expiry-delta-blocks"),
    htlcMinimumMsat = config.getInt("htlc-minimum-msat"),
    delayBlocks = config.getInt("delay-blocks"),
    minDepthBlocks = config.getInt("mindepth-blocks"),
    feeratePerKw = 10000,
    feeBaseMsat = config.getInt("fee-base-msat"),
    feeProportionalMillionth = config.getInt("fee-proportional-millionth"),
    maxReserveToFundingRatio = 0.05 // channel reserve can't be more than 5% of the funding amount (recommended: 1%)
  )

  /**
    * This counter holds the current blockchain height.
    * It is mainly used to calculate htlc expiries.
    * The value is updated by the [[fr.acinq.eclair.blockchain.PeerWatcher]] and read by all actors, hence it needs to be thread-safe.
    */
  val blockCount = new AtomicLong(0)
}

case class NodeParams(privateKey: PrivateKey,
                      alias: String,
                      color: (Byte, Byte, Byte),
                      address: InetSocketAddress,
                      globalFeatures: BinaryData,
                      localFeatures: BinaryData,
                      expiryDeltaBlocks: Int,
                      htlcMinimumMsat: Int,
                      delayBlocks: Int,
                      minDepthBlocks: Int,
                      feeratePerKw: Int,
                      feeBaseMsat: Int,
                      feeProportionalMillionth: Int,
                      maxReserveToFundingRatio: Double)
