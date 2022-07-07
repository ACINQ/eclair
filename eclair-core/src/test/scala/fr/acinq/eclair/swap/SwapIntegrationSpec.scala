package fr.acinq.eclair.swap

import akka.actor.typed.scaladsl.adapter._
import akka.actor.{ActorSystem, Kill}
import akka.testkit.TestProbe
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi, SatoshiLong}
import fr.acinq.eclair.BlockHeight
import fr.acinq.eclair.MilliSatoshi.toMilliSatoshi
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.channel.{DATA_NORMAL, RealScidStatus}
import fr.acinq.eclair.integration.basic.fixtures.MinimalNodeFixture
import fr.acinq.eclair.integration.basic.fixtures.composite.TwoNodesFixture
import fr.acinq.eclair.payment.{PaymentEvent, PaymentReceived, PaymentSent}
import fr.acinq.eclair.swap.SwapEvents._
import fr.acinq.eclair.swap.SwapRegister.{ListPendingSwaps, SwapInRequested}
import fr.acinq.eclair.swap.SwapResponses.{Status, SwapOpened}
import fr.acinq.eclair.swap.SwapScripts.claimByCsvDelta
import fr.acinq.eclair.swap.SwapTransactions.claimByInvoiceTxWeight
import fr.acinq.eclair.testutils.FixtureSpec
import org.scalatest.TestData
import org.scalatest.concurrent.{IntegrationPatience, PatienceConfiguration}
import scodec.bits.HexStringSyntax

import scala.concurrent.duration.DurationInt

/**
 * This test checks the integration between SwapInSender and SwapInReceiver
 */

class SwapIntegrationSpec extends FixtureSpec with IntegrationPatience {

  type FixtureParam = TwoNodesFixture

  val SwapIntegrationConfAlice = "swap_integration_conf_alice"
  val SwapIntegrationConfBob = "swap_integration_conf_bob"

  import fr.acinq.eclair.integration.basic.fixtures.MinimalNodeFixture._

  override def createFixture(testData: TestData): FixtureParam = {
    // seeds have been chosen so that node ids start with 02aaaa for alice, 02bbbb for bob, etc.
    val aliceParams = nodeParamsFor("alice", ByteVector32(hex"b4acd47335b25ab7b84b8c020997b12018592bb4631b868762154d77fa8b93a3"))
    val bobParams = nodeParamsFor("bob", ByteVector32(hex"7620226fec887b0b2ebe76492e5a3fd3eb0e47cd3773263f6a81b59a704dc492"))
      .copy(invoiceExpiry = 2 seconds)
    TwoNodesFixture(aliceParams, bobParams)
  }

  override def cleanupFixture(fixture: FixtureParam): Unit = {
    fixture.cleanup()
  }

  def swapProbes(alice: MinimalNodeFixture, bob: MinimalNodeFixture)(implicit system: ActorSystem): (SwapProbes, SwapProbes) = {
    val aliceSwap = SwapProbes(TestProbe()(alice.system), TestProbe()(alice.system), TestProbe()(alice.system))
    val bobSwap = SwapProbes(TestProbe()(bob.system), TestProbe()(bob.system), TestProbe()(bob.system))
    alice.system.eventStream.subscribe(aliceSwap.paymentEvents.ref, classOf[PaymentEvent])
    alice.system.eventStream.subscribe(aliceSwap.swapEvents.ref, classOf[SwapEvent])
    bob.system.eventStream.subscribe(bobSwap.paymentEvents.ref, classOf[PaymentEvent])
    bob.system.eventStream.subscribe(bobSwap.swapEvents.ref, classOf[SwapEvent])
    (aliceSwap, bobSwap)
  }

  def connectNodes(alice: MinimalNodeFixture, bob: MinimalNodeFixture)(implicit system: ActorSystem): ByteVector32 = {
    connect(alice, bob)(system)
    val channelId = openChannel(alice, bob, 100_000 sat)(system).channelId
    confirmChannel(alice, bob, channelId, BlockHeight(420_000), 21)(system)
    confirmChannelDeep(alice, bob, channelId, BlockHeight(420_000), 21)(system)
    assert(getChannelData(alice, channelId)(system).asInstanceOf[DATA_NORMAL].shortIds.real.isInstanceOf[RealScidStatus.Final])
    assert(getChannelData(bob, channelId)(system).asInstanceOf[DATA_NORMAL].shortIds.real.isInstanceOf[RealScidStatus.Final])

    eventually(PatienceConfiguration.Timeout(2 seconds), PatienceConfiguration.Interval(1 second)) {
      getRouterData(alice)(system).privateChannels.size == 1
    }
    alice.watcher.expectMsgType[WatchExternalChannelSpent]
    bob.watcher.expectMsgType[WatchExternalChannelSpent]

    channelId
  }

