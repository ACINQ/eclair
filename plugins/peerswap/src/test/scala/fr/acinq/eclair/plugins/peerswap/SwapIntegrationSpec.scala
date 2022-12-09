package fr.acinq.eclair.plugins.peerswap

import akka.actor.typed.scaladsl.adapter._
import akka.actor.{ActorSystem, Kill}
import akka.testkit.TestProbe
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi, SatoshiLong}
import fr.acinq.eclair.MilliSatoshi.toMilliSatoshi
import fr.acinq.eclair.blockchain.DummyOnChainWallet
import fr.acinq.eclair.blockchain.OnChainWallet.MakeFundingTxResponse
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.{DATA_NORMAL, RealScidStatus}
import fr.acinq.eclair.integration.basic.fixtures.MinimalNodeFixture
import fr.acinq.eclair.integration.basic.fixtures.composite.TwoNodesFixture
import fr.acinq.eclair.payment.{PaymentEvent, PaymentReceived, PaymentSent}
import fr.acinq.eclair.plugins.peerswap.SwapEvents._
import fr.acinq.eclair.plugins.peerswap.SwapIntegrationFixture.swapRegister
import fr.acinq.eclair.plugins.peerswap.SwapRegister.{CancelSwapRequested, ListPendingSwaps, SwapRequested}
import fr.acinq.eclair.plugins.peerswap.SwapResponses.{OpeningFundingFailed, PeerCanceled, Status, SwapOpened}
import fr.acinq.eclair.plugins.peerswap.SwapRole.{Maker, Taker}
import fr.acinq.eclair.plugins.peerswap.transactions.SwapScripts.claimByCsvDelta
import fr.acinq.eclair.plugins.peerswap.transactions.SwapTransactions.openingTxWeight
import fr.acinq.eclair.testutils.FixtureSpec
import fr.acinq.eclair.{BlockHeight, ShortChannelId}
import org.scalatest.TestData
import org.scalatest.concurrent.{IntegrationPatience, PatienceConfiguration}
import scodec.bits.{ByteVector, HexStringSyntax}

