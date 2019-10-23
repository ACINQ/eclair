/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.payment

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestFSMRef, TestKit, TestProbe}
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{Block, Crypto, DeterministicWallet, Satoshi, Transaction}
import fr.acinq.eclair.TestConstants.{TestFeeEstimator, defaultBlockHeight}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.fee.FeeratesPerKw
import fr.acinq.eclair.channel.Commitments
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.MultiPartPaymentLifecycle._
import fr.acinq.eclair.payment.PaymentInitiator.SendPaymentConfig
import fr.acinq.eclair.payment.PaymentLifecycle.SendPayment
import fr.acinq.eclair.payment.PaymentSent.PartialPayment
import fr.acinq.eclair.payment.Relayer.{GetOutgoingChannels, OutgoingChannel, OutgoingChannels}
import fr.acinq.eclair.router._
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.transactions.Transactions.CommitTx
import fr.acinq.eclair.wire.Onion.createMultiPartPayload
import fr.acinq.eclair.wire.{ChannelUpdate, PaymentTimeout}
import org.scalatest.{Outcome, Tag, fixture}
import scodec.bits.ByteVector

import scala.concurrent.duration._
import scala.util.Random

/**
 * Created by t-bast on 18/07/2019.
 */

class MultiPartPaymentLifecycleSpec extends TestKit(ActorSystem("test")) with fixture.FunSuiteLike {

  import MultiPartPaymentLifecycleSpec._

