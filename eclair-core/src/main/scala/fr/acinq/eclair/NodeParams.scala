package fr.acinq.eclair

import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.TimeUnit

import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.DeterministicWallet.ExtendedPrivateKey
import fr.acinq.bitcoin.{BinaryData, DeterministicWallet}
import fr.acinq.eclair.channel.Data
import fr.acinq.eclair.db.{Dbs, SimpleFileDb, SimpleTypedDb}
import fr.acinq.eclair.io.PeerRecord
import fr.acinq.eclair.wire.LightningMessage

import scala.concurrent.duration.FiniteDuration

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
                      smartfeeNBlocks: Int,
                      feeBaseMsat: Int,
                      feeProportionalMillionth: Int,
                      reserveToFundingRatio: Double,
                      maxReserveToFundingRatio: Double,
                      channelsDb: SimpleTypedDb[BinaryData, Data],
                      peersDb: SimpleTypedDb[PublicKey, PeerRecord],
                      announcementsDb: SimpleTypedDb[String, LightningMessage],
                      routerBroadcastInterval: FiniteDuration,
                      routerValidateInterval: FiniteDuration,
                      pingInterval: FiniteDuration,
                      maxFeerateMismatch: Double,
                      updateFeeMinDiffRatio: Double,
                      autoReconnect: Boolean,
                      chainHash: BinaryData,
                      paymentRequestPrefix: String = "lntb")

object NodeParams {

  /**
    * Order of precedence for the configuration parameters:
    * 1) Java environment variables (-D...)
    * 2) Configuration file eclair.conf
    * 3) default values in reference.conf
    */
  def loadConfiguration(datadir: File) =
    ConfigFactory.parseProperties(System.getProperties)
      .withFallback(ConfigFactory.parseFile(new File(datadir, "eclair.conf")))
      .withFallback(ConfigFactory.load()).getConfig("eclair")

  def makeNodeParams(datadir: File, config: Config, chainHash: BinaryData): NodeParams = {

    datadir.mkdirs()

    val seedPath = new File(datadir, "seed.dat")
    val seed: BinaryData = seedPath.exists() match {
      case true => Files.readAllBytes(seedPath.toPath)
      case false =>
        val seed = randomKey.toBin
        Files.write(seedPath.toPath, seed)
        seed
    }
    val master = DeterministicWallet.generate(seed)
    val extendedPrivateKey = DeterministicWallet.derivePrivateKey(master, DeterministicWallet.hardened(46) :: DeterministicWallet.hardened(0) :: Nil)

    val dbDir = new File(datadir, "db")
    val db = new SimpleFileDb(dbDir)

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
      smartfeeNBlocks = 3,
      feeBaseMsat = config.getInt("fee-base-msat"),
      feeProportionalMillionth = config.getInt("fee-proportional-millionth"),
      reserveToFundingRatio = 0.01, // recommended by BOLT #2
      maxReserveToFundingRatio = 0.05, // channel reserve can't be more than 5% of the funding amount (recommended: 1%)
      channelsDb = Dbs.makeChannelDb(db),
      peersDb = Dbs.makePeerDb(db),
      announcementsDb = Dbs.makeAnnouncementDb(db),
      routerBroadcastInterval = FiniteDuration(config.getDuration("router-broadcast-interval").getSeconds, TimeUnit.SECONDS),
      routerValidateInterval = FiniteDuration(config.getDuration("router-validate-interval").getSeconds, TimeUnit.SECONDS),
      pingInterval = FiniteDuration(config.getDuration("ping-interval").getSeconds, TimeUnit.SECONDS),
      maxFeerateMismatch = config.getDouble("max-feerate-mismatch"),
      updateFeeMinDiffRatio = config.getDouble("update-fee_min-diff-ratio"),
      autoReconnect = config.getBoolean("auto-reconnect"),
      chainHash = chainHash)
  }
}
