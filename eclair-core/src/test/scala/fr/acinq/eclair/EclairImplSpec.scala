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

package fr.acinq.eclair

import akka.actor.typed.scaladsl.adapter.{ClassicActorRefOps, actorRefAdapter}
import akka.actor.{ActorRef, Status}
import akka.pattern.pipe
import akka.testkit.TestProbe
import akka.util.Timeout
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, ByteVector64, Crypto, SatoshiLong}
import fr.acinq.eclair.ApiTypes.{ChannelIdentifier, ChannelNotFound}
import fr.acinq.eclair.TestConstants._
import fr.acinq.eclair.blockchain.DummyOnChainWallet
import fr.acinq.eclair.blockchain.fee.{FeeratePerByte, FeeratePerKw}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db._
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.io.Peer.OpenChannel
import fr.acinq.eclair.payment.receive.MultiPartHandler.ReceiveStandardPayment
import fr.acinq.eclair.payment.receive.PaymentHandler
import fr.acinq.eclair.payment.relay.Relayer.{GetOutgoingChannels, RelayFees}
import fr.acinq.eclair.payment.send.PaymentIdentifier
import fr.acinq.eclair.payment.send.PaymentInitiator._
import fr.acinq.eclair.payment.{Bolt11Invoice, Invoice, PaymentFailed}
import fr.acinq.eclair.router.Graph.GraphStructure.DirectedGraph
import fr.acinq.eclair.router.RouteCalculationSpec.makeUpdateShort
import fr.acinq.eclair.router.Router.{PredefinedNodeRoute, PrivateChannel, PublicChannel, RouteRequest}
import fr.acinq.eclair.router.{Announcements, GraphWithBalanceEstimates, Router}
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec
import fr.acinq.eclair.wire.protocol.{ChannelUpdate, Color, NodeAnnouncement}
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.TryValues.convertTryToSuccessOrFailure
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, ParallelTestExecution}
import scodec.bits._