  case class FixtureParam(paymentId: UUID,
                          nodeParams: NodeParams,
                          payFsm: TestFSMRef[MultiPartPaymentLifecycle.State, MultiPartPaymentLifecycle.Data, MultiPartPaymentLifecycle],
                          router: TestProbe,
                          relayer: TestProbe,
                          sender: TestProbe,
                          childPayFsm: TestProbe,
                          eventListener: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val id = UUID.randomUUID()
    val cfg = SendPaymentConfig(id, id, Some("42"), paymentHash, b, None, storeInDb = true, publishEvent = true)
    val nodeParams = TestConstants.Alice.nodeParams
    nodeParams.onChainFeeConf.feeEstimator.asInstanceOf[TestFeeEstimator].setFeerate(FeeratesPerKw.single(500))
    val (childPayFsm, router, relayer, sender, eventListener) = (TestProbe(), TestProbe(), TestProbe(), TestProbe(), TestProbe())
    class TestMultiPartPaymentLifecycle extends MultiPartPaymentLifecycle(nodeParams, cfg, relayer.ref, router.ref, TestProbe().ref) {
      override def spawnChildPaymentFsm(childId: UUID): ActorRef = childPayFsm.ref
    }
    val paymentHandler = TestFSMRef(new TestMultiPartPaymentLifecycle().asInstanceOf[MultiPartPaymentLifecycle])
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])
    withFixture(test.toNoArgTest(FixtureParam(id, nodeParams, paymentHandler, router, relayer, sender, childPayFsm, eventListener)))
  }

  def initPayment(f: FixtureParam, request: SendMultiPartPayment, networkStats: NetworkStats, localChannels: OutgoingChannels): Unit = {
    import f._
    sender.send(payFsm, request)
    router.expectMsg(GetNetworkStats)
    router.send(payFsm, GetNetworkStatsResponse(Some(networkStats)))
    relayer.expectMsg(GetOutgoingChannels())
    relayer.send(payFsm, localChannels)
  }

  def waitUntilAmountSent(f: FixtureParam, amount: MilliSatoshi): Unit = {
    Iterator.iterate(0 msat)(sent => {
      sent + f.childPayFsm.expectMsgType[SendPayment].finalPayload.amount
    }).takeWhile(sent => sent < amount)
  }

  test("get network statistics and usable balances before paying") { f =>
    import f._

    assert(payFsm.stateName === PAYMENT_INIT)
    val payment = SendMultiPartPayment(paymentHash, randomBytes32, b, 1500 * 1000 msat, expiry, 1)
    sender.send(payFsm, payment)
    router.expectMsg(GetNetworkStats)
    router.send(payFsm, GetNetworkStatsResponse(Some(emptyStats)))
    relayer.expectMsg(GetOutgoingChannels())
    awaitCond(payFsm.stateName === PAYMENT_IN_PROGRESS)
    assert(payFsm.stateData.asInstanceOf[PaymentProgress].networkStats === Some(emptyStats))
  }

  test("get network statistics not available") { f =>
    import f._

    assert(payFsm.stateName === PAYMENT_INIT)
    val payment = SendMultiPartPayment(paymentHash, randomBytes32, b, 2500 * 1000 msat, expiry, 1)
    sender.send(payFsm, payment)
    router.expectMsg(GetNetworkStats)
    router.send(payFsm, GetNetworkStatsResponse(None))
    // If network stats aren't available we'll use local channel balance information instead.
    // We should ask the router to compute statistics (for next payment attempts).
    router.expectMsg(TickComputeNetworkStats)
    relayer.expectMsg(GetOutgoingChannels())
    awaitCond(payFsm.stateName === PAYMENT_IN_PROGRESS)
    assert(payFsm.stateData.asInstanceOf[PaymentProgress].networkStats === None)

    relayer.send(payFsm, localChannels())
    waitUntilAmountSent(f, payment.totalAmount)
    val payments = payFsm.stateData.asInstanceOf[PaymentProgress].pending.values
    assert(payments.size > 1)
  }

  test("send to peer node via multiple channels") { f =>
    import f._
    val payment = SendMultiPartPayment(paymentHash, randomBytes32, b, 2000 * 1000 msat, expiry, 1)
    // Network statistics should be ignored when sending to peer.
    initPayment(f, payment, emptyStats, localChannels(0))

    // The payment should be split in two, using direct channels with b.
    childPayFsm.expectMsgAllOf(
      SendPayment(paymentHash, b, createMultiPartPayload(1000 * 1000 msat, payment.totalAmount, expiry, payment.paymentSecret), 1, routePrefix = Seq(Hop(nodeParams.nodeId, b, channelUpdate_ab_1))),
      SendPayment(paymentHash, b, createMultiPartPayload(1000 * 1000 msat, payment.totalAmount, expiry, payment.paymentSecret), 1, routePrefix = Seq(Hop(nodeParams.nodeId, b, channelUpdate_ab_2)))
    )
    childPayFsm.expectNoMsg(50 millis)
    val childIds = payFsm.stateData.asInstanceOf[PaymentProgress].pending.keys.toSeq
    assert(childIds.length === 2)

    val pp1 = PartialPayment(childIds.head, 1000 * 1000 msat, 0 msat, randomBytes32, None)
    val pp2 = PartialPayment(childIds(1), 1000 * 1000 msat, 0 msat, randomBytes32, None)
    childPayFsm.send(payFsm, PaymentSent(childIds.head, paymentHash, paymentPreimage, Seq(pp1)))
    childPayFsm.send(payFsm, PaymentSent(childIds(1), paymentHash, paymentPreimage, Seq(pp2)))
    val expectedMsg = PaymentSent(paymentId, paymentHash, paymentPreimage, Seq(pp1, pp2))
    sender.expectMsg(expectedMsg)
    eventListener.expectMsg(expectedMsg)
  }

  test("send to peer node via single big channel") { f =>
    import f._
    val payment = SendMultiPartPayment(paymentHash, randomBytes32, b, 1000 * 1000 msat, expiry, 1)
    // Network statistics should be ignored when sending to peer (otherwise we should have split into multiple payments).
    initPayment(f, payment, emptyStats.copy(capacity = Stats(Seq(100), d => Satoshi(d.toLong))), localChannels(0))
    childPayFsm.expectMsg(SendPayment(paymentHash, b, createMultiPartPayload(payment.totalAmount, payment.totalAmount, expiry, payment.paymentSecret), 1, routePrefix = Seq(Hop(nodeParams.nodeId, b, channelUpdate_ab_1))))
    childPayFsm.expectNoMsg(50 millis)
  }

  test("send to peer node via remote channels") { f =>
    import f._
    // d only has a single channel with capacity 1000 sat, we try to send more.
    val payment = SendMultiPartPayment(paymentHash, randomBytes32, d, 2000 * 1000 msat, expiry, 1)
    val testChannels = localChannels()
    val balanceToTarget = testChannels.channels.filter(_.nextNodeId == d).map(_.commitments.availableBalanceForSend).sum
    assert(balanceToTarget < (1000 * 1000).msat) // the commit tx fee prevents us from completely emptying our channel
    initPayment(f, payment, emptyStats.copy(capacity = Stats(Seq(500), d => Satoshi(d.toLong))), testChannels)
    waitUntilAmountSent(f, payment.totalAmount)
    val payments = payFsm.stateData.asInstanceOf[PaymentProgress].pending.values
    assert(payments.size > 1)
    val directPayments = payments.filter(p => p.routePrefix.head.nextNodeId == d)
    assert(directPayments.size === 1)
    assert(directPayments.head.finalPayload.amount === balanceToTarget)
  }

  test("send to remote node without splitting") { f =>
    import f._
    val payment = SendMultiPartPayment(paymentHash, randomBytes32, e, 300 * 1000 msat, expiry, 1)
    initPayment(f, payment, emptyStats.copy(capacity = Stats(Seq(1500), d => Satoshi(d.toLong))), localChannels())
    waitUntilAmountSent(f, payment.totalAmount)
    payFsm.stateData.asInstanceOf[PaymentProgress].pending.foreach {
      case (id, payment) => childPayFsm.send(payFsm, PaymentSent(id, paymentHash, paymentPreimage, Seq(PartialPayment(id, payment.finalPayload.amount, 5 msat, randomBytes32, None))))
    }

    val result = sender.expectMsgType[PaymentSent]
    assert(result.id === paymentId)
    assert(result.amount === payment.totalAmount)
    assert(result.parts.length === 1)
  }

  test("send to remote node via multiple channels") { f =>
    import f._
    val payment = SendMultiPartPayment(paymentHash, randomBytes32, e, 3200 * 1000 msat, expiry, 3)
    // A network capacity of 1000 sat should split the payment in at least 3 parts.
    initPayment(f, payment, emptyStats.copy(capacity = Stats(Seq(1000), d => Satoshi(d.toLong))), localChannels())

    val payments = Iterator.iterate(0 msat)(sent => {
      val child = childPayFsm.expectMsgType[SendPayment]
      assert(child.paymentHash === paymentHash)
      assert(child.targetNodeId === e)
      assert(child.maxAttempts === 3)
      assert(child.finalPayload.expiry === expiry)
      assert(child.finalPayload.paymentSecret === Some(payment.paymentSecret))
      assert(child.finalPayload.totalAmount === payment.totalAmount)
      assert(child.routePrefix.length === 1 && child.routePrefix.head.nodeId === nodeParams.nodeId)
      assert(sent + child.finalPayload.amount <= payment.totalAmount)
      sent + child.finalPayload.amount
    }).toSeq.takeWhile(sent => sent != payment.totalAmount)
    assert(payments.length > 2)
    assert(payments.length < 10)
    childPayFsm.expectNoMsg(50 millis)

    val pending = payFsm.stateData.asInstanceOf[PaymentProgress].pending
    val partialPayments = pending.map {
      case (id, payment) => PartialPayment(id, payment.finalPayload.amount, 1 msat, randomBytes32, Some(hop_ac_1 :: hop_ab_2 :: Nil))
    }
    partialPayments.foreach(pp => childPayFsm.send(payFsm, PaymentSent(pp.id, paymentHash, paymentPreimage, Seq(pp))))
    val result = sender.expectMsgType[PaymentSent]
    assert(result.id === paymentId)
    assert(result.paymentHash === paymentHash)
    assert(result.paymentPreimage === paymentPreimage)
    assert(result.parts === partialPayments)
    assert(result.amount === (3200 * 1000).msat)
    assert(result.feesPaid === partialPayments.map(_.feesPaid).sum)
  }

  test("send to remote node via single big channel") { f =>
    import f._
    val payment = SendMultiPartPayment(paymentHash, randomBytes32, e, 3500 * 1000 msat, expiry, 3)
    // When splitting inside a channel, we need to take the fees of the commit tx into account (multiple outgoing HTLCs
    // will increase the size of the commit tx and thus its fee.
    val feeRatePerKw = 100
    // A network capacity of 1500 sat should split the payment in at least 2 parts.
    // We have a single big channel inside which we'll send multiple payments.
    val localChannel = OutgoingChannels(Seq(OutgoingChannel(b, channelUpdate_ab_1, makeCommitments(5000 * 1000 msat, feeRatePerKw))))
    initPayment(f, payment, emptyStats.copy(capacity = Stats(Seq(1500), d => Satoshi(d.toLong))), localChannel)
    waitUntilAmountSent(f, payment.totalAmount)

    val pending = payFsm.stateData.asInstanceOf[PaymentProgress].pending
    assert(pending.size >= 2)
    val partialPayments = pending.map {
      case (id, payment) => PartialPayment(id, payment.finalPayload.amount, 1 msat, randomBytes32, None)
    }
    partialPayments.foreach(pp => childPayFsm.send(payFsm, PaymentSent(pp.id, paymentHash, paymentPreimage, Seq(pp))))
    val result = sender.expectMsgType[PaymentSent]
    assert(result.id === paymentId)
    assert(result.paymentHash === paymentHash)
    assert(result.paymentPreimage === paymentPreimage)
    assert(result.parts === partialPayments)
    assert(result.amount === (3500 * 1000).msat)
    assert(result.feesPaid === partialPayments.map(_.feesPaid).sum)
  }

  test("split fees between child payments") { f =>
    import f._
    val routeParams = RouteParams(randomize = false, 100 msat, 0.05, 20, CltvExpiryDelta(144), None)
    val payment = SendMultiPartPayment(paymentHash, randomBytes32, e, 3000 * 1000 msat, expiry, 3, routeParams = Some(routeParams))
    initPayment(f, payment, emptyStats.copy(capacity = Stats(Seq(1000), d => Satoshi(d.toLong))), localChannels())
    waitUntilAmountSent(f, 3000 * 1000 msat)

    val pending = payFsm.stateData.asInstanceOf[PaymentProgress].pending
    assert(pending.size >= 2)
    pending.foreach {
      case (_, p) =>
        assert(p.routeParams.get.maxFeeBase < 50.msat)
        assert(p.routeParams.get.maxFeePct == 0.05) // fee percent doesn't need to change
    }
  }

  test("skip empty channels") { f =>
    import f._
    val payment = SendMultiPartPayment(paymentHash, randomBytes32, e, 3000 * 1000 msat, expiry, 3)
    val testChannels = localChannels()
    val testChannels1 = testChannels.copy(channels = testChannels.channels ++ Seq(
      OutgoingChannel(b, channelUpdate_ab_1.copy(shortChannelId = ShortChannelId(42)), makeCommitments(0 msat, 10)),
      OutgoingChannel(e, channelUpdate_ab_1.copy(shortChannelId = ShortChannelId(43)), makeCommitments(0 msat, 10)
      )))
    initPayment(f, payment, emptyStats.copy(capacity = Stats(Seq(1000), d => Satoshi(d.toLong))), testChannels1)
    waitUntilAmountSent(f, payment.totalAmount)
    payFsm.stateData.asInstanceOf[PaymentProgress].pending.foreach {
      case (id, payment) => childPayFsm.send(payFsm, PaymentSent(id, paymentHash, paymentPreimage, Seq(PartialPayment(id, payment.finalPayload.amount, 5 msat, randomBytes32, None))))
    }

    val result = sender.expectMsgType[PaymentSent]
    assert(result.id === paymentId)
    assert(result.amount === payment.totalAmount)
  }

  test("retry after error") { f =>
    import f._
    val payment = SendMultiPartPayment(paymentHash, randomBytes32, e, 3000 * 1000 msat, expiry, 3)
    val testChannels = localChannels()
    // A network capacity of 1000 sat should split the payment in at least 3 parts.
    initPayment(f, payment, emptyStats.copy(capacity = Stats(Seq(1000), d => Satoshi(d.toLong))), testChannels)
    waitUntilAmountSent(f, payment.totalAmount)
    val pending = payFsm.stateData.asInstanceOf[PaymentProgress].pending
    val childIds = pending.keys.toSeq
    assert(pending.size > 2)

    // Simulate two failures.
    val failures = Seq(LocalFailure(new RuntimeException("418 I'm a teapot")), UnreadableRemoteFailure(Nil))
    childPayFsm.send(payFsm, PaymentFailed(childIds.head, paymentHash, failures.slice(0, 1)))
    childPayFsm.send(payFsm, PaymentFailed(childIds(1), paymentHash, failures.slice(1, 2)))
    // We should ask for updated balance to take into account pending payments.
    relayer.expectMsg(GetOutgoingChannels())
    relayer.send(payFsm, testChannels.copy(channels = testChannels.channels.dropRight(2)))

    // New payments should be sent that match the failed amount.
    waitUntilAmountSent(f, pending(childIds.head).finalPayload.amount + pending(childIds(1)).finalPayload.amount)
    assert(payFsm.stateData.asInstanceOf[PaymentProgress].failures.toSet === failures.toSet)
  }

  test("cannot send (not enough capacity on local channels)") { f =>
    import f._
    val payment = SendMultiPartPayment(paymentHash, randomBytes32, e, 3000 * 1000 msat, expiry, 3)
    initPayment(f, payment, emptyStats.copy(capacity = Stats(Seq(1000), d => Satoshi(d.toLong))), OutgoingChannels(Seq(
      OutgoingChannel(b, channelUpdate_ab_1, makeCommitments(1000 * 1000 msat, 10)),
      OutgoingChannel(c, channelUpdate_ac_2, makeCommitments(1000 * 1000 msat, 10)),
      OutgoingChannel(d, channelUpdate_ad_1, makeCommitments(1000 * 1000 msat, 10))))
    )
    val result = sender.expectMsgType[PaymentFailed]
    assert(result.id === paymentId)
    assert(result.paymentHash === paymentHash)
    assert(result.failures.length === 1)
    assert(result.failures.head.asInstanceOf[LocalFailure].t.getMessage === "balance is too low")
  }

  test("cannot send (fee rate too high)") { f =>
    import f._
    val payment = SendMultiPartPayment(paymentHash, randomBytes32, e, 2500 * 1000 msat, expiry, 3)
    initPayment(f, payment, emptyStats.copy(capacity = Stats(Seq(1000), d => Satoshi(d.toLong))), OutgoingChannels(Seq(
      OutgoingChannel(b, channelUpdate_ab_1, makeCommitments(1500 * 1000 msat, 1000)),
      OutgoingChannel(c, channelUpdate_ac_2, makeCommitments(1500 * 1000 msat, 1000)),
      OutgoingChannel(d, channelUpdate_ad_1, makeCommitments(1500 * 1000 msat, 1000))))
    )
    val result = sender.expectMsgType[PaymentFailed]
    assert(result.id === paymentId)
    assert(result.paymentHash === paymentHash)
    assert(result.failures.length === 1)
    assert(result.failures.head.asInstanceOf[LocalFailure].t.getMessage === "balance is too low")
  }

  test("payment timeout") { f =>
    import f._
    val payment = SendMultiPartPayment(paymentHash, randomBytes32, e, 3000 * 1000 msat, expiry, 5)
    initPayment(f, payment, emptyStats.copy(capacity = Stats(Seq(1000), d => Satoshi(d.toLong))), localChannels())
    waitUntilAmountSent(f, payment.totalAmount)
    val (childId1, _) = payFsm.stateData.asInstanceOf[PaymentProgress].pending.head

    // If we receive a timeout failure, we directly abort the payment instead of retrying.
    childPayFsm.send(payFsm, PaymentFailed(childId1, paymentHash, RemoteFailure(Nil, Sphinx.DecryptedFailurePacket(e, PaymentTimeout)) :: Nil))
    relayer.expectNoMsg(50 millis)
    awaitCond(payFsm.stateName === PAYMENT_ABORTED)
  }

  test("fail after too many attempts") { f =>
    import f._
    val payment = SendMultiPartPayment(paymentHash, randomBytes32, e, 3000 * 1000 msat, expiry, 2)
    initPayment(f, payment, emptyStats.copy(capacity = Stats(Seq(1000), d => Satoshi(d.toLong))), localChannels())
    waitUntilAmountSent(f, payment.totalAmount)
    val (childId1, childPayment1) = payFsm.stateData.asInstanceOf[PaymentProgress].pending.head

    // We retry one failure.
    val failures = Seq(UnreadableRemoteFailure(hop_ab_1 :: Nil), UnreadableRemoteFailure(hop_ac_1 :: hop_ab_2 :: Nil))
    childPayFsm.send(payFsm, PaymentFailed(childId1, paymentHash, failures.slice(0, 1)))
    relayer.expectMsg(GetOutgoingChannels())
    relayer.send(payFsm, localChannels())
    waitUntilAmountSent(f, childPayment1.finalPayload.amount)

    // But another failure occurs...
    val (childId2, _) = payFsm.stateData.asInstanceOf[PaymentProgress].pending.head
    childPayFsm.send(payFsm, PaymentFailed(childId2, paymentHash, failures.slice(1, 2)))
    relayer.expectNoMsg(50 millis)
    awaitCond(payFsm.stateName === PAYMENT_ABORTED)

    // And then all other payments time out.
    payFsm.stateData.asInstanceOf[PaymentAborted].pending.foreach(childId => childPayFsm.send(payFsm, PaymentFailed(childId, paymentHash, Nil)))
    val result = sender.expectMsgType[PaymentFailed]
    assert(result.id === paymentId)
    assert(result.paymentHash === paymentHash)
    assert(result.failures.length === 3)
    assert(result.failures.slice(0, 2) === failures)
    assert(result.failures.last.asInstanceOf[LocalFailure].t.getMessage === "payment attempts exhausted without success")
  }

  test("receive partial failure after success (recipient spec violation)") { f =>
    import f._
    val payment = SendMultiPartPayment(paymentHash, randomBytes32, e, 4000 * 1000 msat, expiry, 2)
    initPayment(f, payment, emptyStats.copy(capacity = Stats(Seq(1500), d => Satoshi(d.toLong))), localChannels())
    waitUntilAmountSent(f, payment.totalAmount)
    val pending = payFsm.stateData.asInstanceOf[PaymentProgress].pending

    // If one of the payments succeeds, the recipient MUST succeed them all: we can consider the whole payment succeeded.
    val (id1, payment1) = pending.head
    childPayFsm.send(payFsm, PaymentSent(id1, paymentHash, paymentPreimage, Seq(PartialPayment(id1, payment1.finalPayload.amount, 10 msat, randomBytes32, None))))
    awaitCond(payFsm.stateName === PAYMENT_SUCCEEDED)

    // A partial failure should simply be ignored.
    val (id2, payment2) = pending.tail.head
    childPayFsm.send(payFsm, PaymentFailed(id2, paymentHash, Nil))

    pending.tail.tail.foreach {
      case (id, payment) => childPayFsm.send(payFsm, PaymentSent(id, paymentHash, paymentPreimage, Seq(PartialPayment(id, payment.finalPayload.amount, 10 msat, randomBytes32, None))))
    }
    val result = sender.expectMsgType[PaymentSent]
    assert(result.id === paymentId)
    assert(result.amount === payment.totalAmount - payment2.finalPayload.amount)
  }

  test("receive partial success after abort (recipient spec violation)") { f =>
    import f._
    val payment = SendMultiPartPayment(paymentHash, randomBytes32, e, 5000 * 1000 msat, expiry, 1)
    initPayment(f, payment, emptyStats.copy(capacity = Stats(Seq(2000), d => Satoshi(d.toLong))), localChannels())
    waitUntilAmountSent(f, payment.totalAmount)
    val pending = payFsm.stateData.asInstanceOf[PaymentProgress].pending

    // One of the payments failed and we configured maxAttempts = 1, so we abort.
    val (id1, _) = pending.head
    childPayFsm.send(payFsm, PaymentFailed(id1, paymentHash, Nil))
    awaitCond(payFsm.stateName === PAYMENT_ABORTED)

    // The in-flight HTLC set doesn't pay the full amount, so the recipient MUST not fulfill any of those.
    // But if he does, it's too bad for him as we have obtained a cheaper proof of payment.
    val (id2, payment2) = pending.tail.head
    childPayFsm.send(payFsm, PaymentSent(id2, paymentHash, paymentPreimage, Seq(PartialPayment(id2, payment2.finalPayload.amount, 5 msat, randomBytes32, None))))
    awaitCond(payFsm.stateName === PAYMENT_SUCCEEDED)

    // Even if all other child payments fail, we obtained the preimage so the payment is a success from our point of view.
    pending.tail.tail.foreach {
      case (id, _) => childPayFsm.send(payFsm, PaymentFailed(id, paymentHash, Nil))
    }
    val result = sender.expectMsgType[PaymentSent]
    assert(result.id === paymentId)
    assert(result.amount === payment2.finalPayload.amount)
    assert(result.feesPaid === 5.msat)
  }

  test("split payment", Tag("fuzzy")) { f =>
    // The fees for a single HTLC will be 100 * 172 / 1000 = 17 satoshis.
    val testChannels = localChannels(100)
    for (_ <- 1 to 100) {
      // We have a total of 6500 satoshis across all channels. We try to send lower amounts to take fees into account.
      val toSend = ((1 + Random.nextInt(3500)) * 1000).msat
      val networkStats = emptyStats.copy(capacity = Stats(Seq(400 + Random.nextInt(1600)), d => Satoshi(d.toLong)))
      val routeParams = RouteParams(randomize = true, Random.nextInt(1000).msat, Random.nextInt(10).toDouble / 100, 20, CltvExpiryDelta(144), None)
      val request = SendMultiPartPayment(paymentHash, randomBytes32, e, toSend, CltvExpiry(561), 1, Nil, Some(routeParams))
      val fuzzParams = s"(sending $toSend with network capacity ${networkStats.capacity.percentile75.toMilliSatoshi}, fee base ${routeParams.maxFeeBase} and fee percentage ${routeParams.maxFeePct})"
      val (remaining, payments) = splitPayment(f.nodeParams, toSend, testChannels.channels, Some(networkStats), request, randomize = true)
      assert(remaining === 0.msat, fuzzParams)
      assert(payments.nonEmpty, fuzzParams)
      assert(payments.map(_.finalPayload.amount).sum === toSend, fuzzParams)
      // Verify that we're not generating tiny HTLCs.
      assert(payments.forall(_.finalPayload.amount > 50.msat), fuzzParams)
    }
  }

}

