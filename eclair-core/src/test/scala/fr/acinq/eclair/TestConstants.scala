package fr.acinq.eclair

import java.net.InetSocketAddress

import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{BinaryData, Block, DeterministicWallet, Script}
import fr.acinq.eclair.db.{Dbs, DummyDb}
import fr.acinq.eclair.io.Peer
import scala.concurrent.duration._

/**
  * Created by PM on 26/04/2016.
  */
object TestConstants {
  val fundingSatoshis = 1000000L
  val pushMsat = 200000000L
  val feeratePerKw = 10000L

  object Alice {
    val seed = BinaryData("01" * 32)
    val master = DeterministicWallet.generate(seed)
    val extendedPrivateKey = DeterministicWallet.derivePrivateKey(master, DeterministicWallet.hardened(46) :: DeterministicWallet.hardened(0) :: Nil)
    def db = new DummyDb()
    def nodeParams = NodeParams(
      extendedPrivateKey = extendedPrivateKey,
      privateKey = extendedPrivateKey.privateKey,
      alias = "alice",
      color = (1: Byte, 2: Byte, 3: Byte),
      publicAddresses = new InetSocketAddress("localhost", 9731) :: Nil,
      globalFeatures = "",
      localFeatures = "00",
      dustLimitSatoshis = 500,
      maxHtlcValueInFlightMsat = UInt64(150000000),
      maxAcceptedHtlcs = 100,
      expiryDeltaBlocks = 144,
      htlcMinimumMsat = 0,
      minDepthBlocks = 3,
      delayBlocks = 144,
      smartfeeNBlocks = 3,
      feeBaseMsat = 546000,
      feeProportionalMillionth = 10,
      reserveToFundingRatio = 0.01, // note: not used (overriden below)
      maxReserveToFundingRatio = 0.05,
      defaultFinalScriptPubKey = Script.write(Script.pay2wpkh(PrivateKey(Array.fill[Byte](32)(5), compressed = true).publicKey)),
      channelsDb = Dbs.makeChannelDb(db),
      peersDb = Dbs.makePeerDb(db),
      announcementsDb = Dbs.makeAnnouncementDb(db),
      routerBroadcastInterval = 60 seconds,
      routerValidateInterval = 2 seconds,
      pingInterval = 30 seconds,
      maxFeerateMismatch = 1.5,
      updateFeeMinDiffRatio = 0.1,
      autoReconnect = false,
      chainHash = Block.RegtestGenesisBlock.blockId,
      channelFlags = 1)
    def id = nodeParams.privateKey.publicKey
    def channelParams = Peer.makeChannelParams(
      nodeParams = nodeParams,
      isFunder = true,
      fundingSatoshis).copy(
      channelReserveSatoshis = 10000 // Bob will need to keep that much satoshis as direct payment
    )
  }

  object Bob {
    val seed = BinaryData("02" * 32)
    val master = DeterministicWallet.generate(seed)
    val extendedPrivateKey = DeterministicWallet.derivePrivateKey(master, DeterministicWallet.hardened(46) :: DeterministicWallet.hardened(0) :: Nil)
    def db = new DummyDb()
    def nodeParams = NodeParams(
      extendedPrivateKey = extendedPrivateKey,
      privateKey = extendedPrivateKey.privateKey,
      alias = "bob",
      color = (4: Byte, 5: Byte, 6: Byte),
      publicAddresses = new InetSocketAddress("localhost", 9732) :: Nil,
      globalFeatures = "",
      localFeatures = "00", // no announcement
      dustLimitSatoshis = 1000,
      maxHtlcValueInFlightMsat = UInt64.MaxValue, // Bob has no limit on the combined max value of in-flight htlcs
      maxAcceptedHtlcs = 30,
      expiryDeltaBlocks = 144,
      htlcMinimumMsat = 1000,
      minDepthBlocks = 3,
      delayBlocks = 144,
      smartfeeNBlocks = 3,
      feeBaseMsat = 546000,
      feeProportionalMillionth = 10,
      reserveToFundingRatio = 0.01, // note: not used (overriden below)
      maxReserveToFundingRatio = 0.05,
      defaultFinalScriptPubKey = Script.write(Script.pay2wpkh(PrivateKey(Array.fill[Byte](32)(5), compressed = true).publicKey)),
      channelsDb = Dbs.makeChannelDb(db),
      peersDb = Dbs.makePeerDb(db),
      announcementsDb = Dbs.makeAnnouncementDb(db),
      routerBroadcastInterval = 60 seconds,
      routerValidateInterval = 2 seconds,
      pingInterval = 30 seconds,
      maxFeerateMismatch = 1.0,
      updateFeeMinDiffRatio = 0.1,
      autoReconnect = false,
      chainHash = Block.RegtestGenesisBlock.blockId,
      channelFlags = 1)
    def id = nodeParams.privateKey.publicKey
    def channelParams = Peer.makeChannelParams(
      nodeParams = nodeParams,
      isFunder = false,
      fundingSatoshis).copy(
      channelReserveSatoshis = 20000 // Alice will need to keep that much satoshis as direct payment
    )
  }

}
