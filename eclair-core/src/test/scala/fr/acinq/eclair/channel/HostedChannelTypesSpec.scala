package fr.acinq.eclair.channel

import java.util.UUID

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{Block, ByteVector32, ByteVector64, Crypto}
import fr.acinq.eclair._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.{Local, PaymentLifecycle}
import fr.acinq.eclair.router.Hop
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.wire.Onion.FinalLegacyPayload
import fr.acinq.eclair.wire.{ChannelUpdate, InitHostedChannel, LastCrossSignedState, UpdateAddHtlc, UpdateFulfillHtlc}
import org.scalatest.FunSuite

class HostedChannelTypesSpec extends FunSuite {
  val alicePrivKey: Crypto.PrivateKey = randomKey
  val bobPrivKey: Crypto.PrivateKey = randomKey

  val channelId: ByteVector32 = randomBytes32
  val initHostedChannel = InitHostedChannel(maxHtlcValueInFlightMsat = UInt64(90000L), htlcMinimumMsat = 10 msat, maxAcceptedHtlcs = 3, 1000000L msat, 5000, 1000000 sat, initialClientBalanceMsat = 0 msat)
  val preimage1: ByteVector32 = randomBytes32
  val preimage2: ByteVector32 = randomBytes32
  val updateAddHtlc1 = UpdateAddHtlc(channelId, 102, 10000 msat, Crypto.sha256(preimage1), CltvExpiry(4), Sphinx.emptyOnionPacket)
  val updateAddHtlc2 = UpdateAddHtlc(channelId, 103, 20000 msat, Crypto.sha256(preimage2), CltvExpiry(40), Sphinx.emptyOnionPacket)

  val lcss = LastCrossSignedState(refundScriptPubKey = randomBytes(119), initHostedChannel, blockDay = 100, localBalanceMsat = 100000 msat, remoteBalanceMsat = 900000 msat,
    localUpdates = 201, remoteUpdates = 101, incomingHtlcs = List(updateAddHtlc1, updateAddHtlc2), outgoingHtlcs = List(updateAddHtlc2, updateAddHtlc1),
    remoteSigOfLocal = ByteVector64.Zeroes, localSigOfRemote = ByteVector64.Zeroes)

  val lcss1: LastCrossSignedState = lcss.copy(incomingHtlcs = Nil, outgoingHtlcs = Nil)

  val localCommitmentSpec = CommitmentSpec(htlcs = Set.empty, feeratePerKw = 0L, lcss1.localBalanceMsat, lcss1.remoteBalanceMsat)

  val channelUpdate = ChannelUpdate(randomBytes64, Block.RegtestGenesisBlock.hash, ShortChannelId(1), 2, 42, 0, CltvExpiryDelta(3), 4 msat, 5 msat, 6, None)

  test("LCSS has the same sigHash for different order of in-flight HTLCs") {
    val lcssDifferentHtlcOrder = lcss.copy(incomingHtlcs = List(updateAddHtlc2, updateAddHtlc1), outgoingHtlcs = List(updateAddHtlc1, updateAddHtlc2))
    assert(lcss.hostedSigHash === lcssDifferentHtlcOrder.hostedSigHash)
  }

  test("Meddled LCSS has a different hash") {
    assert(lcss.hostedSigHash != lcss.copy(localUpdates = 200).hostedSigHash)
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
    assert(aliceFullySignedLCSS.stateUpdate(false).localUpdates === bobFullySignedLCSS.remoteUpdates)
    assert(bobFullySignedLCSS.stateUpdate(false).localUpdates === aliceFullySignedLCSS.remoteUpdates)
    assert(bobFullySignedLCSS.verifyRemoteSig(alicePrivKey.publicKey))
    assert(aliceFullySignedLCSS.verifyRemoteSig(bobPrivKey.publicKey))
  }

