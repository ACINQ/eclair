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

package fr.acinq.eclair.integration

import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import akka.actor.ActorRef
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{Block, ByteVector32, SatoshiLong}
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService.BitcoinReq
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{Watch, WatchFundingConfirmed}
import fr.acinq.eclair.channel.Channel.{BroadcastChannelUpdate, PeriodicRefresh}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx.DecryptedFailurePacket
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.db._
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.receive.MultiPartHandler.ReceivePayment
import fr.acinq.eclair.payment.relay.Relayer
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle.PreimageReceived
import fr.acinq.eclair.payment.send.PaymentInitiator.{SendPaymentRequest, SendTrampolinePaymentRequest}
import fr.acinq.eclair.router.Graph.WeightRatios
import fr.acinq.eclair.router.Router.{GossipDecision, PublicChannel}
import fr.acinq.eclair.router.{Announcements, AnnouncementsBatchValidationSpec, Router}
import fr.acinq.eclair.wire.protocol.{ChannelAnnouncement, ChannelUpdate, IncorrectOrUnknownPaymentDetails, NodeAnnouncement}
import fr.acinq.eclair.{CltvExpiryDelta, Kit, MilliSatoshiLong, ShortChannelId, randomBytes32}
import org.json4s.JsonAST.{JString, JValue}
import scodec.bits.ByteVector

import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/**
 * Created by PM on 15/03/2017.
 */

class PaymentIntegrationSpec extends IntegrationSpec {

