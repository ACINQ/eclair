package fr.acinq.eclair.channel

import java.util.UUID

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{Block, ByteVector32, ByteVector64, Crypto}
import fr.acinq.eclair._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.{Local, Origin, PaymentLifecycle}
import fr.acinq.eclair.router.Hop
import fr.acinq.eclair.transactions.{CommitmentSpec, OUT}
import fr.acinq.eclair.wire.Onion.FinalLegacyPayload
import fr.acinq.eclair.wire.{ChannelUpdate, Error, InitHostedChannel, LastCrossSignedState, UpdateAddHtlc, UpdateMessage}
import org.scalatest.{FunSuite, Outcome}

class HostedChannelTypesSpec extends FunSuite {
  val alicePrivKey: Crypto.PrivateKey = randomKey
  val bobPrivKey: Crypto.PrivateKey = randomKey

  val channelId: ByteVector32 = randomBytes32
  val initHostedChannel = InitHostedChannel(maxHtlcValueInFlightMsat = UInt64(50000L), htlcMinimumMsat = 10 msat, maxAcceptedHtlcs = 3, 1000000L msat, 5000, 1000000 sat, initialClientBalanceMsat = 0 msat)
  val updateAddHtlc1 = UpdateAddHtlc(channelId, 201, 300 msat, randomBytes32, CltvExpiry(4), Sphinx.emptyOnionPacket)
  val updateAddHtlc2 = UpdateAddHtlc(channelId, 101, 900 msat, randomBytes32, CltvExpiry(40), Sphinx.emptyOnionPacket)

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

  val hdc = HOSTED_DATA_COMMITMENTS(randomKey.publicKey, ChannelVersion.STANDARD, lcss1, futureUpdates = Nil, localCommitmentSpec, originChannels = Map.empty,
    channelId = randomBytes32, isHost = true, channelUpdate, localError = None, remoteError = None, overriddenBalanceProposal = None)

  def makeCmdAdd(amount: MilliSatoshi, destination: PublicKey, currentBlockHeight: Long): (ByteVector32, CMD_ADD_HTLC) = {
    val payment_preimage: ByteVector32 = randomBytes32
    val payment_hash: ByteVector32 = Crypto.sha256(payment_preimage)
    val expiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight)
    val cmd = PaymentLifecycle.buildCommand(UUID.randomUUID, payment_hash, Hop(null, destination, null) :: Nil, FinalLegacyPayload(amount, expiry))._1.copy(commit = false)
    (payment_preimage, cmd)
  }

  /*
  def sendAdd(cmd: CMD_ADD_HTLC, origin: Origin, blockHeight: Long): Either[ChannelException, (HOSTED_DATA_COMMITMENTS, UpdateAddHtlc)] = {

    val add = UpdateAddHtlc(channelId, nextTotalLocal + 1, cmd.amount, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)
    val commits1 = addProposal(Left(add)).copy(originChannels = originChannels + (add.id -> origin))
    val outgoingHtlcs = commits1.nextLocalSpec.htlcs.filter(_.direction == OUT)

    if (commits1.nextLocalSpec.toLocal < 0.msat) {
      return Left(InsufficientFunds(channelId, amount = cmd.amount, missing = -commits1.nextLocalSpec.toLocal.truncateToSatoshi, reserve = 0 sat, fees = 0 sat))
    }

    val htlcValueInFlight = outgoingHtlcs.map(_.add.amountMsat).sum
    if (lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat < htlcValueInFlight) {
      return Left(HtlcValueTooHighInFlight(channelId, maximum = lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat, actual = htlcValueInFlight))
    }

    if (outgoingHtlcs.size > lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs) {
      return Left(TooManyAcceptedHtlcs(channelId, maximum = lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs))
    }

    Right(commits1, add)
  }
   */

  test("Adding invalid HTLCs") {
    val (_, cmdAdd1) = makeCmdAdd(5 msat, randomKey.publicKey, currentBlockHeight = 100)
    assert(hdc.sendAdd(cmdAdd1, Local(UUID.randomUUID, None), blockHeight = 100).left.get.isInstanceOf[HtlcValueTooSmall])
    val (_, cmdAdd2) = makeCmdAdd(50 msat, randomKey.publicKey, currentBlockHeight = 100)
    assert(hdc.sendAdd(cmdAdd2, Local(UUID.randomUUID, None), blockHeight = 300).left.get.isInstanceOf[ExpiryTooSmall])

    val (_, cmdAdd3) = makeCmdAdd(50000 msat, randomKey.publicKey, currentBlockHeight = 100)
    val (_, cmdAdd4) = makeCmdAdd(50000 msat, randomKey.publicKey, currentBlockHeight = 100)
    val (_, cmdAdd5) = makeCmdAdd(20000 msat, randomKey.publicKey, currentBlockHeight = 100)
    val Right((hdc1, _)) = hdc.sendAdd(cmdAdd3, Local(UUID.randomUUID, None), blockHeight = 100)
    assert(hdc1.nextLocalSpec.toLocal === 50000.msat)
    val Right((hdc2, _)) = hdc1.sendAdd(cmdAdd4, Local(UUID.randomUUID, None), blockHeight = 100)

    val outgoingHtlcs = hdc2.nextLocalSpec.htlcs.filter(_.direction == OUT)
    println(outgoingHtlcs.map(_.add.amountMsat))

    assert(hdc2.nextLocalSpec.toLocal === 0.msat)
    val Left(InsufficientFunds(_, _, missing, _, _)) = hdc2.sendAdd(cmdAdd5, Local(UUID.randomUUID, None), blockHeight = 100)
    assert(missing === 20.sat)
  }
}
