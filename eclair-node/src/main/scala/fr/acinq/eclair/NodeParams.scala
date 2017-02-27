package fr.acinq.eclair

import java.net.InetSocketAddress

import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.{BinaryData, DeterministicWallet}
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.DeterministicWallet.ExtendedPrivateKey

/**
  * Created by PM on 26/02/2017.
  */
case class NodeParams(extendedPrivateKey: ExtendedPrivateKey,
                      privateKey: PrivateKey,
                      alias: String,
                      color: (Byte, Byte, Byte),
                      address: InetSocketAddress,
                      globalFeatures: BinaryData,
                      localFeatures: BinaryData,
                      dustLimitSatoshis: Long,
                      maxHtlcValueInFlightMsat: Long,
                      maxAcceptedHtlcs: Int,
                      expiryDeltaBlocks: Int,
                      htlcMinimumMsat: Int,
                      delayBlocks: Int,
                      minDepthBlocks: Int,
                      feeratePerKw: Int,
                      feeBaseMsat: Int,
                      feeProportionalMillionth: Int,
                      reserveToFundingRatio: Double,
                      maxReserveToFundingRatio: Double)

object NodeParams {

  def loadFromConfiguration(): NodeParams = {
    val config = ConfigFactory.load().getConfig("eclair")

    val seed: BinaryData = config.getString("node.seed")
    val master = DeterministicWallet.generate(seed)
    val extendedPrivateKey = DeterministicWallet.derivePrivateKey(master, DeterministicWallet.hardened(46) :: DeterministicWallet.hardened(0) :: Nil)

    NodeParams(
      extendedPrivateKey = extendedPrivateKey,
      privateKey = extendedPrivateKey.privateKey,
      alias = config.getString("node.alias").take(32),
      color = (config.getInt("node.color.r").toByte, config.getInt("node.color.g").toByte, config.getInt("node.color.b").toByte),
      address = new InetSocketAddress(config.getString("server.host"), config.getInt("server.port")),
      globalFeatures = BinaryData(""),
      localFeatures = BinaryData("05"), // channels_public and initial_routing_sync
      dustLimitSatoshis = 542,
      maxHtlcValueInFlightMsat = Long.MaxValue,
      maxAcceptedHtlcs = 100,
      expiryDeltaBlocks = config.getInt("expiry-delta-blocks"),
      htlcMinimumMsat = config.getInt("htlc-minimum-msat"),
      delayBlocks = config.getInt("delay-blocks"),
      minDepthBlocks = config.getInt("mindepth-blocks"),
      feeratePerKw = 10000,
      feeBaseMsat = config.getInt("fee-base-msat"),
      feeProportionalMillionth = config.getInt("fee-proportional-millionth"),
      reserveToFundingRatio = 0.01, // recommended by BOLT #2
      maxReserveToFundingRatio = 0.05 // channel reserve can't be more than 5% of the funding amount (recommended: 1%)
    )
  }
}