  test("start eclair nodes") {
    instantiateEclairNode("A", ConfigFactory.parseMap(Map("eclair.node-alias" -> "A", "eclair.expiry-delta-blocks" -> 130, "eclair.server.port" -> 29730, "eclair.api.port" -> 28080, "eclair.channel-flags" -> 0).asJava).withFallback(commonFeatures).withFallback(commonConfig)) // A's channels are private
    instantiateEclairNode("B", ConfigFactory.parseMap(Map("eclair.node-alias" -> "B", "eclair.expiry-delta-blocks" -> 131, "eclair.server.port" -> 29731, "eclair.api.port" -> 28081, "eclair.trampoline-payments-enable" -> true).asJava).withFallback(commonFeatures).withFallback(commonConfig))
    instantiateEclairNode("C", ConfigFactory.parseMap(Map("eclair.node-alias" -> "C", "eclair.expiry-delta-blocks" -> 132, "eclair.server.port" -> 29732, "eclair.api.port" -> 28082, "eclair.trampoline-payments-enable" -> true).asJava).withFallback(withAnchorOutputs).withFallback(withWumbo).withFallback(commonConfig))
    instantiateEclairNode("D", ConfigFactory.parseMap(Map("eclair.node-alias" -> "D", "eclair.expiry-delta-blocks" -> 133, "eclair.server.port" -> 29733, "eclair.api.port" -> 28083, "eclair.trampoline-payments-enable" -> true).asJava).withFallback(commonFeatures).withFallback(commonConfig))
    instantiateEclairNode("E", ConfigFactory.parseMap(Map("eclair.node-alias" -> "E", "eclair.expiry-delta-blocks" -> 134, "eclair.server.port" -> 29734, "eclair.api.port" -> 28084).asJava).withFallback(withAnchorOutputs).withFallback(commonConfig))
    instantiateEclairNode("F", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F", "eclair.expiry-delta-blocks" -> 135, "eclair.server.port" -> 29735, "eclair.api.port" -> 28085, "eclair.trampoline-payments-enable" -> true).asJava).withFallback(withWumbo).withFallback(commonConfig))
    instantiateEclairNode("G", ConfigFactory.parseMap(Map("eclair.node-alias" -> "G", "eclair.expiry-delta-blocks" -> 136, "eclair.server.port" -> 29736, "eclair.api.port" -> 28086, "eclair.fee-base-msat" -> 1010, "eclair.fee-proportional-millionths" -> 102, "eclair.trampoline-payments-enable" -> true).asJava).withFallback(commonConfig))
  }

  test("connect nodes") {
    //       ,--G--,
    //      /       \
    // A---B ------- C ==== D
    //      \       / \
    //       '--E--'   '--F

    val sender = TestProbe()
    val eventListener = TestProbe()
    nodes.values.foreach(_.system.eventStream.subscribe(eventListener.ref, classOf[ChannelStateChanged]))

    connect(nodes("A"), nodes("B"), 11000000 sat, 0 msat)
    connect(nodes("B"), nodes("C"), 2000000 sat, 0 msat)
    connect(nodes("C"), nodes("D"), 5000000 sat, 0 msat)
    connect(nodes("C"), nodes("D"), 5000000 sat, 0 msat)
    connect(nodes("C"), nodes("F"), 16000000 sat, 0 msat)
    connect(nodes("B"), nodes("E"), 10000000 sat, 0 msat)
    connect(nodes("E"), nodes("C"), 10000000 sat, 0 msat)
    connect(nodes("B"), nodes("G"), 16000000 sat, 0 msat)
    connect(nodes("G"), nodes("C"), 16000000 sat, 0 msat)

    val numberOfChannels = 9
    val channelEndpointsCount = 2 * numberOfChannels

    // we make sure all channels have set up their WatchConfirmed for the funding tx
    awaitCond({
      val watches = nodes.values.foldLeft(Set.empty[Watch[_]]) {
        case (watches, setup) =>
          setup.watcher !  ZmqWatcher.ListWatches(sender.ref)
          watches ++ sender.expectMsgType[Set[Watch[_]]]
      }
      watches.count(_.isInstanceOf[WatchFundingConfirmed]) == channelEndpointsCount
    }, max = 20 seconds, interval = 1 second)

    // confirming the funding tx
    generateBlocks(2)

    within(60 seconds) {
      var count = 0
      while (count < channelEndpointsCount) {
        if (eventListener.expectMsgType[ChannelStateChanged](60 seconds).currentState == NORMAL) count = count + 1
      }
    }
  }

  def awaitAnnouncements(subset: Map[String, Kit], nodes: Int, channels: Int, updates: Int): Unit = {
    val sender = TestProbe()
    subset.foreach {
      case (_, setup) =>
        awaitCond({
          sender.send(setup.router, Router.GetNodes)
          sender.expectMsgType[Iterable[NodeAnnouncement]].size == nodes
        }, max = 60 seconds, interval = 1 second)
        awaitCond({
          sender.send(setup.router, Router.GetChannels)
          sender.expectMsgType[Iterable[ChannelAnnouncement]].size == channels
        }, max = 60 seconds, interval = 1 second)
        awaitCond({
          sender.send(setup.router, Router.GetChannelUpdates)
          sender.expectMsgType[Iterable[ChannelUpdate]].size == updates
        }, max = 60 seconds, interval = 1 second)
    }
  }

  test("wait for network announcements") {
    // generating more blocks so that all funding txes are buried under at least 6 blocks
    generateBlocks(4)
    // A requires private channels, as a consequence:
    // - only A and B know about channel A-B (and there is no channel_announcement)
    // - A is not announced (no node_announcement)
    awaitAnnouncements(nodes.filterKeys(key => List("A", "B").contains(key)).toMap, 6, 8, 18)
    awaitAnnouncements(nodes.filterKeys(key => List("C", "D", "E", "G").contains(key)).toMap, 6, 8, 16)
  }

  test("wait for channels balance") {
    // Channels balance should now be available in the router
    val sender = TestProbe()
    val nodeId = nodes("C").nodeParams.nodeId
    sender.send(nodes("C").router, Router.GetRoutingState)
    val routingState = sender.expectMsgType[Router.RoutingState]
    val publicChannels = routingState.channels.filter(pc => Set(pc.ann.nodeId1, pc.ann.nodeId2).contains(nodeId))
    assert(publicChannels.nonEmpty)
    publicChannels.foreach(pc => assert(pc.meta_opt.map(m => m.balance1 > 0.msat || m.balance2 > 0.msat) === Some(true), pc))
  }

  test("send an HTLC A->D") {
    val sender = TestProbe()
    val amountMsat = 4200000.msat
    // first we retrieve a payment hash from D
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    // then we make the actual payment
    sender.send(nodes("A").paymentInitiator, SendPaymentRequest(amountMsat, pr.paymentHash, nodes("D").nodeParams.nodeId, fallbackFinalExpiryDelta = finalCltvExpiryDelta, routeParams = integrationTestRouteParams, maxAttempts = 1))
    val paymentId = sender.expectMsgType[UUID]
    val ps = sender.expectMsgType[PaymentSent]
    assert(ps.id == paymentId)
  }

  test("send an HTLC A->D with an invalid expiry delta for B") {
    val sender = TestProbe()
    // to simulate this, we will update B's relay params
    // first we find out the short channel id for channel B-C
    sender.send(nodes("B").router, Router.GetChannels)
    val shortIdBC = sender.expectMsgType[Iterable[ChannelAnnouncement]].find(c => Set(c.nodeId1, c.nodeId2) == Set(nodes("B").nodeParams.nodeId, nodes("C").nodeParams.nodeId)).get.shortChannelId
    // we also need the full commitment
    nodes("B").register ! Register.ForwardShortId(sender.ref, shortIdBC, CMD_GETINFO(ActorRef.noSender))
    val commitmentBC = sender.expectMsgType[RES_GETINFO].data.asInstanceOf[DATA_NORMAL].commitments
    // we then forge a new channel_update for B-C...
    val channelUpdateBC = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, nodes("B").nodeParams.privateKey, nodes("C").nodeParams.nodeId, shortIdBC, nodes("B").nodeParams.expiryDelta + 1, nodes("C").nodeParams.htlcMinimum, nodes("B").nodeParams.feeBase, nodes("B").nodeParams.feeProportionalMillionth, 500000000 msat)
    // ...and notify B's relayer
    nodes("B").system.eventStream.publish(LocalChannelUpdate(system.deadLetters, commitmentBC.channelId, shortIdBC, commitmentBC.remoteParams.nodeId, None, channelUpdateBC, commitmentBC))
    // we retrieve a payment hash from D
    val amountMsat = 4200000.msat
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    // then we make the actual payment, do not randomize the route to make sure we route through node B
    val sendReq = SendPaymentRequest(amountMsat, pr.paymentHash, nodes("D").nodeParams.nodeId, fallbackFinalExpiryDelta = finalCltvExpiryDelta, routeParams = integrationTestRouteParams, maxAttempts = 5)
    sender.send(nodes("A").paymentInitiator, sendReq)
    // A will receive an error from B that include the updated channel update, then will retry the payment
    val paymentId = sender.expectMsgType[UUID]
    val ps = sender.expectMsgType[PaymentSent]
    assert(ps.id == paymentId)

    def updateFor(n: PublicKey, pc: PublicChannel): Option[ChannelUpdate] = if (n == pc.ann.nodeId1) pc.update_1_opt else if (n == pc.ann.nodeId2) pc.update_2_opt else throw new IllegalArgumentException("this node is unrelated to this channel")

    awaitCond({
      // in the meantime, the router will have updated its state
      sender.send(nodes("A").router, Router.GetChannelsMap)
      // we then put everything back like before by asking B to refresh its channel update (this will override the one we created)
      val u_opt = updateFor(nodes("B").nodeParams.nodeId, sender.expectMsgType[Map[ShortChannelId, PublicChannel]](10 seconds).apply(channelUpdateBC.shortChannelId))
      u_opt.contains(channelUpdateBC)
    }, max = 30 seconds, interval = 1 seconds)

    // first let's wait 3 seconds to make sure the timestamp of the new channel_update will be strictly greater than the former
    sender.expectNoMsg(3 seconds)
    nodes("B").register ! Register.ForwardShortId(sender.ref, shortIdBC, BroadcastChannelUpdate(PeriodicRefresh))
    nodes("B").register ! Register.ForwardShortId(sender.ref, shortIdBC, CMD_GETINFO(ActorRef.noSender))
    val channelUpdateBC_new = sender.expectMsgType[RES_GETINFO].data.asInstanceOf[DATA_NORMAL].channelUpdate
    logger.info(s"channelUpdateBC=$channelUpdateBC")
    logger.info(s"channelUpdateBC_new=$channelUpdateBC_new")
    assert(channelUpdateBC_new.timestamp > channelUpdateBC.timestamp)
    assert(channelUpdateBC_new.cltvExpiryDelta == nodes("B").nodeParams.expiryDelta)
    awaitCond({
      sender.send(nodes("A").router, Router.GetChannelsMap)
      val u = updateFor(nodes("B").nodeParams.nodeId, sender.expectMsgType[Map[ShortChannelId, PublicChannel]].apply(channelUpdateBC.shortChannelId)).get
      u.cltvExpiryDelta == nodes("B").nodeParams.expiryDelta
    }, max = 30 seconds, interval = 1 second)
  }

