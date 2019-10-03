package fr.acinq.eclair.channel

import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto}
import fr.acinq.eclair._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.wire.{InitHostedChannel, LastCrossSignedState, UpdateAddHtlc}
import org.scalatest.FunSuite

class HostedChannelTypesSpec extends FunSuite {
  val alicePrivKey: Crypto.PrivateKey = randomKey
  val bobPrivKey: Crypto.PrivateKey = randomKey

  val channelId: ByteVector32 = randomBytes32
  val initHostedChannel = InitHostedChannel(UInt64(6), 10 msat, 20, 500000000L msat, 5000, 1000000 sat, 1000000 msat)
  val updateAddHtlc1 = UpdateAddHtlc(channelId, 201, 300 msat, randomBytes32, CltvExpiry(4), Sphinx.emptyOnionPacket)
  val updateAddHtlc2 = UpdateAddHtlc(channelId, 101, 900 msat, randomBytes32, CltvExpiry(40), Sphinx.emptyOnionPacket)

  val lcss = LastCrossSignedState(refundScriptPubKey = randomBytes(119), initHostedChannel, blockDay = 100, localBalanceMsat = 100000 msat, remoteBalanceMsat = 900000 msat,
    localUpdates = 201, remoteUpdates = 101, incomingHtlcs = List(updateAddHtlc1, updateAddHtlc2), outgoingHtlcs = List(updateAddHtlc2, updateAddHtlc1),
    remoteSigOfLocal = ByteVector64.Zeroes, localSigOfRemote = ByteVector64.Zeroes)

  test("LCSS has the same sigHash for different order of in-flight HTLCs") {
    val lcssDifferentHtlcOrder = lcss.copy(incomingHtlcs = List(updateAddHtlc2, updateAddHtlc1), outgoingHtlcs = List(updateAddHtlc1, updateAddHtlc2))
    assert(lcss.hostedSigHash === lcssDifferentHtlcOrder.hostedSigHash)
  }

  test("LCSS reversed twice is the same as original") {
    assert(lcss.reverse.reverse === lcss)
  }

  test("LCSS is correctly ahead and even") {
    assert(!lcss.isEven(lcss))
    assert(lcss.isEven(lcss.reverse))
    assert(lcss.isAhead(lcss.reverse.copy(remoteUpdates = 200))) // their remote view of our local updates is behind
    assert(lcss.isAhead(lcss.copy(localUpdates = 200).reverse)) // their remote view of our local updates is behind
  }

  test("LCSS signature checks 1") {
    val aliceLocallySignedLCSS = lcss.withLocalSigOfRemote(alicePrivKey)
    val bobLocallySignedLCSS = lcss.reverse.withLocalSigOfRemote(bobPrivKey)
    val aliceFullySignedLCSS = aliceLocallySignedLCSS.copy(remoteSigOfLocal = bobLocallySignedLCSS.localSigOfRemote)
    val bobFullySignedLCSS = bobLocallySignedLCSS.copy(remoteSigOfLocal = aliceLocallySignedLCSS.localSigOfRemote)
    assert(aliceFullySignedLCSS.stateUpdate.localUpdates == bobFullySignedLCSS.remoteUpdates)
    assert(bobFullySignedLCSS.stateUpdate.localUpdates == aliceFullySignedLCSS.remoteUpdates)
    assert(bobFullySignedLCSS.verifyRemoteSig(alicePrivKey.publicKey))
    assert(aliceFullySignedLCSS.verifyRemoteSig(bobPrivKey.publicKey))
  }

  test("LCSS signature checks 2") {
    val aliceLocallySignedLCSS = lcss.withLocalSigOfRemote(alicePrivKey)
    val bobLocallySignedLCSS = lcss.reverse.withLocalSigOfRemote(bobPrivKey)
    assert(aliceLocallySignedLCSS.reverse.verifyRemoteSig(alicePrivKey.publicKey)) // Bob verifies Alice remote sig of Bob local view of LCSS
    assert(bobLocallySignedLCSS.reverse.verifyRemoteSig(bobPrivKey.publicKey)) // Alice verifies Bob remote sig of Alice local view of LCSS
  }
}