object MultiPartPaymentLifecycleSpec {

  val paymentPreimage = randomBytes32
  val paymentHash = Crypto.sha256(paymentPreimage)
  val expiry = CltvExpiry(1105)

  /**
   * We simulate a multi-part-friendly network:
   * .-----> b -------.
   * |                |
   * a ----> c -----> e
   * |                |
   * '-----> d -------'
   * where a has multiple channels with each of his peers.
   */

  val a :: b :: c :: d :: e :: Nil = Seq.fill(5)(PrivateKey(randomBytes32).publicKey)
  val channelId_ab_1 = ShortChannelId(1)
  val channelId_ab_2 = ShortChannelId(2)
  val channelId_ac_1 = ShortChannelId(11)
  val channelId_ac_2 = ShortChannelId(12)
  val channelId_ac_3 = ShortChannelId(13)
  val channelId_ad_1 = ShortChannelId(21)
  val defaultChannelUpdate = ChannelUpdate(randomBytes64, Block.RegtestGenesisBlock.hash, ShortChannelId(0), 0, 1, 0, CltvExpiryDelta(12), 1 msat, 0 msat, 0, Some(2000 * 1000 msat))
  val channelUpdate_ab_1 = defaultChannelUpdate.copy(shortChannelId = channelId_ab_1, cltvExpiryDelta = CltvExpiryDelta(4), feeBaseMsat = 100 msat, feeProportionalMillionths = 70)
  val channelUpdate_ab_2 = defaultChannelUpdate.copy(shortChannelId = channelId_ab_2, cltvExpiryDelta = CltvExpiryDelta(4), feeBaseMsat = 100 msat, feeProportionalMillionths = 70)
  val channelUpdate_ac_1 = defaultChannelUpdate.copy(shortChannelId = channelId_ac_1, cltvExpiryDelta = CltvExpiryDelta(5), feeBaseMsat = 150 msat, feeProportionalMillionths = 40)
  val channelUpdate_ac_2 = defaultChannelUpdate.copy(shortChannelId = channelId_ac_2, cltvExpiryDelta = CltvExpiryDelta(5), feeBaseMsat = 150 msat, feeProportionalMillionths = 40)
  val channelUpdate_ac_3 = defaultChannelUpdate.copy(shortChannelId = channelId_ac_3, cltvExpiryDelta = CltvExpiryDelta(5), feeBaseMsat = 150 msat, feeProportionalMillionths = 40)
  val channelUpdate_ad_1 = defaultChannelUpdate.copy(shortChannelId = channelId_ad_1, cltvExpiryDelta = CltvExpiryDelta(6), feeBaseMsat = 200 msat, feeProportionalMillionths = 50)