  test("swap in - claim by invoice") { f =>
    import f._

    val (aliceSwap, bobSwap) = swapProbes(alice, bob)
    val channelId = connectNodes(alice, bob)

    // bob must have enough on-chain balance to send
    val amount = Satoshi(1000)
    val feeRatePerKw = alice.nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(target = alice.nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget)
    val premium = (feeRatePerKw * claimByInvoiceTxWeight / 1000).toLong.sat
    val openingBlock = BlockHeight(1)
    val claimByInvoiceBlock = BlockHeight(4)
    bob.wallet.confirmedBalance = amount + premium

    // swap in sender (bob) requests a swap in with swap in receiver (alice)
    bob.swapRegister ! SwapInRequested(bobSwap.cli.ref, amount, channelId)
    val swapId = bobSwap.cli.expectMsgType[SwapOpened].swapId

    // swap in sender (bob) confirms opening tx on-chain
    val openingTx = bobSwap.swapEvents.expectMsgType[TransactionPublished].tx
    bob.watcher.expectMsgType[WatchTxConfirmed].replyTo ! WatchTxConfirmedTriggered(openingBlock, 0, openingTx)
    assert(openingTx.txOut.head.amount == amount + premium)

    // bob has status of 1 pending swap
    bob.swapRegister ! ListPendingSwaps(bobSwap.cli.ref)
    val bobStatus = bobSwap.cli.expectMsgType[Iterable[Status]]
    assert(bobStatus.size == 1)
    assert(bobStatus.head.swapId === swapId)

    // swap in receiver (alice) confirms opening tx on-chain
    alice.watcher.expectMsgType[WatchTxConfirmed].replyTo ! WatchTxConfirmedTriggered(openingBlock, 0, openingTx)

    // swap in receiver (alice) sends a payment of `amount` to swap in sender (bob)
    assert(aliceSwap.paymentEvents.expectMsgType[PaymentSent].recipientAmount === toMilliSatoshi(amount))
    assert(bobSwap.paymentEvents.expectMsgType[PaymentReceived].amount === toMilliSatoshi(amount))

    // swap in receiver (alice) confirms claim-by-invoice tx on-chain
    val claimTx = aliceSwap.swapEvents.expectMsgType[TransactionPublished].tx
    assert(claimTx.txOut.head.amount == amount) // added on-chain premium consumed as tx fee
    alice.watcher.expectMsgType[WatchTxConfirmed].replyTo ! WatchTxConfirmedTriggered(claimByInvoiceBlock, 0, claimTx)

    // both parties publish that the swap was completed via claim-by-invoice
    assert(aliceSwap.swapEvents.expectMsgType[ClaimByInvoiceConfirmed].swapId == swapId)
    assert(bobSwap.swapEvents.expectMsgType[ClaimByInvoicePaid].swapId == swapId)
  }