  test("send an HTLC A->D with an amount greater than capacity of B-C") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D
    val amountMsat = 300000000.msat
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    // then we make the payment (B-C has a smaller capacity than A-B and C-D)
    val sendReq = SendPaymentRequest(amountMsat, pr.paymentHash, nodes("D").nodeParams.nodeId, fallbackFinalExpiryDelta = finalCltvExpiryDelta, routeParams = integrationTestRouteParams, maxAttempts = 5)
    sender.send(nodes("A").paymentInitiator, sendReq)
    // A will first receive an error from C, then retry and route around C: A->B->E->C->D
    sender.expectMsgType[UUID]
    sender.expectMsgType[PaymentSent] // the payment FSM will also reply to the sender after the payment is completed
  }

  test("send an HTLC A->D with an unknown payment hash") {
    val sender = TestProbe()
    val pr = SendPaymentRequest(100000000 msat, randomBytes32(), nodes("D").nodeParams.nodeId, routeParams = integrationTestRouteParams, maxAttempts = 5)
    sender.send(nodes("A").paymentInitiator, pr)

    // A will receive an error from D and won't retry
    val paymentId = sender.expectMsgType[UUID]
    val failed = sender.expectMsgType[PaymentFailed]
    assert(failed.id == paymentId)
    assert(failed.paymentHash === pr.paymentHash)
    assert(failed.failures.size === 1)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e === DecryptedFailurePacket(nodes("D").nodeParams.nodeId, IncorrectOrUnknownPaymentDetails(100000000 msat, getBlockCount)))
  }

  test("send an HTLC A->D with a lower amount than requested") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D for 2 mBTC
    val amountMsat = 200000000.msat
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]

    // A send payment of only 1 mBTC
    val sendReq = SendPaymentRequest(100000000 msat, pr.paymentHash, nodes("D").nodeParams.nodeId, fallbackFinalExpiryDelta = finalCltvExpiryDelta, routeParams = integrationTestRouteParams, maxAttempts = 5)
    sender.send(nodes("A").paymentInitiator, sendReq)

    // A will first receive an IncorrectPaymentAmount error from D
    val paymentId = sender.expectMsgType[UUID]
    val failed = sender.expectMsgType[PaymentFailed]
    assert(failed.id == paymentId)
    assert(failed.paymentHash === pr.paymentHash)
    assert(failed.failures.size === 1)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e === DecryptedFailurePacket(nodes("D").nodeParams.nodeId, IncorrectOrUnknownPaymentDetails(100000000 msat, getBlockCount)))
  }

  test("send an HTLC A->D with too much overpayment") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D for 2 mBTC
    val amountMsat = 200000000.msat
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]

    // A send payment of 6 mBTC
    val sendReq = SendPaymentRequest(600000000 msat, pr.paymentHash, nodes("D").nodeParams.nodeId, fallbackFinalExpiryDelta = finalCltvExpiryDelta, routeParams = integrationTestRouteParams, maxAttempts = 5)
    sender.send(nodes("A").paymentInitiator, sendReq)

    // A will first receive an IncorrectPaymentAmount error from D
    val paymentId = sender.expectMsgType[UUID]
    val failed = sender.expectMsgType[PaymentFailed]
    assert(paymentId == failed.id)
    assert(failed.paymentHash === pr.paymentHash)
    assert(failed.failures.size === 1)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e === DecryptedFailurePacket(nodes("D").nodeParams.nodeId, IncorrectOrUnknownPaymentDetails(600000000 msat, getBlockCount)))
  }

  test("send an HTLC A->D with a reasonable overpayment") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D for 2 mBTC
    val amountMsat = 200000000.msat
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]

    // A send payment of 3 mBTC, more than asked but it should still be accepted
    val sendReq = SendPaymentRequest(300000000 msat, pr.paymentHash, nodes("D").nodeParams.nodeId, fallbackFinalExpiryDelta = finalCltvExpiryDelta, routeParams = integrationTestRouteParams, maxAttempts = 5)
    sender.send(nodes("A").paymentInitiator, sendReq)
    sender.expectMsgType[UUID]
  }

  test("send multiple HTLCs A->D with a failover when a channel gets exhausted") {
    val sender = TestProbe()
    // there are two C-D channels with 5000000 sat, so we should be able to make 7 payments worth 1000000 sat each
    for (_ <- 0 until 7) {
      val amountMsat = 1000000000.msat
      sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 payment"))
      val pr = sender.expectMsgType[PaymentRequest]

      val sendReq = SendPaymentRequest(amountMsat, pr.paymentHash, nodes("D").nodeParams.nodeId, fallbackFinalExpiryDelta = finalCltvExpiryDelta, routeParams = integrationTestRouteParams, maxAttempts = 5)
      sender.send(nodes("A").paymentInitiator, sendReq)
      sender.expectMsgType[UUID]
      sender.expectMsgType[PaymentSent] // the payment FSM will also reply to the sender after the payment is completed
    }
  }

  test("send an HTLC A->B->G->C using heuristics to select the route") {
    val sender = TestProbe()
    // first we retrieve a payment hash from C
    val amountMsat = 2000.msat
    sender.send(nodes("C").paymentHandler, ReceivePayment(Some(amountMsat), "Change from coffee"))
    val pr = sender.expectMsgType[PaymentRequest]

    // the payment is requesting to use a capacity-optimized route which will select node G even though it's a bit more expensive
    sender.send(nodes("A").paymentInitiator, SendPaymentRequest(amountMsat, pr.paymentHash, nodes("C").nodeParams.nodeId, maxAttempts = 1, fallbackFinalExpiryDelta = finalCltvExpiryDelta, routeParams = integrationTestRouteParams.map(_.copy(ratios = WeightRatios(0, 0, 0, 1, 0 msat, 0)))))
    sender.expectMsgType[UUID]

    sender.expectMsgType[PaymentEvent] match {
      case PaymentFailed(_, _, failures, _) => failures == Seq.empty // if something went wrong fail with a hint
      case PaymentSent(_, _, _, _, _, part :: Nil) => part.route.getOrElse(Nil).exists(_.nodeId == nodes("G").nodeParams.nodeId)
      case e => fail(s"unexpected payment event: $e")
    }
  }

  test("send a multi-part payment B->D") {
    val start = System.currentTimeMillis
    val sender = TestProbe()
    val amount = 1000000000L.msat
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amount), "split the restaurant bill"))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowMultiPart)

    sender.send(nodes("B").paymentInitiator, SendPaymentRequest(amount, pr.paymentHash, nodes("D").nodeParams.nodeId, 5, paymentRequest = Some(pr)))
    val paymentId = sender.expectMsgType[UUID]
    assert(sender.expectMsgType[PreimageReceived].paymentHash === pr.paymentHash)
    val paymentSent = sender.expectMsgType[PaymentSent](max = 30 seconds)
    assert(paymentSent.id === paymentId, paymentSent)
    assert(paymentSent.paymentHash === pr.paymentHash, paymentSent)
    assert(paymentSent.parts.length > 1, paymentSent)
    assert(paymentSent.recipientNodeId === nodes("D").nodeParams.nodeId, paymentSent)
    assert(paymentSent.recipientAmount === amount, paymentSent)
    assert(paymentSent.feesPaid > 0.msat, paymentSent)
    assert(paymentSent.parts.forall(p => p.id != paymentSent.id), paymentSent)
    assert(paymentSent.parts.forall(p => p.route.isDefined), paymentSent)

    val paymentParts = nodes("B").nodeParams.db.payments.listOutgoingPayments(paymentId).filter(_.status.isInstanceOf[OutgoingPaymentStatus.Succeeded])
    assert(paymentParts.length == paymentSent.parts.length, paymentParts)
    assert(paymentParts.map(_.amount).sum === amount, paymentParts)
    assert(paymentParts.forall(p => p.parentId == paymentId), paymentParts)
    assert(paymentParts.forall(p => p.parentId != p.id), paymentParts)
    assert(paymentParts.forall(p => p.status.asInstanceOf[OutgoingPaymentStatus.Succeeded].feesPaid > 0.msat), paymentParts)

    awaitCond(nodes("B").nodeParams.db.audit.listSent(start, System.currentTimeMillis).nonEmpty)
    val sent = nodes("B").nodeParams.db.audit.listSent(start, System.currentTimeMillis)
    assert(sent.length === 1, sent)
    assert(sent.head.copy(parts = sent.head.parts.sortBy(_.timestamp)) === paymentSent.copy(parts = paymentSent.parts.map(_.copy(route = None)).sortBy(_.timestamp)), sent)

    awaitCond(nodes("D").nodeParams.db.payments.getIncomingPayment(pr.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))
    val Some(IncomingPayment(_, _, _, _, IncomingPaymentStatus.Received(receivedAmount, _))) = nodes("D").nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(receivedAmount === amount)
  }

  // NB: this test may take 20 seconds to complete (multi-part payment timeout).
  test("send a multi-part payment B->D greater than balance C->D (temporary remote failure)") {
    val sender = TestProbe()
    // There is enough channel capacity to route this payment, but D doesn't have enough incoming capacity to receive it
    // (the link C->D has too much capacity on D's side).
    val amount = 2000000000L.msat
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amount), "well that's an expensive restaurant bill"))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowMultiPart)

    sender.send(nodes("B").relayer, Relayer.GetOutgoingChannels())
    val canSend = sender.expectMsgType[Relayer.OutgoingChannels].channels.map(_.commitments.availableBalanceForSend).sum
    assert(canSend > amount)

    sender.send(nodes("B").paymentInitiator, SendPaymentRequest(amount, pr.paymentHash, nodes("D").nodeParams.nodeId, 1, paymentRequest = Some(pr)))
    val paymentId = sender.expectMsgType[UUID]
    val paymentFailed = sender.expectMsgType[PaymentFailed](max = 30 seconds)
    assert(paymentFailed.id === paymentId, paymentFailed)
    assert(paymentFailed.paymentHash === pr.paymentHash, paymentFailed)
    assert(paymentFailed.failures.length > 1, paymentFailed)

    assert(nodes("D").nodeParams.db.payments.getIncomingPayment(pr.paymentHash).get.status === IncomingPaymentStatus.Pending)

    sender.send(nodes("B").relayer, Relayer.GetOutgoingChannels())
    val canSend2 = sender.expectMsgType[Relayer.OutgoingChannels].channels.map(_.commitments.availableBalanceForSend).sum
    // Fee updates may impact balances, but it shouldn't have changed much.
    assert(math.abs((canSend - canSend2).toLong) < 50000000)
  }

  test("send a multi-part payment D->C (local channels only)") {
    val sender = TestProbe()
    // This amount is greater than any channel capacity between D and C, so it should be split.
    val amount = 5100000000L.msat
    sender.send(nodes("C").paymentHandler, ReceivePayment(Some(amount), "lemme borrow some money"))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowMultiPart)

    sender.send(nodes("D").paymentInitiator, SendPaymentRequest(amount, pr.paymentHash, nodes("C").nodeParams.nodeId, 3, paymentRequest = Some(pr)))
    val paymentId = sender.expectMsgType[UUID]
    assert(sender.expectMsgType[PreimageReceived].paymentHash === pr.paymentHash)
    val paymentSent = sender.expectMsgType[PaymentSent](max = 30 seconds)
    assert(paymentSent.id === paymentId, paymentSent)
    assert(paymentSent.paymentHash === pr.paymentHash, paymentSent)
    assert(paymentSent.parts.length > 1, paymentSent)
    assert(paymentSent.recipientAmount === amount, paymentSent)
    assert(paymentSent.feesPaid === 0.msat, paymentSent) // no fees when using direct channels

    val paymentParts = nodes("D").nodeParams.db.payments.listOutgoingPayments(paymentId).filter(_.status.isInstanceOf[OutgoingPaymentStatus.Succeeded])
    assert(paymentParts.map(_.amount).sum === amount, paymentParts)
    assert(paymentParts.forall(p => p.parentId == paymentId), paymentParts)
    assert(paymentParts.forall(p => p.parentId != p.id), paymentParts)
    assert(paymentParts.forall(p => p.status.asInstanceOf[OutgoingPaymentStatus.Succeeded].feesPaid == 0.msat), paymentParts)

    awaitCond(nodes("C").nodeParams.db.payments.getIncomingPayment(pr.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))
    val Some(IncomingPayment(_, _, _, _, IncomingPaymentStatus.Received(receivedAmount, _))) = nodes("C").nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(receivedAmount === amount)
  }

  test("send a multi-part payment D->C greater than balance D->C (temporary local failure)") {
    val sender = TestProbe()
    // This amount is greater than the current capacity between D and C.
    val amount = 10000000000L.msat
    sender.send(nodes("C").paymentHandler, ReceivePayment(Some(amount), "lemme borrow more money"))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowMultiPart)

    sender.send(nodes("D").relayer, Relayer.GetOutgoingChannels())
    val canSend = sender.expectMsgType[Relayer.OutgoingChannels].channels.map(_.commitments.availableBalanceForSend).sum
    assert(canSend < amount)

    sender.send(nodes("D").paymentInitiator, SendPaymentRequest(amount, pr.paymentHash, nodes("C").nodeParams.nodeId, 1, paymentRequest = Some(pr)))
    val paymentId = sender.expectMsgType[UUID]
    val paymentFailed = sender.expectMsgType[PaymentFailed](max = 30 seconds)
    assert(paymentFailed.id === paymentId, paymentFailed)
    assert(paymentFailed.paymentHash === pr.paymentHash, paymentFailed)

    val incoming = nodes("C").nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(incoming.get.status === IncomingPaymentStatus.Pending, incoming)

    sender.send(nodes("D").relayer, Relayer.GetOutgoingChannels())
    val canSend2 = sender.expectMsgType[Relayer.OutgoingChannels].channels.map(_.commitments.availableBalanceForSend).sum
    // Fee updates may impact balances, but it shouldn't have changed much.
    assert(math.abs((canSend - canSend2).toLong) < 50000000)
  }

  test("send a trampoline payment B->F1 with retry (via trampoline G)") {
    val start = System.currentTimeMillis
    val sender = TestProbe()
    val amount = 4000000000L.msat
    sender.send(nodes("F").paymentHandler, ReceivePayment(Some(amount), "like trampoline much?"))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowMultiPart)
    assert(pr.features.allowTrampoline)

    // The first attempt should fail, but the second one should succeed.
    val attempts = (1000 msat, CltvExpiryDelta(42)) :: (1000000 msat, CltvExpiryDelta(288)) :: Nil
    val payment = SendTrampolinePaymentRequest(amount, pr, nodes("G").nodeParams.nodeId, attempts)
    sender.send(nodes("B").paymentInitiator, payment)
    val paymentId = sender.expectMsgType[UUID]
    val paymentSent = sender.expectMsgType[PaymentSent](max = 30 seconds)
    assert(paymentSent.id === paymentId, paymentSent)
    assert(paymentSent.paymentHash === pr.paymentHash, paymentSent)
    assert(paymentSent.recipientNodeId === nodes("F").nodeParams.nodeId, paymentSent)
    assert(paymentSent.recipientAmount === amount, paymentSent)
    assert(paymentSent.feesPaid === 1000000.msat, paymentSent)
    assert(paymentSent.nonTrampolineFees === 0.msat, paymentSent)

    awaitCond(nodes("F").nodeParams.db.payments.getIncomingPayment(pr.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))
    val Some(IncomingPayment(_, _, _, _, IncomingPaymentStatus.Received(receivedAmount, _))) = nodes("F").nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(receivedAmount === amount)

    awaitCond({
      val relayed = nodes("G").nodeParams.db.audit.listRelayed(start, System.currentTimeMillis).filter(_.paymentHash == pr.paymentHash)
      relayed.nonEmpty && relayed.head.amountOut >= amount
    })
    val relayed = nodes("G").nodeParams.db.audit.listRelayed(start, System.currentTimeMillis).filter(_.paymentHash == pr.paymentHash).head
    assert(relayed.amountIn - relayed.amountOut > 0.msat, relayed)
    assert(relayed.amountIn - relayed.amountOut < 1000000.msat, relayed)

    val outgoingSuccess = nodes("B").nodeParams.db.payments.listOutgoingPayments(paymentId).filter(p => p.status.isInstanceOf[OutgoingPaymentStatus.Succeeded])
    outgoingSuccess.collect { case p@OutgoingPayment(_, _, _, _, _, _, _, recipientNodeId, _, _, OutgoingPaymentStatus.Succeeded(_, _, route, _)) =>
      assert(recipientNodeId === nodes("F").nodeParams.nodeId, p)
      assert(route.lastOption === Some(HopSummary(nodes("G").nodeParams.nodeId, nodes("F").nodeParams.nodeId)), p)
    }
    assert(outgoingSuccess.map(_.amount).sum === amount + 1000000.msat, outgoingSuccess)
  }

  test("send a trampoline payment D->B (via trampoline C)") {
    val start = System.currentTimeMillis
    val sender = TestProbe()
    val amount = 2500000000L.msat
    sender.send(nodes("B").paymentHandler, ReceivePayment(Some(amount), "trampoline-MPP is so #reckless"))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowMultiPart)
    assert(pr.features.allowTrampoline)

    val payment = SendTrampolinePaymentRequest(amount, pr, nodes("C").nodeParams.nodeId, Seq((350000 msat, CltvExpiryDelta(288))))
    sender.send(nodes("D").paymentInitiator, payment)
    val paymentId = sender.expectMsgType[UUID]
    val paymentSent = sender.expectMsgType[PaymentSent](max = 30 seconds)
    assert(paymentSent.id === paymentId, paymentSent)
    assert(paymentSent.paymentHash === pr.paymentHash, paymentSent)
    assert(paymentSent.recipientAmount === amount, paymentSent)
    assert(paymentSent.feesPaid === 350000.msat, paymentSent)
    assert(paymentSent.nonTrampolineFees === 0.msat, paymentSent)

    awaitCond(nodes("B").nodeParams.db.payments.getIncomingPayment(pr.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))
    val Some(IncomingPayment(_, _, _, _, IncomingPaymentStatus.Received(receivedAmount, _))) = nodes("B").nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(receivedAmount === amount)

    awaitCond({
      val relayed = nodes("C").nodeParams.db.audit.listRelayed(start, System.currentTimeMillis).filter(_.paymentHash == pr.paymentHash)
      relayed.nonEmpty && relayed.head.amountOut >= amount
    })
    val relayed = nodes("C").nodeParams.db.audit.listRelayed(start, System.currentTimeMillis).filter(_.paymentHash == pr.paymentHash).head
    assert(relayed.amountIn - relayed.amountOut > 0.msat, relayed)
    assert(relayed.amountIn - relayed.amountOut < 350000.msat, relayed)

    val outgoingSuccess = nodes("D").nodeParams.db.payments.listOutgoingPayments(paymentId).filter(p => p.status.isInstanceOf[OutgoingPaymentStatus.Succeeded])
    outgoingSuccess.collect { case p@OutgoingPayment(_, _, _, _, _, _, _, recipientNodeId, _, _, OutgoingPaymentStatus.Succeeded(_, _, route, _)) =>
      assert(recipientNodeId === nodes("B").nodeParams.nodeId, p)
      assert(route.lastOption === Some(HopSummary(nodes("C").nodeParams.nodeId, nodes("B").nodeParams.nodeId)), p)
    }
    assert(outgoingSuccess.map(_.amount).sum === amount + 350000.msat, outgoingSuccess)

    awaitCond(nodes("D").nodeParams.db.audit.listSent(start, System.currentTimeMillis).nonEmpty)
    val sent = nodes("D").nodeParams.db.audit.listSent(start, System.currentTimeMillis)
    assert(sent.length === 1, sent)
    assert(sent.head.copy(parts = sent.head.parts.sortBy(_.timestamp)) === paymentSent.copy(parts = paymentSent.parts.map(_.copy(route = None)).sortBy(_.timestamp)), sent)
  }

  test("send a trampoline payment F1->A (via trampoline C, non-trampoline recipient)") {
    // The A -> B channel is not announced.
    val start = System.currentTimeMillis
    val sender = TestProbe()
    sender.send(nodes("B").relayer, Relayer.GetOutgoingChannels())
    val channelUpdate_ba = sender.expectMsgType[Relayer.OutgoingChannels].channels.filter(c => c.nextNodeId == nodes("A").nodeParams.nodeId).head.channelUpdate
    val routingHints = List(List(ExtraHop(nodes("B").nodeParams.nodeId, channelUpdate_ba.shortChannelId, channelUpdate_ba.feeBaseMsat, channelUpdate_ba.feeProportionalMillionths, channelUpdate_ba.cltvExpiryDelta)))

    val amount = 3000000000L.msat
    sender.send(nodes("A").paymentHandler, ReceivePayment(Some(amount), "trampoline to non-trampoline is so #vintage", extraHops = routingHints))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowMultiPart)
    assert(!pr.features.allowTrampoline)

    val payment = SendTrampolinePaymentRequest(amount, pr, nodes("C").nodeParams.nodeId, Seq((1000000 msat, CltvExpiryDelta(432))))
    sender.send(nodes("F").paymentInitiator, payment)
    val paymentId = sender.expectMsgType[UUID]
    val paymentSent = sender.expectMsgType[PaymentSent](max = 30 seconds)
    assert(paymentSent.id === paymentId, paymentSent)
    assert(paymentSent.paymentHash === pr.paymentHash, paymentSent)
    assert(paymentSent.recipientAmount === amount, paymentSent)
    assert(paymentSent.trampolineFees === 1000000.msat, paymentSent)

    awaitCond(nodes("A").nodeParams.db.payments.getIncomingPayment(pr.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))
    val Some(IncomingPayment(_, _, _, _, IncomingPaymentStatus.Received(receivedAmount, _))) = nodes("A").nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(receivedAmount === amount)

    awaitCond({
      val relayed = nodes("C").nodeParams.db.audit.listRelayed(start, System.currentTimeMillis).filter(_.paymentHash == pr.paymentHash)
      relayed.nonEmpty && relayed.head.amountOut >= amount
    })
    val relayed = nodes("C").nodeParams.db.audit.listRelayed(start, System.currentTimeMillis).filter(_.paymentHash == pr.paymentHash).head
    assert(relayed.amountIn - relayed.amountOut > 0.msat, relayed)
    assert(relayed.amountIn - relayed.amountOut < 1000000.msat, relayed)

    val outgoingSuccess = nodes("F").nodeParams.db.payments.listOutgoingPayments(paymentId).filter(p => p.status.isInstanceOf[OutgoingPaymentStatus.Succeeded])
    outgoingSuccess.collect { case p@OutgoingPayment(_, _, _, _, _, _, _, recipientNodeId, _, _, OutgoingPaymentStatus.Succeeded(_, _, route, _)) =>
      assert(recipientNodeId === nodes("A").nodeParams.nodeId, p)
      assert(route.lastOption === Some(HopSummary(nodes("C").nodeParams.nodeId, nodes("A").nodeParams.nodeId)), p)
    }
    assert(outgoingSuccess.map(_.amount).sum === amount + 1000000.msat, outgoingSuccess)
  }

  test("send a trampoline payment B->D (temporary local failure at trampoline)") {
    val sender = TestProbe()

    // We put most of the capacity C <-> D on D's side.
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(8000000000L msat), "plz send everything"))
    val pr1 = sender.expectMsgType[PaymentRequest]
    sender.send(nodes("C").paymentInitiator, SendPaymentRequest(8000000000L msat, pr1.paymentHash, nodes("D").nodeParams.nodeId, 3, paymentRequest = Some(pr1)))
    sender.expectMsgType[UUID]
    sender.expectMsgType[PreimageReceived](max = 30 seconds)
    sender.expectMsgType[PaymentSent](max = 30 seconds)

    // Now we try to send more than C's outgoing capacity to D.
    val amount = 2000000000L.msat
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amount), "I iz Satoshi"))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowMultiPart)
    assert(pr.features.allowTrampoline)

    val payment = SendTrampolinePaymentRequest(amount, pr, nodes("C").nodeParams.nodeId, Seq((250000 msat, CltvExpiryDelta(288))))
    sender.send(nodes("B").paymentInitiator, payment)
    val paymentId = sender.expectMsgType[UUID]
    val paymentFailed = sender.expectMsgType[PaymentFailed](max = 30 seconds)
    assert(paymentFailed.id === paymentId, paymentFailed)
    assert(paymentFailed.paymentHash === pr.paymentHash, paymentFailed)

    assert(nodes("D").nodeParams.db.payments.getIncomingPayment(pr.paymentHash).get.status === IncomingPaymentStatus.Pending)
    val outgoingPayments = nodes("B").nodeParams.db.payments.listOutgoingPayments(paymentId)
    assert(outgoingPayments.nonEmpty, outgoingPayments)
    assert(outgoingPayments.forall(p => p.status.isInstanceOf[OutgoingPaymentStatus.Failed]), outgoingPayments)
  }

  test("send a trampoline payment A->D (temporary remote failure at trampoline)") {
    val sender = TestProbe()
    val amount = 2000000000L.msat // B can forward to C, but C doesn't have that much outgoing capacity to D
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amount), "I iz not Satoshi"))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowMultiPart)
    assert(pr.features.allowTrampoline)

    val payment = SendTrampolinePaymentRequest(amount, pr, nodes("B").nodeParams.nodeId, Seq((450000 msat, CltvExpiryDelta(288))))
    sender.send(nodes("A").paymentInitiator, payment)
    val paymentId = sender.expectMsgType[UUID]
    val paymentFailed = sender.expectMsgType[PaymentFailed](max = 30 seconds)
    assert(paymentFailed.id === paymentId, paymentFailed)
    assert(paymentFailed.paymentHash === pr.paymentHash, paymentFailed)

    assert(nodes("D").nodeParams.db.payments.getIncomingPayment(pr.paymentHash).get.status === IncomingPaymentStatus.Pending)
    val outgoingPayments = nodes("A").nodeParams.db.payments.listOutgoingPayments(paymentId)
    assert(outgoingPayments.nonEmpty, outgoingPayments)
    assert(outgoingPayments.forall(p => p.status.isInstanceOf[OutgoingPaymentStatus.Failed]), outgoingPayments)
  }

  test("generate and validate lots of channels") {
    implicit val bitcoinClient: ExtendedBitcoinClient = new ExtendedBitcoinClient(bitcoinrpcclient)
    // we simulate fake channels by publishing a funding tx and sending announcement messages to a node at random
    logger.info(s"generating fake channels")
    val sender = TestProbe()
    sender.send(bitcoincli, BitcoinReq("getnewaddress"))
    val JString(address) = sender.expectMsgType[JValue]
    val channels = for (i <- 0 until 242) yield {
      // let's generate a block every 10 txs so that we can compute short ids
      if (i % 10 == 0) {
        generateBlocks(1, Some(address))
      }
      AnnouncementsBatchValidationSpec.simulateChannel
    }
    generateBlocks(1, Some(address))
    logger.info(s"simulated ${channels.size} channels")

    val remoteNodeId = PrivateKey(ByteVector32(ByteVector.fill(32)(1))).publicKey

    // then we make the announcements
    val announcements = channels.map(c => AnnouncementsBatchValidationSpec.makeChannelAnnouncement(c))
    announcements.foreach { ann =>
      nodes("A").router ! PeerRoutingMessage(sender.ref, remoteNodeId, ann)
      sender.expectMsg(TransportHandler.ReadAck(ann))
      sender.expectMsg(GossipDecision.Accepted(ann))
    }
    awaitCond({
      sender.send(nodes("D").router, Router.GetChannels)
      sender.expectMsgType[Iterable[ChannelAnnouncement]].size == channels.size + 8 // 8 original channels (A -> B is private)
    }, max = 120 seconds, interval = 1 second)
  }

  /** Handy way to check what the channel balances are before adding new tests. */
  def debugChannelBalances(): Unit = {
    val sender = TestProbe()
    sender.send(nodes("B").relayer, Relayer.GetOutgoingChannels())
    sender.send(nodes("C").relayer, Relayer.GetOutgoingChannels())

    logger.info(s"A -> ${nodes("A").nodeParams.nodeId}")
    logger.info(s"B -> ${nodes("B").nodeParams.nodeId}")
    logger.info(s"C -> ${nodes("C").nodeParams.nodeId}")
    logger.info(s"D -> ${nodes("D").nodeParams.nodeId}")
    logger.info(s"E -> ${nodes("E").nodeParams.nodeId}")
    logger.info(s"F -> ${nodes("F").nodeParams.nodeId}")
    logger.info(s"G -> ${nodes("G").nodeParams.nodeId}")

    val channels1 = sender.expectMsgType[Relayer.OutgoingChannels]
    val channels2 = sender.expectMsgType[Relayer.OutgoingChannels]

    logger.info(channels1.channels.map(_.toUsableBalance))
    logger.info(channels2.channels.map(_.toUsableBalance))
  }

}