  // With a fee rate of 10, the fees for a single HTLC will be 10 * 172 / 1000 = 1 satoshi.
  def localChannels(feeRatePerKw: Long = 10): OutgoingChannels = OutgoingChannels(Seq(
    OutgoingChannel(b, channelUpdate_ab_1, makeCommitments(1000 * 1000 msat, feeRatePerKw)),
    OutgoingChannel(b, channelUpdate_ab_2, makeCommitments(1500 * 1000 msat, feeRatePerKw)),
    OutgoingChannel(c, channelUpdate_ac_1, makeCommitments(500 * 1000 msat, feeRatePerKw)),
    OutgoingChannel(c, channelUpdate_ac_2, makeCommitments(1000 * 1000 msat, feeRatePerKw)),
    OutgoingChannel(c, channelUpdate_ac_3, makeCommitments(1500 * 1000 msat, feeRatePerKw)),
    OutgoingChannel(d, channelUpdate_ad_1, makeCommitments(1000 * 1000 msat, feeRatePerKw))))

  val hop_ab_1 = Hop(a, b, channelUpdate_ab_1)
  val hop_ab_2 = Hop(a, b, channelUpdate_ab_2)
  val hop_ac_1 = Hop(a, c, channelUpdate_ac_1)

  val emptyStats = NetworkStats(0, 0, Stats(Seq(0), d => Satoshi(d.toLong)), Stats(Seq(0), d => CltvExpiryDelta(d.toInt)), Stats(Seq(0), d => MilliSatoshi(d.toLong)), Stats(Seq(0), d => d.toLong))

