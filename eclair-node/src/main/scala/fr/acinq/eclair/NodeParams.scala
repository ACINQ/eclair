package fr.acinq.eclair

import java.io.File
import java.net.InetSocketAddress
import java.nio.file.{Files, Paths}

import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.DeterministicWallet.ExtendedPrivateKey
import fr.acinq.bitcoin.{BinaryData, DeterministicWallet}
import fr.acinq.eclair.channel.Data
import fr.acinq.eclair.db.{Dbs, SimpleFileDb, SimpleTypedDb}
import fr.acinq.eclair.io.PeerRecord
import fr.acinq.eclair.wire.LightningMessage

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
                      maxReserveToFundingRatio: Double,
                      channelsDb: SimpleTypedDb[BinaryData, Data],
                      peersDb: SimpleTypedDb[PublicKey, PeerRecord],
                      announcementsDb: SimpleTypedDb[String, LightningMessage])

object NodeParams {

  def loadFromConfiguration(datadir: File): NodeParams = {
    /**
      * Order of precedence for the configuration parameters:
      * 1) Java environment variables (-D...)
      * 2) Configuration file eclair.conf
      * 3) default values in application.conf
      */
    val config = ConfigFactory.parseProperties(System.getProperties)
      .withFallback(ConfigFactory.parseFile(new File(datadir, "eclair.conf")))
      .withFallback(ConfigFactory.load()).getConfig("eclair")

    val seedPath = Paths.get(datadir.getAbsolutePath, "seed.dat")
    val seed: BinaryData = Files.exists(seedPath) match {
      case true => Files.readAllBytes(seedPath)
      case false =>
        val seed = randomKey.toBin
        Files.write(seedPath, seed)
        seed
    }

    val master = DeterministicWallet.generate(seed)
    val extendedPrivateKey = DeterministicWallet.derivePrivateKey(master, DeterministicWallet.hardened(46) :: DeterministicWallet.hardened(0) :: Nil)
    val db = new SimpleFileDb(datadir)
    val color = BinaryData(config.getString("node-color"))
    require(color.size == 3, "color should be a 3-bytes hex buffer")
    NodeParams(
      extendedPrivateKey = extendedPrivateKey,
      privateKey = extendedPrivateKey.privateKey,
      alias = config.getString("node-alias").take(32),
      color = (color.data(0), color.data(1), color.data(2)),
      address = new InetSocketAddress(config.getString("server.public-ip"), config.getInt("server.port")),
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
      maxReserveToFundingRatio = 0.05, // channel reserve can't be more than 5% of the funding amount (recommended: 1%)
      channelsDb = Dbs.makeChannelDb(db),
      peersDb = Dbs.makePeerDb(db),
      announcementsDb = Dbs.makeAnnouncementDb(db)
    )
  }
}
