package fr.acinq.eclair

import java.net.InetSocketAddress

import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{BinaryData, DeterministicWallet, Script}
import fr.acinq.eclair.db.{Dbs, DummyDb}
import fr.acinq.eclair.io.Peer

/**
  * Created by PM on 26/04/2016.
  */
object TestConstants {
  val fundingSatoshis = 1000000L
  val pushMsat = 200000000L

  object Alice {
    val seed = BinaryData("01" * 32)
    val master = DeterministicWallet.generate(seed)
    val extendedPrivateKey = DeterministicWallet.derivePrivateKey(master, DeterministicWallet.hardened(46) :: DeterministicWallet.hardened(0) :: Nil)
    val db = new DummyDb()
    val nodeParams = NodeParams(
      extendedPrivateKey = extendedPrivateKey,
      privateKey = extendedPrivateKey.privateKey,
      alias = "alice",
      color = (1: Byte, 2: Byte, 3: Byte),
      address = new InetSocketAddress("localhost", 9731),
      globalFeatures = "",
      localFeatures = "00", // no announcement
      dustLimitSatoshis = 542,
      maxHtlcValueInFlightMsat = 150000000,
      maxAcceptedHtlcs = 100,
      expiryDeltaBlocks = 144,
      htlcMinimumMsat = 0,
      minDepthBlocks = 3,
      delayBlocks = 144,
      feeratePerKw = 10000,
      feeBaseMsat = 546000,
      feeProportionalMillionth = 10,
      reserveToFundingRatio = 0.01, // note: not used (overriden below)
      maxReserveToFundingRatio = 0.05,
      channelsDb = Dbs.makeChannelDb(db),
      peersDb = Dbs.makePeerDb(db),
      announcementsDb = Dbs.makeAnnouncementDb(db))
    val id = nodeParams.privateKey.publicKey
    val channelParams = Peer.makeChannelParams(
      nodeParams = nodeParams,
      keyIndex = 1,
      defaultFinalScriptPubKey = Script.write(Script.pay2wpkh(PrivateKey(Array.fill[Byte](32)(5), compressed = true).publicKey)),
      isFunder = true,
      fundingSatoshis).copy(
      channelReserveSatoshis = 10000 // Bob will need to keep that much satoshis as direct payment
    )
  }

  object Bob {
    val seed = BinaryData("02" * 32)
    val master = DeterministicWallet.generate(seed)
    val extendedPrivateKey = DeterministicWallet.derivePrivateKey(master, DeterministicWallet.hardened(46) :: DeterministicWallet.hardened(0) :: Nil)
    val db = new DummyDb()
    val nodeParams = NodeParams(
      extendedPrivateKey = extendedPrivateKey,
      privateKey = extendedPrivateKey.privateKey,
      alias = "bob",
      color = (4: Byte, 5: Byte, 6: Byte),
      address = new InetSocketAddress("localhost", 9732),
      globalFeatures = "",
      localFeatures = "00", // no announcement
      dustLimitSatoshis = 542,
      maxHtlcValueInFlightMsat = Long.MaxValue, // Bob has no limit on the combined max value of in-flight htlcs
      maxAcceptedHtlcs = 30,
      expiryDeltaBlocks = 144,
      htlcMinimumMsat = 1000,
      minDepthBlocks = 3,
      delayBlocks = 144,
      feeratePerKw = 10000,
      feeBaseMsat = 546000,
      feeProportionalMillionth = 10,
      reserveToFundingRatio = 0.01, // note: not used (overriden below)
      maxReserveToFundingRatio = 0.05,
      channelsDb = Dbs.makeChannelDb(db),
      peersDb = Dbs.makePeerDb(db),
      announcementsDb = Dbs.makeAnnouncementDb(db))
    val id = nodeParams.privateKey.publicKey
    val channelParams = Peer.makeChannelParams(
      nodeParams = nodeParams,
      keyIndex = 1,
      defaultFinalScriptPubKey = Script.write(Script.pay2wpkh(PrivateKey(Array.fill[Byte](32)(15), compressed = true).publicKey)),
      isFunder = false,
      fundingSatoshis).copy(
      channelReserveSatoshis = 20000 // Alice will need to keep that much satoshis as direct payment
    )
  }

}