  test("swap in - claim by coop, receiver does not have sufficient channel balance") { f =>
    import f._

    val (aliceSwap, bobSwap) = swapProbes(alice, bob)
    val channelId = connectNodes(alice, bob)

    // swap more satoshis than alice has available in the channel to send to bob
    val amount = 100_000 sat
    val feeRatePerKw = alice.nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(target = alice.nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget)
    val premium = (feeRatePerKw * claimByInvoiceTxWeight / 1000).toLong.sat
    val openingBlock = BlockHeight(1)
    val claimByCoopBlock = BlockHeight(2)
    bob.wallet.confirmedBalance = amount + premium

    // swap in sender (bob) requests a swap in with swap in receiver (alice)
    bob.swapRegister ! SwapInRequested(bobSwap.cli.ref, amount, channelId)
    val swapId = bobSwap.cli.expectMsgType[SwapOpened].swapId

    // swap in sender (bob) confirms opening tx on-chain
    val openingTx = bobSwap.swapEvents.expectMsgType[TransactionPublished].tx
    bob.watcher.expectMsgType[WatchTxConfirmed].replyTo ! WatchTxConfirmedTriggered(openingBlock, 0, openingTx)
    assert(openingTx.txOut.head.amount == amount + premium)

    // bob has status of 1 pending swap
    bob.swapRegister ! ListPendingSwaps(bobSwap.cli.ref)
    val bobStatus = bobSwap.cli.expectMsgType[Iterable[Status]]
    assert(bobStatus.size == 1)
    assert(bobStatus.head.swapId === swapId)

    // alice has status of 1 pending swap
    alice.swapRegister ! ListPendingSwaps(aliceSwap.cli.ref)
    val aliceStatus = aliceSwap.cli.expectMsgType[Iterable[Status]]
    assert(aliceStatus.size == 1)
    assert(aliceStatus.head.swapId == swapId)

    // swap in receiver (alice) confirms opening tx on-chain
    alice.watcher.expectMsgType[WatchTxConfirmed].replyTo ! WatchTxConfirmedTriggered(openingBlock, 0, openingTx)

    // swap in sender (bob) confirms claim-by-coop tx on-chain
    val claimTx = bobSwap.swapEvents.expectMsgType[TransactionPublished].tx
    bob.watcher.expectMsgType[WatchTxConfirmed].replyTo ! WatchTxConfirmedTriggered(claimByCoopBlock, 0, claimTx)

    // swap in receiver (alice) confirms opening tx spent by claim tx
    alice.watcher.expectMsgType[WatchOutputSpent].replyTo ! WatchOutputSpentTriggered(claimTx)

    // swap in receiver (alice) completed swap with coop cancel message to sender (bob)
    val claimByCoopEvent = aliceSwap.swapEvents.expectMsgType[ClaimByCoopOffered]
    assert(claimByCoopEvent.swapId == swapId)

    // swap in sender (bob) confirms completed swap with claim-by-coop tx
    assert(bobSwap.swapEvents.expectMsgType[ClaimByCoopConfirmed].swapId == swapId)
  }

  test("swap in - claim by csv, receiver does not pay after opening tx confirmed") { f =>
    import f._

    val (_, bobSwap) = swapProbes(alice, bob)
    val channelId = connectNodes(alice, bob)

    // bob must have enough on-chain balance to send
    val amount = Satoshi(1000)
    val feeRatePerKw = alice.nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(target = alice.nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget)
    val premium = (feeRatePerKw * claimByInvoiceTxWeight / 1000).toLong.sat
    val openingBlock = BlockHeight(1)
    val claimByCsvBlock = claimByCsvDelta.toCltvExpiry(openingBlock).blockHeight
    bob.wallet.confirmedBalance = amount + premium

    // swap in sender (bob) requests a swap in with swap in receiver (alice)
    bob.swapRegister ! SwapInRequested(bobSwap.cli.ref, amount, channelId)
    val swapId = bobSwap.cli.expectMsgType[SwapOpened].swapId

    // swap in sender (bob) confirms opening tx on-chain
    val openingTx = bobSwap.swapEvents.expectMsgType[TransactionPublished].tx

    //  swap in receiver (alice) stops unexpectedly
    alice.swapRegister ! Kill

    // opening tx confirmed
    bob.watcher.expectMsgType[WatchTxConfirmed].replyTo ! WatchTxConfirmedTriggered(openingBlock, 0, openingTx)
    assert(openingTx.txOut.head.amount == amount + premium)

    // bob has status of 1 pending swap
    bob.swapRegister ! ListPendingSwaps(bobSwap.cli.ref)
    val bobStatus = bobSwap.cli.expectMsgType[Iterable[Status]]
    assert(bobStatus.size == 1)
    assert(bobStatus.head.swapId === swapId)

    // opening tx buried by csv delay
    bob.watcher.expectMsgType[WatchTxConfirmed].replyTo ! WatchTxConfirmedTriggered(claimByCsvBlock, 0, openingTx)

    // swap in sender (bob) confirms claim-by-csv tx on-chain if Alice does not send payment
    val claimTx = bobSwap.swapEvents.expectMsgType[TransactionPublished].tx
    bob.watcher.expectMsgType[WatchTxConfirmed].replyTo ! WatchTxConfirmedTriggered(claimByCsvBlock, 0, claimTx)

    // swap in sender (bob) confirms claim-by-csv
    assert(bobSwap.swapEvents.expectMsgType[ClaimByCsvConfirmed].swapId == swapId)
  }

}