import java.util.UUID
import scala.collection.immutable.SortedMap
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class EclairImplSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with IdiomaticMockito with ParallelTestExecution {
  implicit val timeout: Timeout = Timeout(30 seconds)

  case class FixtureParam(register: TestProbe, relayer: TestProbe, router: TestProbe, paymentInitiator: TestProbe, switchboard: TestProbe, paymentHandler: TestProbe, sender: TestProbe, kit: Kit)

  override def withFixture(test: OneArgTest): Outcome = {
    val watcher = TestProbe()
    val paymentHandler = TestProbe()
    val register = TestProbe()
    val relayer = TestProbe()
    val router = TestProbe()
    val switchboard = TestProbe()
    val paymentInitiator = TestProbe()
    val server = TestProbe()
    val channelsListener = TestProbe()
    val balanceActor = TestProbe()
    val postman = TestProbe()
    val kit = Kit(
      TestConstants.Alice.nodeParams,
      system,
      watcher.ref,
      paymentHandler.ref,
      register.ref,
      relayer.ref,
      router.ref,
      switchboard.ref,
      paymentInitiator.ref,
      server.ref,
      channelsListener.ref.toTyped,
      balanceActor.ref.toTyped,
      postman.ref.toTyped,
      new DummyOnChainWallet()
    )
    withFixture(test.toNoArgTest(FixtureParam(register, relayer, router, paymentInitiator, switchboard, paymentHandler, TestProbe(), kit)))
  }

  test("convert fee rate properly") { f =>
    import f._

    val eclair = new EclairImpl(kit)
    val nodeId = PublicKey(hex"030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87")

    // standard conversion
    eclair.open(nodeId, fundingAmount = 10000000L sat, pushAmount_opt = None, channelType_opt = None, fundingFeeratePerByte_opt = Some(FeeratePerByte(5 sat)), announceChannel_opt = None, openTimeout_opt = None)
    val open = switchboard.expectMsgType[OpenChannel]
    assert(open.fundingTxFeerate_opt.contains(FeeratePerKw(1250 sat)))

    // check that minimum fee rate of 253 sat/bw is used
    eclair.open(nodeId, fundingAmount = 10000000L sat, pushAmount_opt = None, channelType_opt = Some(ChannelTypes.StaticRemoteKey()), fundingFeeratePerByte_opt = Some(FeeratePerByte(1 sat)), announceChannel_opt = None, openTimeout_opt = None)
    val open1 = switchboard.expectMsgType[OpenChannel]
    assert(open1.fundingTxFeerate_opt.contains(FeeratePerKw.MinimumFeeratePerKw))
    assert(open1.channelType_opt.contains(ChannelTypes.StaticRemoteKey()))
  }

  test("call send with passing correct arguments") { f =>
    import f._

    val eclair = new EclairImpl(kit)
    val nodePrivKey = randomKey()
    val invoice0 = Bolt11Invoice(Block.RegtestGenesisBlock.hash, Some(123 msat), ByteVector32.Zeroes, nodePrivKey, Left("description"), CltvExpiryDelta(18))
    eclair.send(None, 123 msat, invoice0)
    val send = paymentInitiator.expectMsgType[SendPaymentToNode]
    assert(send.externalId.isEmpty)
    assert(send.recipientNodeId == nodePrivKey.publicKey)
    assert(send.recipientAmount == 123.msat)
    assert(send.paymentHash == ByteVector32.Zeroes)
    assert(send.invoice == invoice0)

    // with finalCltvExpiry
    val externalId2 = "487da196-a4dc-4b1e-92b4-3e5e905e9f3f"
    val invoice2 = Bolt11Invoice("lntb", Some(123 msat), TimestampSecond.now(), nodePrivKey.publicKey, List(Bolt11Invoice.MinFinalCltvExpiry(96), Bolt11Invoice.PaymentHash(ByteVector32.Zeroes), Bolt11Invoice.Description("description"), Bolt11Invoice.PaymentSecret(ByteVector32.One)), ByteVector.empty)
    eclair.send(Some(externalId2), 123 msat, invoice2)
    val send2 = paymentInitiator.expectMsgType[SendPaymentToNode]
    assert(send2.externalId.contains(externalId2))
    assert(send2.recipientNodeId == nodePrivKey.publicKey)
    assert(send2.recipientAmount == 123.msat)
    assert(send2.paymentHash == ByteVector32.Zeroes)
    assert(send2.invoice == invoice2)

    // with custom route fees parameters
    eclair.send(None, 123 msat, invoice0, maxFeeFlat_opt = Some(123 sat), maxFeePct_opt = Some(4.20))
    val send3 = paymentInitiator.expectMsgType[SendPaymentToNode]
    assert(send3.externalId.isEmpty)
    assert(send3.recipientNodeId == nodePrivKey.publicKey)
    assert(send3.recipientAmount == 123.msat)
    assert(send3.paymentHash == ByteVector32.Zeroes)
    assert(send3.routeParams.boundaries.maxFeeFlat == 123000.msat) // conversion sat -> msat
    assert(send3.routeParams.boundaries.maxFeeProportional == 0.042)

    val invalidExternalId = "Robert'); DROP TABLE received_payments; DROP TABLE sent_payments; DROP TABLE payments;"
    assertThrows[IllegalArgumentException](Await.result(eclair.send(Some(invalidExternalId), 123 msat, invoice0), 50 millis))

    val expiredInvoice = invoice2.copy(createdAt = 0.unixsec)
    assertThrows[IllegalArgumentException](Await.result(eclair.send(None, 123 msat, expiredInvoice), 50 millis))
  }

  test("return node details") { f =>
    import f._

    val eclair = new EclairImpl(kit)

    val ann = NodeAnnouncement(randomBytes64(), Features.empty, TimestampSecond(42L), randomKey().publicKey, Color(42, 42, 42), "ACINQ", Nil)
    val remoteNode = Router.PublicNode(ann, 7, 561_000 sat)
    eclair.node(ann.nodeId).pipeTo(sender.ref)
    val msg1 = router.expectMsgType[Router.GetNode]
    assert(msg1.nodeId == ann.nodeId)
    msg1.replyTo ! remoteNode
    sender.expectMsg(Some(remoteNode))

    val unknownNode = Router.UnknownNode(randomKey().publicKey)
    eclair.node(unknownNode.nodeId).pipeTo(sender.ref)
    val msg2 = router.expectMsgType[Router.GetNode]
    assert(msg2.nodeId == unknownNode.nodeId)
    msg2.replyTo ! unknownNode
    sender.expectMsg(None)
  }

  test("return node announcements") { f =>
    import f._

    val eclair = new EclairImpl(kit)
    val remoteNodeAnn1 = NodeAnnouncement(randomBytes64(), Features.empty, TimestampSecond(42L), randomKey().publicKey, Color(42, 42, 42), "LN-rocks", Nil)
    val remoteNodeAnn2 = NodeAnnouncement(randomBytes64(), Features.empty, TimestampSecond(43L), randomKey().publicKey, Color(43, 43, 43), "LN-papers", Nil)
    val allNodes = Seq(
      NodeAnnouncement(randomBytes64(), Features.empty, TimestampSecond(561L), randomKey().publicKey, Color(0, 0, 0), "some-node", Nil),
      remoteNodeAnn1,
      remoteNodeAnn2,
      NodeAnnouncement(randomBytes64(), Features.empty, TimestampSecond(1105L), randomKey().publicKey, Color(0, 0, 0), "some-other-node", Nil),
    )

    {
      eclair.nodes().pipeTo(sender.ref)
      router.expectMsg(Router.GetNodes)
      router.reply(allNodes)
      assert(sender.expectMsgType[Iterable[NodeAnnouncement]].toSet == allNodes.toSet)
    }
    {
      eclair.nodes(Some(Set(remoteNodeAnn1.nodeId, remoteNodeAnn2.nodeId))).pipeTo(sender.ref)
      router.expectMsg(Router.GetNodes)
      router.reply(allNodes)
      assert(sender.expectMsgType[Iterable[NodeAnnouncement]].toSet == Set(remoteNodeAnn1, remoteNodeAnn2))
    }
    {
      eclair.nodes(Some(Set(randomKey().publicKey))).pipeTo(sender.ref)
      router.expectMsg(Router.GetNodes)
      router.reply(allNodes)
      assert(sender.expectMsgType[Iterable[NodeAnnouncement]].isEmpty)
    }
  }

  test("allupdates can filter by nodeId") { f =>
    import f._

    val (a_priv, b_priv, c_priv, d_priv, e_priv) = (
      PrivateKey(hex"3580a881ac24eb00530a51235c42bcb65424ba121e2e7d910a70fa531a578d21"),
      PrivateKey(hex"f6a353f7a5de654501c3495acde7450293f74d09086c2b7c9a4e524248d0daac"),
      PrivateKey(hex"c2efe0095f9113bc5b9f4140958670a8ea2afc3ed50fb32ea9c809f82b3b0374"),
      PrivateKey(hex"216414970b4216b197a1040367419ad6922f80e8b73ced083e9afe5e6ddd8e4d"),
      PrivateKey(hex"216414970b4216b197a1040367419ad6922f80e8b73ced083e9afe5e6ddd8e4e"),
    )

    val (a, b, c, d, e) = (a_priv.publicKey, b_priv.publicKey, c_priv.publicKey, d_priv.publicKey, e_priv.publicKey)
    val ann_ab = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, RealShortChannelId(1), a, b, a, b, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes)
    val ann_ae = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, RealShortChannelId(4), a, e, a, e, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes)
    val ann_bc = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, RealShortChannelId(2), b, c, b, c, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes)
    val ann_cd = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, RealShortChannelId(3), c, d, c, d, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes)
    val ann_ec = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, RealShortChannelId(7), e, c, e, c, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes)

    assert(Announcements.isNode1(a, b))
    assert(Announcements.isNode1(b, c))

    val channels = SortedMap(Seq(
      (ann_ab, makeUpdateShort(ShortChannelId(1L), a, b, feeBase = 0 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(13))),
      (ann_ae, makeUpdateShort(ShortChannelId(4L), a, e, feeBase = 0 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(12))),
      (ann_bc, makeUpdateShort(ShortChannelId(2L), b, c, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(500))),
      (ann_cd, makeUpdateShort(ShortChannelId(3L), c, d, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(500))),
      (ann_ec, makeUpdateShort(ShortChannelId(7L), e, c, feeBase = 2 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(12)))
    ).map { case (ann, update) =>
      update.shortChannelId -> PublicChannel(ann, ByteVector32.Zeroes, 100 sat, Some(update.copy(channelFlags = ChannelUpdate.ChannelFlags.DUMMY)), None, None)
    }: _*)

    val eclair = new EclairImpl(kit)
    eclair.allUpdates(Some(b)).pipeTo(sender.ref) // ask updates filtered by 'b'
    router.expectMsg(Router.GetChannelsMap)
    router.reply(channels)
    assert(sender.expectMsgType[Iterable[ChannelUpdate]].map(_.shortChannelId).toSet == Set(ShortChannelId(2)))
  }

  test("open with bad arguments") { f =>
    import f._

    val eclair = new EclairImpl(kit)

    // option_scid_alias is not compatible with public channels
    eclair.open(randomKey().publicKey, 123456 sat, None, Some(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(scidAlias = true, zeroConf = true)), None, announceChannel_opt = Some(true), None).pipeTo(sender.ref)
    assert(sender.expectMsgType[Status.Failure].cause.getMessage.contains("option_scid_alias is not compatible with public channels"))

    eclair.open(randomKey().publicKey, 123456 sat, None, Some(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(scidAlias = true, zeroConf = true)), None, announceChannel_opt = Some(false), None).pipeTo(sender.ref)
    switchboard.expectMsgType[Peer.OpenChannel]

    eclair.open(randomKey().publicKey, 123456 sat, None, Some(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(zeroConf = true)), None, announceChannel_opt = Some(true), None).pipeTo(sender.ref)
    switchboard.expectMsgType[Peer.OpenChannel]

    eclair.open(randomKey().publicKey, 123456 sat, None, Some(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(zeroConf = true)), None, announceChannel_opt = Some(false), None).pipeTo(sender.ref)
    switchboard.expectMsgType[Peer.OpenChannel]
  }

  test("close and forceclose should work both with channelId and shortChannelId") { f =>
    import f._

    val eclair = new EclairImpl(kit)

    eclair.forceClose(Left(ByteVector32.Zeroes) :: Nil)
    register.expectMsg(Register.Forward(null, ByteVector32.Zeroes, CMD_FORCECLOSE(ActorRef.noSender)))

    eclair.forceClose(Right(ShortChannelId.fromCoordinates("568749x2597x0").success.value) :: Nil)
    register.expectMsg(Register.ForwardShortId(null, ShortChannelId.fromCoordinates("568749x2597x0").success.value, CMD_FORCECLOSE(ActorRef.noSender)))

    eclair.forceClose(Left(ByteVector32.Zeroes) :: Right(ShortChannelId.fromCoordinates("568749x2597x0").success.value) :: Nil)
    register.expectMsgAllOf(
      Register.Forward(null, ByteVector32.Zeroes, CMD_FORCECLOSE(ActorRef.noSender)),
      Register.ForwardShortId(null, ShortChannelId.fromCoordinates("568749x2597x0").success.value, CMD_FORCECLOSE(ActorRef.noSender))
    )

    eclair.close(Left(ByteVector32.Zeroes) :: Nil, None, None)
    register.expectMsg(Register.Forward(null, ByteVector32.Zeroes, CMD_CLOSE(ActorRef.noSender, None, None)))

    val customClosingFees = ClosingFeerates(FeeratePerKw(500 sat), FeeratePerKw(200 sat), FeeratePerKw(1000 sat))
    eclair.close(Left(ByteVector32.Zeroes) :: Nil, None, Some(customClosingFees))
    register.expectMsg(Register.Forward(null, ByteVector32.Zeroes, CMD_CLOSE(ActorRef.noSender, None, Some(customClosingFees))))

    eclair.close(Right(ShortChannelId.fromCoordinates("568749x2597x0").success.value) :: Nil, None, None)
    register.expectMsg(Register.ForwardShortId(null, ShortChannelId.fromCoordinates("568749x2597x0").success.value, CMD_CLOSE(ActorRef.noSender, None, None)))

    eclair.close(Right(ShortChannelId.fromCoordinates("568749x2597x0").success.value) :: Nil, Some(ByteVector.empty), Some(customClosingFees))
    register.expectMsg(Register.ForwardShortId(null, ShortChannelId.fromCoordinates("568749x2597x0").success.value, CMD_CLOSE(ActorRef.noSender, Some(ByteVector.empty), Some(customClosingFees))))

    eclair.close(Right(ShortChannelId.fromCoordinates("568749x2597x0").success.value) :: Left(ByteVector32.One) :: Right(ShortChannelId.fromCoordinates("568749x2597x1").success.value) :: Nil, None, None)
    register.expectMsgAllOf(
      Register.ForwardShortId(null, ShortChannelId.fromCoordinates("568749x2597x0").success.value, CMD_CLOSE(ActorRef.noSender, None, None)),
      Register.Forward(null, ByteVector32.One, CMD_CLOSE(ActorRef.noSender, None, None)),
      Register.ForwardShortId(null, ShortChannelId.fromCoordinates("568749x2597x1").success.value, CMD_CLOSE(ActorRef.noSender, None, None))
    )
  }

  test("receive should have an optional fallback address and use millisatoshi") { f =>
    import f._

    val fallBackAddressRaw = "muhtvdmsnbQEPFuEmxcChX58fGvXaaUoVt"
    val eclair = new EclairImpl(kit)
    eclair.receive(Left("some desc"), Some(123 msat), Some(456), Some(fallBackAddressRaw), None)
    val receive = paymentHandler.expectMsgType[ReceiveStandardPayment]

    assert(receive.amount_opt.contains(123 msat))
    assert(receive.expirySeconds_opt.contains(456))
    assert(receive.fallbackAddress_opt.contains(fallBackAddressRaw))

    // try with wrong address format
    assertThrows[IllegalStateException](eclair.receive(Left("some desc"), Some(123 msat), Some(456), Some("wassa wassa"), None))
  }

  test("passing a payment_preimage to /createinvoice should result in an invoice with payment_hash=H(payment_preimage)") { f =>
    import f._

    val kitWithPaymentHandler = kit.copy(paymentHandler = system.actorOf(PaymentHandler.props(Alice.nodeParams, TestProbe().ref)))
    val eclair = new EclairImpl(kitWithPaymentHandler)
    val paymentPreimage = randomBytes32()

    eclair.receive(Left("some desc"), None, None, None, Some(paymentPreimage)).pipeTo(sender.ref)
    assert(sender.expectMsgType[Invoice].paymentHash == Crypto.sha256(paymentPreimage))
  }

  test("sendtoroute should pass the parameters correctly") { f =>
    import f._

    val eclair = new EclairImpl(kit)
    val route = PredefinedNodeRoute(1000 msat, Seq(randomKey().publicKey))
    val parentId = UUID.randomUUID()
    val secret = randomBytes32()
    val pr = Bolt11Invoice(Block.LivenetGenesisBlock.hash, Some(1234 msat), ByteVector32.One, randomKey(), Right(randomBytes32()), CltvExpiryDelta(18))
    eclair.sendToRoute(Some(1200 msat), Some("42"), Some(parentId), pr, route, Some(secret), Some(100 msat), Some(CltvExpiryDelta(144)))
    paymentInitiator.expectMsg(SendPaymentToRoute(1200 msat, pr, route, Some("42"), Some(parentId), Some(TrampolineAttempt(secret, 100 msat, CltvExpiryDelta(144)))))
  }

  test("find routes") { f =>
    import f._

    val eclair = new EclairImpl(kit)
    val (a, b, c) = (randomKey().publicKey, randomKey().publicKey, randomKey().publicKey)
    val channel1 = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, RealShortChannelId(1), a, b, a, b, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes)
    val channel2 = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, RealShortChannelId(2), b, c, b, c, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes)
    val publicChannels = SortedMap(
      channel1.shortChannelId -> PublicChannel(channel1, ByteVector32.Zeroes, 100_000 sat, None, None, None),
      channel2.shortChannelId -> PublicChannel(channel2, ByteVector32.Zeroes, 150_000 sat, None, None, None),
    )
    val (channelId3, shortIds3) = (randomBytes32(), ShortIds(RealScidStatus.Unknown, Alias(13), None))
    val (channelId4, shortIds4) = (randomBytes32(), ShortIds(RealScidStatus.Final(RealShortChannelId(4)), Alias(14), None))
    val privateChannels = Map(
      channelId3 -> PrivateChannel(channelId3, shortIds3, a, b, None, None, Router.ChannelMeta(25_000 msat, 50_000 msat)),
      channelId4 -> PrivateChannel(channelId4, shortIds4, a, c, None, None, Router.ChannelMeta(75_000 msat, 10_000 msat)),
    )
    val scidMapping = Map(
      shortIds3.localAlias.toLong -> channelId3,
      shortIds4.localAlias.toLong -> channelId4,
      shortIds4.real.toOption.get.toLong -> channelId4,
    )
    val g = GraphWithBalanceEstimates(DirectedGraph(Nil), 1 hour)
    val routerData = Router.Data(Map.empty, publicChannels, SortedMap.empty, Router.Stash(Map.empty, Map.empty), Router.Rebroadcast(Map.empty, Map.empty, Map.empty), Map.empty, privateChannels, scidMapping, Map.empty, g, Map.empty)

    eclair.findRoute(c, 250_000 msat, None)
    val routeRequest1 = router.expectMsgType[RouteRequest]
    assert(routeRequest1.target.nodeId == c)
    assert(routeRequest1.ignore == Router.Ignore.empty)

    val unknownNodeId = randomKey().publicKey
    val unknownScid = Alias(42)
    eclair.findRoute(c, 250_000 msat, None, ignoreNodeIds = Seq(b, unknownNodeId), ignoreShortChannelIds = Seq(channel1.shortChannelId, shortIds3.localAlias, shortIds4.real.toOption.get, unknownScid))
    router.expectMsg(Router.GetRouterData)
    router.reply(routerData)
    val routeRequest2 = router.expectMsgType[RouteRequest]
    assert(routeRequest2.target.nodeId == c)
    assert(routeRequest2.ignore.nodes == Set(b, unknownNodeId))
    assert(routeRequest2.ignore.channels == Set(
      Router.ChannelDesc(channel1.shortChannelId, a, b),
      Router.ChannelDesc(channel1.shortChannelId, b, a),
      Router.ChannelDesc(shortIds3.localAlias, a, b),
      Router.ChannelDesc(shortIds3.localAlias, b, a),
      Router.ChannelDesc(shortIds4.real.toOption.get, a, c),
      Router.ChannelDesc(shortIds4.real.toOption.get, c, a),
    ))
  }

  test("call sendWithPreimage, which generates a random preimage, to perform a KeySend payment") { f =>
    import f._

    val eclair = new EclairImpl(kit)
    val nodeId = randomKey().publicKey

    eclair.sendWithPreimage(None, nodeId, 12345 msat)
    val send = paymentInitiator.expectMsgType[SendSpontaneousPayment]
    assert(send.externalId.isEmpty)
    assert(send.recipientNodeId == nodeId)
    assert(send.recipientAmount == 12345.msat)
    assert(send.paymentHash == Crypto.sha256(send.paymentPreimage))
    assert(send.userCustomTlvs.isEmpty)
  }

  test("call sendWithPreimage, giving a specific preimage, to perform a KeySend payment") { f =>
    import f._

    val eclair = new EclairImpl(kit)
    val nodeId = randomKey().publicKey
    val expectedPaymentPreimage = ByteVector32(hex"deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef")
    val expectedPaymentHash = Crypto.sha256(expectedPaymentPreimage)

    eclair.sendWithPreimage(None, nodeId, 12345 msat, paymentPreimage = expectedPaymentPreimage)
    val send = paymentInitiator.expectMsgType[SendSpontaneousPayment]
    assert(send.externalId.isEmpty)
    assert(send.recipientNodeId == nodeId)
    assert(send.recipientAmount == 12345.msat)
    assert(send.paymentPreimage == expectedPaymentPreimage)
    assert(send.paymentHash == expectedPaymentHash)
    assert(send.userCustomTlvs.isEmpty)
  }

  test("sign and verify an arbitrary message with the node's private key") { f =>
    import f._

    val eclair = new EclairImpl(kit)

    val base64Msg = "aGVsbG8sIHdvcmxk" // echo -n 'hello, world' | base64
    val bytesMsg = ByteVector.fromValidBase64(base64Msg)

    val signedMessage = eclair.signMessage(bytesMsg)
    assert(signedMessage.nodeId == kit.nodeParams.nodeId)
    assert(signedMessage.message == base64Msg)

    val verifiedMessage = eclair.verifyMessage(bytesMsg, signedMessage.signature)
    assert(verifiedMessage.valid)
    assert(verifiedMessage.publicKey == kit.nodeParams.nodeId)

    val prefix = ByteVector("Lightning Signed Message:".getBytes)
    val dhash256 = Crypto.hash256(prefix ++ bytesMsg)
    val expectedDigest = ByteVector32(hex"cbedbc1542fb139e2e10954f1ff9f82e8a1031cc63260636bbc45a90114552ea")
    assert(dhash256 == expectedDigest)
    assert(Crypto.verifySignature(dhash256, ByteVector64(signedMessage.signature.tail), kit.nodeParams.nodeId))
  }

  test("verify an invalid signature for the given message") { f =>
    import f._

    val eclair = new EclairImpl(kit)

    val base64Msg = "aGVsbG8sIHdvcmxk" // echo -n 'hello, world' | base64
    val bytesMsg = ByteVector.fromValidBase64(base64Msg)

    val signedMessage = eclair.signMessage(bytesMsg)
    assert(signedMessage.nodeId == kit.nodeParams.nodeId)
    assert(signedMessage.message == base64Msg)

    val wrongMsg = ByteVector.fromValidBase64(base64Msg.tail)
    val verifiedMessage = eclair.verifyMessage(wrongMsg, signedMessage.signature)
    assert(verifiedMessage.valid)
    assert(verifiedMessage.publicKey !== kit.nodeParams.nodeId)
  }

  test("verify a signature with different recid formats") { f =>
    import f._
    val eclair = new EclairImpl(kit)

    val bytesMsg = ByteVector("hello, world".getBytes)
    val sig = hex"730dce842c31b692dc041c2d0f00423d2a2a67b0c63c1a905d500f09652a5b1a036763a1603333fa589ae92d1f7963428ff170e976d0966a113f4b9f9d0efc7f"
    assert(eclair.verifyMessage(bytesMsg, hex"1f" ++ sig).valid) // 0x1f = 31, format used by lnd (spec: https://twitter.com/rusty_twit/status/1182102005914800128)
    assert(eclair.verifyMessage(bytesMsg, hex"00" ++ sig).valid)
  }

  test("ensure that an invalid recoveryId cause the signature verification to fail") { f =>
    import f._

    val eclair = new EclairImpl(kit)

    val base64Msg = "aGVsbG8sIHdvcmxk" // echo -n 'hello, world' | base64
    val bytesMsg = ByteVector.fromValidBase64(base64Msg)

    val signedMessage = eclair.signMessage(bytesMsg)
    assert(signedMessage.nodeId == kit.nodeParams.nodeId)
    assert(signedMessage.message == base64Msg)

    val invalidSignature = (if (signedMessage.signature.head.toInt == 31) 32 else 31).toByte +: signedMessage.signature.tail
    val verifiedMessage = eclair.verifyMessage(bytesMsg, invalidSignature)
    assert(verifiedMessage.publicKey !== kit.nodeParams.nodeId)
  }

  test("get channel info (filtered channels)") { f =>
    import f._

    val eclair = new EclairImpl(kit)

    val a = randomKey().publicKey
    val b = randomKey().publicKey
    val a1 = randomBytes32()
    val a2 = randomBytes32()
    val b1 = randomBytes32()
    val map = Map(a1 -> a, a2 -> a, b1 -> b)

    eclair.channelsInfo(toRemoteNode_opt = None).pipeTo(sender.ref)

    register.expectMsg(Symbol("channels"))
    register.reply(map)

    val c1 = register.expectMsgType[Register.Forward[CMD_GET_CHANNEL_INFO]]
    register.reply(RES_GET_CHANNEL_INFO(map(c1.channelId), c1.channelId, NORMAL, ChannelCodecsSpec.normal))
    register.expectMsgType[Register.Forward[CMD_GET_CHANNEL_INFO]]
    register.reply(RES_FAILURE(CMD_GET_CHANNEL_INFO(ActorRef.noSender), new IllegalArgumentException("Non-standard channel")))
    val c3 = register.expectMsgType[Register.Forward[CMD_GET_CHANNEL_INFO]]
    register.reply(RES_GET_CHANNEL_INFO(map(c3.channelId), c3.channelId, NORMAL, ChannelCodecsSpec.normal))
    register.expectNoMessage()

    assert(sender.expectMsgType[Iterable[RES_GET_CHANNEL_INFO]].toSet == Set(
      RES_GET_CHANNEL_INFO(a, a1, NORMAL, ChannelCodecsSpec.normal),
      RES_GET_CHANNEL_INFO(b, b1, NORMAL, ChannelCodecsSpec.normal),
    ))
  }

  test("get channel info (using node id)") { f =>
    import f._

    val eclair = new EclairImpl(kit)

    val a = randomKey().publicKey
    val b = randomKey().publicKey
    val a1 = randomBytes32()
    val a2 = randomBytes32()
    val b1 = randomBytes32()
    val channels2Nodes = Map(a1 -> a, a2 -> a, b1 -> b)

    eclair.channelsInfo(toRemoteNode_opt = Some(a)).pipeTo(sender.ref)

    register.expectMsg(Symbol("channelsTo"))
    register.reply(channels2Nodes)

    val c1 = register.expectMsgType[Register.Forward[CMD_GET_CHANNEL_INFO]]
    register.reply(RES_GET_CHANNEL_INFO(channels2Nodes(c1.channelId), c1.channelId, NORMAL, ChannelCodecsSpec.normal))
    val c2 = register.expectMsgType[Register.Forward[CMD_GET_CHANNEL_INFO]]
    register.reply(RES_GET_CHANNEL_INFO(channels2Nodes(c2.channelId), c2.channelId, NORMAL, ChannelCodecsSpec.normal))
    register.expectNoMessage()

    assert(sender.expectMsgType[Iterable[RES_GET_CHANNEL_INFO]].toSet == Set(
      RES_GET_CHANNEL_INFO(a, a1, NORMAL, ChannelCodecsSpec.normal),
      RES_GET_CHANNEL_INFO(a, a2, NORMAL, ChannelCodecsSpec.normal),
    ))
  }

  test("get channel info (using channel id)") { f =>
    import f._

    val eclair = new EclairImpl(kit)

    val a = randomKey().publicKey
    val b = randomKey().publicKey
    val a1 = randomBytes32()
    val a2 = randomBytes32()
    val b1 = randomBytes32()
    val channels2Nodes = Map(a1 -> a, a2 -> a, b1 -> b)

    eclair.channelInfo(Left(a2)).pipeTo(sender.ref)

    val c1 = register.expectMsgType[Register.Forward[CMD_GET_CHANNEL_INFO]]
    register.reply(RES_GET_CHANNEL_INFO(channels2Nodes(c1.channelId), c1.channelId, NORMAL, ChannelCodecsSpec.normal))
    register.expectNoMessage()

    sender.expectMsg(RES_GET_CHANNEL_INFO(a, a2, NORMAL, ChannelCodecsSpec.normal))
  }

  test("get sent payment info") { f =>
    import f._

    val eclair = new EclairImpl(kit)

    // A first payment has been sent out and is currently pending.
    val pendingPayment1 = OutgoingPayment(UUID.randomUUID(), UUID.randomUUID(), None, randomBytes32(), "test", 500 msat, 750 msat, randomKey().publicKey, TimestampMilli.now(), None, None, OutgoingPaymentStatus.Pending)
    kit.nodeParams.db.payments.addOutgoingPayment(pendingPayment1)
    eclair.sentInfo(PaymentIdentifier.PaymentUUID(pendingPayment1.parentId)).pipeTo(sender.ref)
    sender.expectMsg(Seq(pendingPayment1))
    eclair.sentInfo(PaymentIdentifier.PaymentHash(pendingPayment1.paymentHash)).pipeTo(sender.ref)
    sender.expectMsg(Seq(pendingPayment1))

    // Payments must be queried by parentId, not child paymentId.
    eclair.sentInfo(PaymentIdentifier.PaymentUUID(pendingPayment1.id)).pipeTo(sender.ref)
    paymentInitiator.expectMsg(GetPayment(PaymentIdentifier.PaymentUUID(pendingPayment1.id)))
    paymentInitiator.reply(NoPendingPayment(PaymentIdentifier.PaymentUUID(pendingPayment1.id)))
    sender.expectMsg(Nil)

    // A second payment is pending in the payment initiator, but doesn't have a corresponding DB entry yet.
    val pendingPaymentId = UUID.randomUUID()
    val spontaneousPayment = SendSpontaneousPayment(600 msat, randomKey().publicKey, randomBytes32(), 5, routeParams = null)
    eclair.sentInfo(PaymentIdentifier.PaymentHash(spontaneousPayment.paymentHash)).pipeTo(sender.ref)
    paymentInitiator.expectMsg(GetPayment(PaymentIdentifier.PaymentHash(spontaneousPayment.paymentHash)))
    paymentInitiator.reply(PaymentIsPending(pendingPaymentId, spontaneousPayment.paymentHash, PendingSpontaneousPayment(ActorRef.noSender, spontaneousPayment)))
    val pendingPayment2 = sender.expectMsgType[Seq[OutgoingPayment]]
    assert(pendingPayment2.length == 1)
    assert(pendingPayment2.head.id == pendingPayment2.head.parentId)
    assert(pendingPayment2.head.id == pendingPaymentId)
    assert(pendingPayment2.head.paymentHash == spontaneousPayment.paymentHash)
    assert(pendingPayment2.head.status == OutgoingPaymentStatus.Pending)

    // A third payment is fully settled in the DB and not being retried.
    val failedAt = TimestampMilli.now()
    val failedPayment = OutgoingPayment(UUID.randomUUID(), UUID.randomUUID(), None, spontaneousPayment.paymentHash, "test", 700 msat, 900 msat, randomKey().publicKey, TimestampMilli.now(), None, None, OutgoingPaymentStatus.Failed(Nil, failedAt))
    kit.nodeParams.db.payments.addOutgoingPayment(failedPayment.copy(status = OutgoingPaymentStatus.Pending))
    kit.nodeParams.db.payments.updateOutgoingPayment(PaymentFailed(failedPayment.id, failedPayment.paymentHash, Nil, failedAt))
    eclair.sentInfo(PaymentIdentifier.PaymentUUID(failedPayment.parentId)).pipeTo(sender.ref)
    paymentInitiator.expectMsg(GetPayment(PaymentIdentifier.PaymentUUID(failedPayment.parentId)))
    paymentInitiator.reply(NoPendingPayment(PaymentIdentifier.PaymentUUID(failedPayment.parentId)))
    sender.expectMsg(Seq(failedPayment))

    // The failed payment is currently being retried.
    eclair.sentInfo(PaymentIdentifier.PaymentUUID(failedPayment.parentId)).pipeTo(sender.ref)
    paymentInitiator.expectMsg(GetPayment(PaymentIdentifier.PaymentUUID(failedPayment.parentId)))
    paymentInitiator.reply(PaymentIsPending(failedPayment.parentId, spontaneousPayment.paymentHash, PendingSpontaneousPayment(ActorRef.noSender, spontaneousPayment)))
    val pendingPayment3 = sender.expectMsgType[Seq[OutgoingPayment]]
    assert(pendingPayment3.length == 2)
    assert(pendingPayment3.head.id == failedPayment.parentId)
    assert(pendingPayment3.head.paymentHash == failedPayment.paymentHash)
    assert(pendingPayment3.head.status == OutgoingPaymentStatus.Pending)
    assert(pendingPayment3.last == failedPayment)
  }

  test("close channels") { f =>
    import f._

    val eclair = new EclairImpl(kit)

    val a = randomBytes32()
    val b = randomBytes32()

    eclair.close(List(Left(a), Left(b)), None, None).pipeTo(sender.ref)

    val c1 = register.expectMsgType[Register.Forward[CMD_CLOSE]]
    register.reply(RES_SUCCESS(c1.message, c1.channelId))
    val c2 = register.expectMsgType[Register.Forward[CMD_CLOSE]]
    register.reply(RES_SUCCESS(c2.message, c2.channelId))
    register.expectNoMessage()

    assert(sender.expectMsgType[Map[ChannelIdentifier, Either[Throwable, CommandResponse[CMD_CLOSE]]]] == Map(
      Left(a) -> Right(RES_SUCCESS(CMD_CLOSE(ActorRef.noSender, None, None), a)),
      Left(b) -> Right(RES_SUCCESS(CMD_CLOSE(ActorRef.noSender, None, None), b))
    ))
  }

  test("update relay fees in database") { f =>
    import f._

    val peersDb = mock[PeersDb]

    val databases = mock[Databases]
    databases.peers returns peersDb

    val kitWithMockDb = kit.copy(nodeParams = kit.nodeParams.copy(db = databases))
    val eclair = new EclairImpl(kitWithMockDb)

    val a = randomKey().publicKey
    val b = randomKey().publicKey
    val a1 = randomBytes32()
    val a2 = randomBytes32()
    val b1 = randomBytes32()
    val map = Map(a1 -> a, a2 -> a, b1 -> b)

    eclair.updateRelayFee(List(a, b), 999 msat, 1234).pipeTo(sender.ref)

    register.expectMsg(Symbol("channelsTo"))
    register.reply(map)

    val u1 = register.expectMsgType[Register.Forward[CMD_UPDATE_RELAY_FEE]]
    register.reply(RES_SUCCESS(u1.message, u1.channelId))
    val u2 = register.expectMsgType[Register.Forward[CMD_UPDATE_RELAY_FEE]]
    register.reply(RES_FAILURE(u2.message, CommandUnavailableInThisState(u2.channelId, "CMD_UPDATE_RELAY_FEE", channel.CLOSING)))
    val u3 = register.expectMsgType[Register.Forward[CMD_UPDATE_RELAY_FEE]]
    register.reply(Register.ForwardFailure(u3))
    register.expectNoMessage()

    assert(sender.expectMsgType[Map[ChannelIdentifier, Either[Throwable, CommandResponse[CMD_UPDATE_RELAY_FEE]]]] == Map(
      Left(a1) -> Right(RES_SUCCESS(CMD_UPDATE_RELAY_FEE(ActorRef.noSender, 999 msat, 1234, None), a1)),
      Left(a2) -> Right(RES_FAILURE(CMD_UPDATE_RELAY_FEE(ActorRef.noSender, 999 msat, 1234, None), CommandUnavailableInThisState(a2, "CMD_UPDATE_RELAY_FEE", channel.CLOSING))),
      Left(b1) -> Left(ChannelNotFound(Left(b1)))
    ))

    peersDb.addOrUpdateRelayFees(a, RelayFees(999 msat, 1234)).wasCalled(once)
    peersDb.addOrUpdateRelayFees(b, RelayFees(999 msat, 1234)).wasCalled(once)
  }

  test("channelBalances asks for all channels, usableBalances only for enabled ones") { f =>
    import f._

    val eclair = new EclairImpl(kit)

    eclair.channelBalances().pipeTo(sender.ref)
    relayer.expectMsg(GetOutgoingChannels(enabledOnly = false))
    eclair.usableBalances().pipeTo(sender.ref)
    relayer.expectMsg(GetOutgoingChannels())
  }

}