  test("LCSS signature checks 2") {
    val aliceLocallySignedLCSS = lcss.withLocalSigOfRemote(alicePrivKey)
    val bobLocallySignedLCSS = lcss.reverse.withLocalSigOfRemote(bobPrivKey)
    assert(aliceLocallySignedLCSS.reverse.verifyRemoteSig(alicePrivKey.publicKey)) // Bob verifies Alice remote sig of Bob local view of LCSS
    assert(bobLocallySignedLCSS.reverse.verifyRemoteSig(bobPrivKey.publicKey)) // Alice verifies Bob remote sig of Alice local view of LCSS
  }

  val hdc = HOSTED_DATA_COMMITMENTS(randomKey.publicKey, ChannelVersion.STANDARD, lcss1, futureUpdates = Nil, localCommitmentSpec, originChannels = Map.empty,
    channelId = randomBytes32, isHost = true, channelUpdate, localError = None, remoteError = None, resolvedOutgoingHtlcLeftoverIds = Set.empty, overriddenBalanceProposal = None)

  def makeCmdAdd(amount: MilliSatoshi, destination: PublicKey, currentBlockHeight: Long): (ByteVector32, CMD_ADD_HTLC) = {
    val payment_preimage: ByteVector32 = randomBytes32
    val payment_hash: ByteVector32 = Crypto.sha256(payment_preimage)
    val expiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight)
    val cmd = PaymentLifecycle.buildCommand(UUID.randomUUID, payment_hash, Hop(null, destination, null) :: Nil, FinalLegacyPayload(amount, expiry))._1.copy(commit = false)
    (payment_preimage, cmd)
  }

  test("Processing HTLCs") {
    val (_, cmdAdd1) = makeCmdAdd(5 msat, randomKey.publicKey, currentBlockHeight = 100)
    assert(hdc.sendAdd(cmdAdd1, Local(UUID.randomUUID, None), blockHeight = 100).left.get.isInstanceOf[HtlcValueTooSmall])
    val (_, cmdAdd2) = makeCmdAdd(50 msat, randomKey.publicKey, currentBlockHeight = 100)
    assert(hdc.sendAdd(cmdAdd2, Local(UUID.randomUUID, None), blockHeight = 300).left.get.isInstanceOf[ExpiryTooSmall])
    val (_, cmdAdd3) = makeCmdAdd(50000 msat, randomKey.publicKey, currentBlockHeight = 100)
    val Right((hdc1, _)) = hdc.sendAdd(cmdAdd3, Local(UUID.randomUUID, None), blockHeight = 100)
    assert(hdc1.nextLocalSpec.toLocal === 50000.msat)
    val (_, cmdAdd4) = makeCmdAdd(40000 msat, randomKey.publicKey, currentBlockHeight = 100)
    val Right((hdc2, _)) = hdc1.sendAdd(cmdAdd4, Local(UUID.randomUUID, None), blockHeight = 100)
    assert(hdc2.nextLocalSpec.toLocal === 10000.msat)
    val (_, cmdAdd5) = makeCmdAdd(20000 msat, randomKey.publicKey, currentBlockHeight = 100)
    val Left(InsufficientFunds(_, _, missing, _, _)) = hdc2.sendAdd(cmdAdd5, Local(UUID.randomUUID, None), blockHeight = 100)
    assert(missing === 10.sat)
    val (_, cmdAdd6) = makeCmdAdd(90001 msat, randomKey.publicKey, currentBlockHeight = 100)
    assert(hdc.sendAdd(cmdAdd6, Local(UUID.randomUUID, None), blockHeight = 100).left.get.isInstanceOf[HtlcValueTooHighInFlight])
    val (bob2AliceAddPreimage, cmdAdd7) = makeCmdAdd(10000 msat, randomKey.publicKey, currentBlockHeight = 100)
    val (_, cmdAdd8) = makeCmdAdd(10000 msat, randomKey.publicKey, currentBlockHeight = 100)
    val (_, cmdAdd9) = makeCmdAdd(10000 msat, randomKey.publicKey, currentBlockHeight = 100)
    val (_, cmdAdd10) = makeCmdAdd(10000 msat, randomKey.publicKey, currentBlockHeight = 100)

    val Right((hdc3, bob2AliceAdd)) = hdc.sendAdd(cmdAdd7, Local(UUID.randomUUID, None), blockHeight = 100)
    val Right((hdc4, _)) = hdc3.sendAdd(cmdAdd8, Local(UUID.randomUUID, None), blockHeight = 100)
    val Right((hdc5, _)) = hdc4.sendAdd(cmdAdd9, Local(UUID.randomUUID, None), blockHeight = 100)
    assert(hdc5.sendAdd(cmdAdd10, Local(UUID.randomUUID, None), blockHeight = 100).left.get.isInstanceOf[TooManyAcceptedHtlcs])
    val hdc6 = hdc5.receiveAdd(updateAddHtlc1)
    val hdc7 = hdc6.receiveAdd(updateAddHtlc2)
    assert(hdc7.nextLocalSpec.toRemote === (hdc.localSpec.toRemote - updateAddHtlc1.amountMsat - updateAddHtlc2.amountMsat))
    assert(hdc7.nextLocalUnsignedLCSS(blockDay = 100).remoteUpdates === 103)
    assert(hdc7.nextLocalUnsignedLCSS(blockDay = 100).localUpdates === 204)
    assert(hdc7.timedOutOutgoingHtlcs(244).isEmpty)
    assert(hdc7.timedOutOutgoingHtlcs(245).size === 3)

    val bobHdc6LCSS: LastCrossSignedState = hdc6.nextLocalUnsignedLCSS(200).reverse.withLocalSigOfRemote(bobPrivKey) // Bob falls behind by one update and has an hdc6 LCSS
    assert(hdc7.futureUpdates.diff(hdc7.findState(bobHdc6LCSS).head.futureUpdates) == List(Right(updateAddHtlc2))) // Alice has hdc7 with all updates and hdc LCSS, finds future state and rest of updates

    val aliceSignedLCSS = hdc7.nextLocalUnsignedLCSS(blockDay = 200).withLocalSigOfRemote(alicePrivKey)
    val bobSignedLCSS = hdc7.nextLocalUnsignedLCSS(blockDay = 200).reverse.withLocalSigOfRemote(bobPrivKey)
    val aliceStateUpdatedHdc = hdc7.copy(lastCrossSignedState = aliceSignedLCSS.copy(remoteSigOfLocal = bobSignedLCSS.localSigOfRemote), localSpec = hdc7.nextLocalSpec, futureUpdates = Nil)
    assert(aliceStateUpdatedHdc.lastCrossSignedState.verifyRemoteSig(bobPrivKey.publicKey)) // Alice now has an updated LCSS signed by Bob
    assert(aliceStateUpdatedHdc.localSpec.htlcs.size === 5) // And 5 HTLCs in-flight

    val (aliceStateUpdatedHdc1, fulfill) = aliceStateUpdatedHdc.sendFulfill(CMD_FULFILL_HTLC(updateAddHtlc1.id, preimage1))
    assert(aliceStateUpdatedHdc1.nextLocalSpec.toLocal === aliceStateUpdatedHdc1.localSpec.toLocal + updateAddHtlc1.amountMsat)
    assert(aliceStateUpdatedHdc1.nextLocalSpec.htlcs.size === 4)
    assert(aliceStateUpdatedHdc1.futureUpdates === List(Left(fulfill)))
    assert(aliceStateUpdatedHdc1.nextLocalUnsignedLCSS(blockDay = 201).withLocalSigOfRemote(alicePrivKey).stateUpdate(false).localUpdates === 205) // Fail/Fulfill also increase an update counter

    val bobFulfill = UpdateFulfillHtlc(channelId, bob2AliceAdd.id, bob2AliceAddPreimage)
    val Right((aliceStateUpdatedHdc2, _, _)) = aliceStateUpdatedHdc1.receiveFulfill(bobFulfill)
    assert(aliceStateUpdatedHdc2.nextLocalSpec.htlcs.size === 3)
    assert(aliceStateUpdatedHdc2.futureUpdates === List(Left(fulfill), Right(bobFulfill)))
    assert(aliceStateUpdatedHdc2.nextLocalUnsignedLCSS(blockDay = 201).withLocalSigOfRemote(alicePrivKey).stateUpdate(true).remoteUpdates === 104) // Fail/Fulfill also increase an update counter
  }
}