  def makeCommitments(canSend: MilliSatoshi, feeRatePerKw: Long): Commitments = {
    import fr.acinq.eclair.channel._
    import fr.acinq.eclair.crypto.ShaChain
    // We are only interested in availableBalanceForSend so we can put dummy values in most places.
    val localParams = LocalParams(randomKey.publicKey, DeterministicWallet.KeyPath(Seq(42L)), 0 sat, UInt64(50000000), 0 sat, 1 msat, CltvExpiryDelta(144), 50, isFunder = true, ByteVector.empty, ByteVector.empty, ByteVector.empty)
    val remoteParams = RemoteParams(randomKey.publicKey, 0 sat, UInt64(5000000), 0 sat, 1 msat, CltvExpiryDelta(144), 50, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, ByteVector.empty, ByteVector.empty)
    val commitmentInput = Funding.makeFundingInputInfo(randomBytes32, 0, canSend.truncateToSatoshi, randomKey.publicKey, remoteParams.fundingPubKey)
    Commitments(
      ChannelVersion.STANDARD,
      localParams,
      remoteParams,
      channelFlags = 0x01.toByte,
      LocalCommit(0, CommitmentSpec(Set.empty, feeRatePerKw, canSend, 0 msat), PublishableTxs(CommitTx(commitmentInput, Transaction(2, Nil, Nil, 0)), Nil)),
      RemoteCommit(0, CommitmentSpec(Set.empty, feeRatePerKw, 0 msat, canSend), randomBytes32, randomKey.publicKey),
      LocalChanges(Nil, Nil, Nil),
      RemoteChanges(Nil, Nil, Nil),
      localNextHtlcId = 1,
      remoteNextHtlcId = 1,
      originChannels = Map.empty,
      remoteNextCommitInfo = Right(randomKey.publicKey),
      commitInput = commitmentInput,
      remotePerCommitmentSecrets = ShaChain.init,
      channelId = randomBytes32)
  }

}