import scala.concurrent.{ExecutionContext, Future}
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
      .copy(pluginParams = Seq(new PeerSwapPlugin().params))
    val bobParams = nodeParamsFor("bob", ByteVector32(hex"7620226fec887b0b2ebe76492e5a3fd3eb0e47cd3773263f6a81b59a704dc492"))
      .copy(invoiceExpiry = 2 seconds, pluginParams = Seq(new PeerSwapPlugin().params))
    TwoNodesFixture(aliceParams, bobParams, testData.name)
  }

  override def cleanupFixture(fixture: FixtureParam): Unit = {
    fixture.cleanup()
  }

  def swapActors(alice: MinimalNodeFixture, bob: MinimalNodeFixture): (SwapActors, SwapActors) = {
    val aliceSwap = SwapActors(TestProbe()(alice.system), TestProbe()(alice.system), TestProbe()(alice.system), swapRegister(alice))
    val bobSwap = SwapActors(TestProbe()(bob.system), TestProbe()(bob.system), TestProbe()(bob.system), swapRegister(bob))
    alice.system.eventStream.subscribe(aliceSwap.paymentEvents.ref, classOf[PaymentEvent])
    alice.system.eventStream.subscribe(aliceSwap.swapEvents.ref, classOf[SwapEvent])
    bob.system.eventStream.subscribe(bobSwap.paymentEvents.ref, classOf[PaymentEvent])
    bob.system.eventStream.subscribe(bobSwap.swapEvents.ref, classOf[SwapEvent])
    (aliceSwap, bobSwap)
  }

  def connectNodes(alice: MinimalNodeFixture, bob: MinimalNodeFixture)(implicit system: ActorSystem): ShortChannelId = {
    connect(alice, bob)(system)
    val channelId = openChannel(alice, bob, 100_000 sat)(system).channelId
    confirmChannel(alice, bob, channelId, BlockHeight(420_000), 21)(system)
    confirmChannelDeep(alice, bob, channelId, BlockHeight(420_000), 21)(system)
    val shortChannelId = getChannelData(alice, channelId)(system).asInstanceOf[DATA_NORMAL].shortIds.real.toOption.get
    assert(getChannelData(alice, channelId)(system).asInstanceOf[DATA_NORMAL].shortIds.real.isInstanceOf[RealScidStatus.Final])
    assert(getChannelData(bob, channelId)(system).asInstanceOf[DATA_NORMAL].shortIds.real.isInstanceOf[RealScidStatus.Final])

    eventually(PatienceConfiguration.Timeout(2 seconds), PatienceConfiguration.Interval(1 second)) {
      getRouterData(alice)(system).privateChannels.size == 1
    }
    alice.watcher.expectMsgType[WatchExternalChannelSpent]
    bob.watcher.expectMsgType[WatchExternalChannelSpent]

    shortChannelId
  }

  def nodeWithOnChainBalance(node: MinimalNodeFixture, balance: Satoshi): MinimalNodeFixture = node.copy(wallet = new DummyOnChainWallet() {
    override def makeFundingTx(pubkeyScript: ByteVector, amount: Satoshi, feeRatePerKw: FeeratePerKw)(implicit ec: ExecutionContext): Future[MakeFundingTxResponse] = {
      if (amount <= balance) {
        val tx = DummyOnChainWallet.makeDummyFundingTx(pubkeyScript, amount)
        funded += (tx.fundingTx.txid -> tx.fundingTx)
        Future.successful(tx)
      } else {
        Future.failed(new RuntimeException("insufficient funds"))
      }
    }
  })

  test("swap in - claim by invoice") { f =>
    import f._

    val amount = Satoshi(1000)
    val feeRatePerKw = alice.nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(target = alice.nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget)
    val premium = 0.sat // TODO: (feeRatePerKw * claimByInvoiceTxWeight / 1000).toLong.sat
    val openingBlock = BlockHeight(1)
    val claimByInvoiceBlock = BlockHeight(4)
    // bob must have enough on-chain balance to send
    val fundedBob = nodeWithOnChainBalance(bob, amount+premium)
    val (aliceSwap, bobSwap) = swapActors(alice, fundedBob)
    val shortChannelId = connectNodes(alice, fundedBob)

    // swap in sender (bob) requests a swap in with swap in receiver (alice)
    bobSwap.swapRegister ! SwapRequested(bobSwap.cli.ref.toTyped, Maker, amount, shortChannelId, None)
    val swapId = bobSwap.cli.expectMsgType[SwapOpened].swapId

    // swap in sender (bob) confirms opening tx published
    val openingTx = bobSwap.swapEvents.expectMsgType[TransactionPublished].tx

    // bob has status of 1 pending swap
    bobSwap.swapRegister ! ListPendingSwaps(bobSwap.cli.ref.toTyped)
    val bobStatus = bobSwap.cli.expectMsgType[Iterable[Status]]
    assert(bobStatus.size == 1)
    assert(bobStatus.head.swapId === swapId)

    // swap in receiver (alice) confirms opening tx on-chain
    alice.watcher.expectMsgType[WatchTxConfirmed].replyTo ! WatchTxConfirmedTriggered(openingBlock, 0, openingTx)
    assert(openingTx.txOut.head.amount == amount + premium)

    // swap in receiver (alice) sends a payment of `amount` to swap in sender (bob)
    assert(aliceSwap.paymentEvents.expectMsgType[PaymentSent].recipientAmount === toMilliSatoshi(amount))
    assert(bobSwap.paymentEvents.expectMsgType[PaymentReceived].amount === toMilliSatoshi(amount))

    // swap in receiver (alice) confirms claim-by-invoice tx published
    val claimTx = aliceSwap.swapEvents.expectMsgType[TransactionPublished].tx
    // TODO: assert(claimTx.txOut.head.amount == amount) // added on-chain premium consumed as tx fee
    alice.watcher.expectMsgType[WatchTxConfirmed].replyTo ! WatchTxConfirmedTriggered(claimByInvoiceBlock, 0, claimTx)

    // both parties publish that the swap was completed via claim-by-invoice
    assert(aliceSwap.swapEvents.expectMsgType[ClaimByInvoiceConfirmed].swapId == swapId)
    assert(bobSwap.swapEvents.expectMsgType[ClaimByInvoicePaid].swapId == swapId)
  }

  test("swap in - claim by coop, receiver does not have sufficient channel balance") { f =>
    import f._

    // swap more satoshis than alice has available in the channel to send to bob
    val amount = 100_000 sat
    val feeRatePerKw = alice.nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(target = alice.nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget)
    val premium = 0.sat // TODO: (feeRatePerKw * claimByInvoiceTxWeight / 1000).toLong.sat
    val openingBlock = BlockHeight(1)
    val claimByCoopBlock = BlockHeight(2)
    val (aliceSwap, bobSwap) = swapActors(alice, bob)
    val shortChannelId = connectNodes(alice, bob)

    // swap in sender (bob) requests a swap in with swap in receiver (alice)
    bobSwap.swapRegister ! SwapRequested(bobSwap.cli.ref.toTyped, Maker, amount, shortChannelId, None)
    val swapId = bobSwap.cli.expectMsgType[SwapOpened].swapId

    // swap in sender (bob) confirms opening tx published
    val openingTx = bobSwap.swapEvents.expectMsgType[TransactionPublished].tx
    assert(openingTx.txOut.head.amount == amount + premium)

    // bob has status of 1 pending swap
    bobSwap.swapRegister ! ListPendingSwaps(bobSwap.cli.ref.toTyped)
    val bobStatus = bobSwap.cli.expectMsgType[Iterable[Status]]
    assert(bobStatus.size == 1)
    assert(bobStatus.head.swapId === swapId)

    // alice has status of 1 pending swap
    aliceSwap.swapRegister ! ListPendingSwaps(aliceSwap.cli.ref.toTyped)
    val aliceStatus = aliceSwap.cli.expectMsgType[Iterable[Status]]
    assert(aliceStatus.size == 1)
    assert(aliceStatus.head.swapId == swapId)

    // swap in receiver (alice) confirms opening tx on-chain
    alice.watcher.expectMsgType[WatchTxConfirmed].replyTo ! WatchTxConfirmedTriggered(openingBlock, 0, openingTx)

    // swap in sender (bob) confirms opening tx on-chain before publishing claim-by-coop tx
    bob.watcher.expectMsgType[WatchTxConfirmed].replyTo ! WatchTxConfirmedTriggered(openingBlock, 0, openingTx)

    // swap in sender (bob) confirms claim-by-coop tx published and confirmed on-chain
    val claimTx = bobSwap.swapEvents.expectMsgType[TransactionPublished].tx
    bob.watcher.expectMsgType[WatchTxConfirmed].replyTo ! WatchTxConfirmedTriggered(claimByCoopBlock, 0, claimTx)

    // swap in receiver (alice) completed swap with coop cancel message to sender (bob)
    val claimByCoopEvent = aliceSwap.swapEvents.expectMsgType[ClaimByCoopOffered]
    assert(claimByCoopEvent.swapId == swapId)

    // swap in sender (bob) confirms completed swap with claim-by-coop tx
    assert(bobSwap.swapEvents.expectMsgType[ClaimByCoopConfirmed].swapId == swapId)
  }

  test("swap in - claim by csv, receiver does not pay after opening tx confirmed") { f =>
    import f._

    val amount = Satoshi(1000)
    val feeRatePerKw = alice.nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(target = alice.nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget)
    val premium = 0.sat // TODO: (feeRatePerKw * claimByInvoiceTxWeight / 1000).toLong.sat
    val openingBlock = BlockHeight(1)
    val claimByCsvBlock = claimByCsvDelta.toCltvExpiry(openingBlock).blockHeight
    // bob must have enough on-chain balance to send
    val fundedBob = nodeWithOnChainBalance(bob, amount + premium)
    val (aliceSwap, bobSwap) = swapActors(alice, fundedBob)
    val shortChannelId = connectNodes(alice, fundedBob)

    // swap in sender (bob) requests a swap in with swap in receiver (alice)
    bobSwap.swapRegister ! SwapRequested(bobSwap.cli.ref.toTyped, Maker, amount, shortChannelId, None)
    val swapId = bobSwap.cli.expectMsgType[SwapOpened].swapId

    // swap in sender (bob) confirms opening tx published
    val openingTx = bobSwap.swapEvents.expectMsgType[TransactionPublished].tx
    assert(openingTx.txOut.head.amount == amount + premium)

    //  swap in receiver (alice) stops unexpectedly
    aliceSwap.swapRegister.toClassic ! Kill

    // bob has status of 1 pending swap
    bobSwap.swapRegister ! ListPendingSwaps(bobSwap.cli.ref.toTyped)
    val bobStatus = bobSwap.cli.expectMsgType[Iterable[Status]]
    assert(bobStatus.size == 1)
    assert(bobStatus.head.swapId === swapId)

    // opening tx buried by csv delay
    bob.watcher.expectMsgType[WatchFundingDeeplyBuried].replyTo ! WatchFundingDeeplyBuriedTriggered(claimByCsvBlock, 0, openingTx)

    // swap in sender (bob) confirms claim-by-csv tx published and confirmed if Alice does not send payment
    val claimTx = bobSwap.swapEvents.expectMsgType[TransactionPublished].tx
    bob.watcher.expectMsgType[WatchTxConfirmed].replyTo ! WatchTxConfirmedTriggered(claimByCsvBlock, 0, claimTx)

    // swap in sender (bob) confirms claim-by-csv
    assert(bobSwap.swapEvents.expectMsgType[ClaimByCsvConfirmed].swapId == swapId)
  }

  test("swap in - claim by coop, receiver cancels while waiting for opening tx to confirm") { f =>
    import f._

    val amount = Satoshi(1000)
    val feeRatePerKw = alice.nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(target = alice.nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget)
    val premium = 0.sat // TODO: (feeRatePerKw * claimByInvoiceTxWeight / 1000).toLong.sat
    val openingBlock = BlockHeight(1)
    val claimByCoopBlock = claimByCsvDelta.toCltvExpiry(openingBlock).blockHeight
    // bob must have enough on-chain balance to send
    val fundedBob = nodeWithOnChainBalance(bob, amount + premium)
    val (aliceSwap, bobSwap) = swapActors(alice, fundedBob)
    val shortChannelId = connectNodes(alice, fundedBob)

    // swap in sender (bob) requests a swap in with swap in receiver (alice)
    bobSwap.swapRegister ! SwapRequested(bobSwap.cli.ref.toTyped, Maker, amount, shortChannelId, None)
    val swapId = bobSwap.cli.expectMsgType[SwapOpened].swapId

    // swap in sender (bob) confirms opening tx is published, but NOT yet confirmed on-chain
    val openingTx = bobSwap.swapEvents.expectMsgType[TransactionPublished].tx

    //  swap in receiver (alice) sends CoopClose before the opening tx has been confirmed on-chain
    aliceSwap.swapRegister ! CancelSwapRequested(aliceSwap.cli.ref.toTyped, swapId)
    val claimByCoopEvent = aliceSwap.swapEvents.expectMsgType[ClaimByCoopOffered]
    assert(claimByCoopEvent.swapId == swapId)

    // swap in sender (bob) watches for opening tx to be confirmed in a block before publishing the claim by coop tx
    bob.watcher.expectMsgType[WatchTxConfirmed].replyTo ! WatchTxConfirmedTriggered(openingBlock, 0, openingTx)

    // swap in sender (bob) confirms claim by coop tx published and confirmed on-chain
    val claimByCoopTx = bobSwap.swapEvents.expectMsgType[TransactionPublished].tx
    bob.watcher.expectMsgType[WatchTxConfirmed].replyTo ! WatchTxConfirmedTriggered(claimByCoopBlock, 0, claimByCoopTx)

    // swap in sender (bob) confirms claim-by-coop
    assert(bobSwap.swapEvents.expectMsgType[ClaimByCoopConfirmed].swapId == swapId)
  }

  test("swap out - claim by invoice") { f =>
    import f._

    val amount = Satoshi(1000)
    val feeRatePerKw = alice.nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(target = alice.nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget)
    val fee = (feeRatePerKw * openingTxWeight / 1000).toLong.sat
    val openingBlock = BlockHeight(1)
    val claimByInvoiceBlock = BlockHeight(4)
    // bob must have enough on-chain balance to send
    val fundedBob = nodeWithOnChainBalance(bob, amount + fee)
    val (aliceSwap, bobSwap) = swapActors(alice, fundedBob)
    val shortChannelId = connectNodes(alice, fundedBob)

    // swap out receiver (alice) requests a swap out with swap out sender (bob)
    aliceSwap.swapRegister ! SwapRequested(aliceSwap.cli.ref.toTyped, Taker, amount, shortChannelId, None)
    val swapId = aliceSwap.cli.expectMsgType[SwapOpened].swapId

    // swap out receiver (alice) sends a payment of `fee` to swap out sender (bob)
    assert(aliceSwap.paymentEvents.expectMsgType[PaymentSent].recipientAmount === toMilliSatoshi(fee))
    assert(bobSwap.paymentEvents.expectMsgType[PaymentReceived].amount === toMilliSatoshi(fee))

    // swap out sender (bob) confirms opening tx published
    val openingTx = bobSwap.swapEvents.expectMsgType[TransactionPublished].tx
    assert(openingTx.txOut.head.amount == amount)

    // bob has status of 1 pending swap
    bobSwap.swapRegister ! ListPendingSwaps(bobSwap.cli.ref.toTyped)
    val bobStatus = bobSwap.cli.expectMsgType[Iterable[Status]]
    assert(bobStatus.size == 1)
    assert(bobStatus.head.swapId === swapId)

    // swap out receiver (alice) confirms opening tx on-chain
    alice.watcher.expectMsgType[WatchTxConfirmed].replyTo ! WatchTxConfirmedTriggered(openingBlock, 0, openingTx)
    assert(openingTx.txOut.head.amount == amount)

    // swap out receiver (alice) sends a payment of `amount` to swap out sender (bob)
    assert(aliceSwap.paymentEvents.expectMsgType[PaymentSent].recipientAmount === toMilliSatoshi(amount))
    assert(bobSwap.paymentEvents.expectMsgType[PaymentReceived].amount === toMilliSatoshi(amount))

    // swap out receiver (alice) confirms claim-by-invoice tx published
    val claimTx = aliceSwap.swapEvents.expectMsgType[TransactionPublished].tx
    alice.watcher.expectMsgType[WatchTxConfirmed].replyTo ! WatchTxConfirmedTriggered(claimByInvoiceBlock, 0, claimTx)

    // both parties publish that the swap was completed via claim-by-invoice
    assert(aliceSwap.swapEvents.expectMsgType[ClaimByInvoiceConfirmed].swapId == swapId)
    assert(bobSwap.swapEvents.expectMsgType[ClaimByInvoicePaid].swapId == swapId)
  }

  test("swap in - sender cancels because they do not have sufficient on-chain balance") { f =>
    import f._

    val amount = Satoshi(1000)
    // bob must have enough on-chain balance to create opening tx
    val fundedBob = nodeWithOnChainBalance(bob, Satoshi(0))
    val (aliceSwap, bobSwap) = swapActors(alice, fundedBob)
    val shortChannelId = connectNodes(alice, fundedBob)

    // swap in sender (bob) requests a swap in with swap in receiver (alice)
    bobSwap.swapRegister ! SwapRequested(bobSwap.cli.ref.toTyped, Maker, amount, shortChannelId, None)
    val swap = bobSwap.cli.expectMsgType[SwapOpened]

    // both parties publish that the swap was canceled because bob could not fund the opening tx
    assert(aliceSwap.swapEvents.expectMsgType[Canceled].reason == PeerCanceled(swap.swapId,OpeningFundingFailed(swap.swapId, new RuntimeException("insufficient funds")).toString).toString)
    assert(bobSwap.swapEvents.expectMsgType[Canceled].reason == OpeningFundingFailed(swap.swapId, new RuntimeException("insufficient funds")).toString)

    // both parties have no pending swaps
    bobSwap.swapRegister ! ListPendingSwaps(bobSwap.cli.ref.toTyped)
    assert(bobSwap.cli.expectMsgType[Iterable[Status]].isEmpty)
    aliceSwap.swapRegister ! ListPendingSwaps(aliceSwap.cli.ref.toTyped)
    assert(aliceSwap.cli.expectMsgType[Iterable[Status]].isEmpty)
  }

}
