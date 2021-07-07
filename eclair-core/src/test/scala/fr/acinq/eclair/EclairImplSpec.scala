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

import akka.actor.ActorRef
import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import akka.testkit.TestProbe
import akka.util.Timeout
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{Block, ByteVector32, ByteVector64, Crypto, SatoshiLong}
import fr.acinq.eclair.TestConstants._
import fr.acinq.eclair.blockchain.TestWallet
import fr.acinq.eclair.blockchain.fee.{FeeratePerByte, FeeratePerKw}
import fr.acinq.eclair.channel.{CMD_FORCECLOSE, Register, _}
import fr.acinq.eclair.db._
import fr.acinq.eclair.io.Peer.OpenChannel
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.payment.receive.MultiPartHandler.ReceivePayment
import fr.acinq.eclair.payment.receive.PaymentHandler
import fr.acinq.eclair.payment.send.PaymentInitiator.{SendPayment, SendPaymentToRoute, SendSpontaneousPayment}
import fr.acinq.eclair.router.RouteCalculationSpec.makeUpdateShort
import fr.acinq.eclair.router.Router.{GetNetworkStats, GetNetworkStatsResponse, PredefinedNodeRoute, PublicChannel}
import fr.acinq.eclair.router.{Announcements, NetworkStats, Router, Stats}
import fr.acinq.eclair.wire.protocol.{Color, NodeAnnouncement}
import org.mockito.Mockito
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, ParallelTestExecution}
import scodec.bits._

import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Success

class EclairImplSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with IdiomaticMockito with ParallelTestExecution {
  implicit val timeout: Timeout = Timeout(30 seconds)

  case class FixtureParam(register: TestProbe, router: TestProbe, paymentInitiator: TestProbe, switchboard: TestProbe, paymentHandler: TestProbe, kit: Kit)

  override def withFixture(test: OneArgTest): Outcome = {
    val watcher = TestProbe()
    val paymentHandler = TestProbe()
    val register = TestProbe()
    val relayer = TestProbe()
    val router = TestProbe()
    val switchboard = TestProbe()
    val paymentInitiator = TestProbe()
    val server = TestProbe()
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
      new TestWallet()
    )

    withFixture(test.toNoArgTest(FixtureParam(register, router, paymentInitiator, switchboard, paymentHandler, kit)))
  }

  test("convert fee rate properly") { f =>
    import f._

    val eclair = new EclairImpl(kit)
    val nodeId = PublicKey(hex"030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87")

    // standard conversion
    eclair.open(nodeId, fundingAmount = 10000000L sat, pushAmount_opt = None, channelType_opt = None, fundingFeeratePerByte_opt = Some(FeeratePerByte(5 sat)), initialRelayFees_opt = None, flags_opt = None, openTimeout_opt = None)
    val open = switchboard.expectMsgType[OpenChannel]
    assert(open.fundingTxFeeratePerKw_opt === Some(FeeratePerKw(1250 sat)))

    // check that minimum fee rate of 253 sat/bw is used
    eclair.open(nodeId, fundingAmount = 10000000L sat, pushAmount_opt = None, channelType_opt = Some(ChannelTypes.StaticRemoteKey), fundingFeeratePerByte_opt = Some(FeeratePerByte(1 sat)), initialRelayFees_opt = None, flags_opt = None, openTimeout_opt = None)
    val open1 = switchboard.expectMsgType[OpenChannel]
    assert(open1.fundingTxFeeratePerKw_opt === Some(FeeratePerKw.MinimumFeeratePerKw))
    assert(open1.channelType_opt === Some(ChannelTypes.StaticRemoteKey))
  }

  test("call send with passing correct arguments") { f =>
    import f._

    val eclair = new EclairImpl(kit)
    val nodePrivKey = randomKey()
    val invoice0 = PaymentRequest(Block.RegtestGenesisBlock.hash, Some(123 msat), ByteVector32.Zeroes, nodePrivKey, "description", CltvExpiryDelta(18))
    eclair.send(None, 123 msat, invoice0)
    val send = paymentInitiator.expectMsgType[SendPayment]
    assert(send.externalId === None)
    assert(send.recipientNodeId === nodePrivKey.publicKey)
    assert(send.recipientAmount === 123.msat)
    assert(send.paymentHash === ByteVector32.Zeroes)
    assert(send.paymentRequest === invoice0)
    assert(send.assistedRoutes === Seq.empty)

    // with assisted routes
    val externalId1 = "030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87"
    val hints = List(List(ExtraHop(Bob.nodeParams.nodeId, ShortChannelId("569178x2331x1"), feeBase = 10 msat, feeProportionalMillionths = 1, cltvExpiryDelta = CltvExpiryDelta(12))))
    val invoice1 = PaymentRequest(Block.RegtestGenesisBlock.hash, Some(123 msat), ByteVector32.Zeroes, nodePrivKey, "description", CltvExpiryDelta(18), None, None, hints)
    eclair.send(Some(externalId1), 123 msat, invoice1)
    val send1 = paymentInitiator.expectMsgType[SendPayment]
    assert(send1.externalId === Some(externalId1))
    assert(send1.recipientNodeId === nodePrivKey.publicKey)
    assert(send1.recipientAmount === 123.msat)
    assert(send1.paymentHash === ByteVector32.Zeroes)
    assert(send1.paymentRequest === invoice1)
    assert(send1.assistedRoutes === hints)

    // with finalCltvExpiry
    val externalId2 = "487da196-a4dc-4b1e-92b4-3e5e905e9f3f"
    val invoice2 = PaymentRequest("lntb", Some(123 msat), System.currentTimeMillis() / 1000L, nodePrivKey.publicKey, List(PaymentRequest.MinFinalCltvExpiry(96), PaymentRequest.PaymentHash(ByteVector32.Zeroes), PaymentRequest.Description("description")), ByteVector.empty)
    eclair.send(Some(externalId2), 123 msat, invoice2)
    val send2 = paymentInitiator.expectMsgType[SendPayment]
    assert(send2.externalId === Some(externalId2))
    assert(send2.recipientNodeId === nodePrivKey.publicKey)
    assert(send2.recipientAmount === 123.msat)
    assert(send2.paymentHash === ByteVector32.Zeroes)
    assert(send2.paymentRequest === invoice2)
    assert(send2.fallbackFinalExpiryDelta === CltvExpiryDelta(96))

    // with custom route fees parameters
    eclair.send(None, 123 msat, invoice0, feeThreshold_opt = Some(123 sat), maxFeePct_opt = Some(4.20))
    val send3 = paymentInitiator.expectMsgType[SendPayment]
    assert(send3.externalId === None)
    assert(send3.recipientNodeId === nodePrivKey.publicKey)
    assert(send3.recipientAmount === 123.msat)
    assert(send3.paymentHash === ByteVector32.Zeroes)
    assert(send3.routeParams.get.maxFeeBase === 123000.msat) // conversion sat -> msat
    assert(send3.routeParams.get.maxFeePct === 4.20)

    val invalidExternalId = "Robert'); DROP TABLE received_payments; DROP TABLE sent_payments; DROP TABLE payments;"
    assertThrows[IllegalArgumentException](Await.result(eclair.send(Some(invalidExternalId), 123 msat, invoice0), 50 millis))

    val expiredInvoice = invoice2.copy(timestamp = 0L)
    assertThrows[IllegalArgumentException](Await.result(eclair.send(None, 123 msat, expiredInvoice), 50 millis))
  }

  test("return node announcements") { f =>
    import f._

    val eclair = new EclairImpl(kit)
    val remoteNodeAnn1 = NodeAnnouncement(randomBytes64(), Features.empty, 42L, randomKey().publicKey, Color(42, 42, 42), "LN-rocks", Nil)
    val remoteNodeAnn2 = NodeAnnouncement(randomBytes64(), Features.empty, 43L, randomKey().publicKey, Color(43, 43, 43), "LN-papers", Nil)
    val allNodes = Seq(
      NodeAnnouncement(randomBytes64(), Features.empty, 561L, randomKey().publicKey, Color(0, 0, 0), "some-node", Nil),
      remoteNodeAnn1,
      remoteNodeAnn2,
      NodeAnnouncement(randomBytes64(), Features.empty, 1105L, randomKey().publicKey, Color(0, 0, 0), "some-other-node", Nil),
    )

    {
      val fRes = eclair.nodes()
      router.expectMsg(Router.GetNodes)
      router.reply(allNodes)
      awaitCond(fRes.value match {
        case Some(Success(nodes)) =>
          assert(nodes.toSet === allNodes.toSet)
          true
        case _ => false
      })
    }
    {
      val fRes = eclair.nodes(Some(Set(remoteNodeAnn1.nodeId, remoteNodeAnn2.nodeId)))
      router.expectMsg(Router.GetNodes)
      router.reply(allNodes)
      awaitCond(fRes.value match {
        case Some(Success(nodes)) =>
          assert(nodes.toSet === Set(remoteNodeAnn1, remoteNodeAnn2))
          true
        case _ => false
      })
    }
    {
      val fRes = eclair.nodes(Some(Set(randomKey().publicKey)))
      router.expectMsg(Router.GetNodes)
      router.reply(allNodes)
      awaitCond(fRes.value match {
        case Some(Success(nodes)) =>
          assert(nodes.isEmpty)
          true
        case _ => false
      })
    }
  }

  test("allupdates can filter by nodeId") { f =>
    import f._

    val (a_priv, b_priv, c_priv, d_priv, e_priv) = (
      PrivateKey(hex"3580a881ac24eb00530a51235c42bcb65424ba121e2e7d910a70fa531a578d21"),
      PrivateKey(hex"f6a353f7a5de654501c3495acde7450293f74d09086c2b7c9a4e524248d0daac"),
      PrivateKey(hex"c2efe0095f9113bc5b9f4140958670a8ea2afc3ed50fb32ea9c809f82b3b0374"),
      PrivateKey(hex"216414970b4216b197a1040367419ad6922f80e8b73ced083e9afe5e6ddd8e4d"),
      PrivateKey(hex"216414970b4216b197a1040367419ad6922f80e8b73ced083e9afe5e6ddd8e4e"))

    val (a, b, c, d, e) = (a_priv.publicKey, b_priv.publicKey, c_priv.publicKey, d_priv.publicKey, e_priv.publicKey)
    val ann_ab = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, ShortChannelId(1), a, b, a, b, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes)
    val ann_ae = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, ShortChannelId(4), a, e, a, e, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes)
    val ann_bc = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, ShortChannelId(2), b, c, b, c, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes)
    val ann_cd = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, ShortChannelId(3), c, d, c, d, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes)
    val ann_ec = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, ShortChannelId(7), e, c, e, c, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes)

    assert(Announcements.isNode1(a, b))
    assert(Announcements.isNode1(b, c))

    var channels = scala.collection.immutable.SortedMap.empty[ShortChannelId, PublicChannel]

    List(
      (ann_ab, makeUpdateShort(ShortChannelId(1L), a, b, feeBase = 0 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(13))),
      (ann_ae, makeUpdateShort(ShortChannelId(4L), a, e, feeBase = 0 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(12))),
      (ann_bc, makeUpdateShort(ShortChannelId(2L), b, c, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(500))),
      (ann_cd, makeUpdateShort(ShortChannelId(3L), c, d, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(500))),
      (ann_ec, makeUpdateShort(ShortChannelId(7L), e, c, feeBase = 2 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(12)))
    ).foreach { case (ann, update) =>
      channels = channels + (update.shortChannelId -> PublicChannel(ann, ByteVector32.Zeroes, 100 sat, Some(update.copy(channelFlags = 0)), None, None))
    }

    val mockNetworkDb = mock[NetworkDb]
    mockNetworkDb.listChannels() returns channels
    mockNetworkDb.listNodes() returns Seq.empty
    Mockito.doNothing().when(mockNetworkDb).removeNode(kit.nodeParams.nodeId)

    val mockDB = mock[Databases]
    mockDB.network returns mockNetworkDb

    val mockNodeParams = kit.nodeParams.copy(db = mockDB)
    val mockRouter = system.actorOf(Router.props(mockNodeParams, TestProbe().ref))

    val eclair = new EclairImpl(kit.copy(router = mockRouter, nodeParams = mockNodeParams))
    val fResp = eclair.allUpdates(Some(b)) // ask updates filtered by 'b'

    awaitCond({
      fResp.value match {
        // check if the response contains updates only for 'b'
        case Some(Success(res)) => res.map(_.shortChannelId).toList == List(ShortChannelId(2))
        case _ => false
      }
    })
  }

  test("router returns Network Stats") { f =>
    import f._

    val capStat = Stats(30 sat, 12 sat, 14 sat, 20 sat, 40 sat, 46 sat, 48 sat)
    val cltvStat = Stats(CltvExpiryDelta(32), CltvExpiryDelta(11), CltvExpiryDelta(13), CltvExpiryDelta(22), CltvExpiryDelta(42), CltvExpiryDelta(51), CltvExpiryDelta(53))
    val feeBaseStat = Stats(32 msat, 11 msat, 13 msat, 22 msat, 42 msat, 51 msat, 53 msat)
    val feePropStat = Stats(32L, 11L, 13L, 22L, 42L, 51L, 53L)
    val eclair = new EclairImpl(kit)
    val fResp = eclair.networkStats()
    f.router.expectMsg(GetNetworkStats)

    f.router.reply(GetNetworkStatsResponse(Some(new NetworkStats(1, 2, capStat, cltvStat, feeBaseStat, feePropStat))))

    awaitCond({
      fResp.value match {
        case Some(Success(Some(res))) => res.channels == 1
        case _ => false
      }
    })

  }

  test("close and forceclose should work both with channelId and shortChannelId") { f =>
    import f._

    val eclair = new EclairImpl(kit)

    eclair.forceClose(Left(ByteVector32.Zeroes) :: Nil)
    register.expectMsg(Register.Forward(ActorRef.noSender, ByteVector32.Zeroes, CMD_FORCECLOSE(ActorRef.noSender)))

    eclair.forceClose(Right(ShortChannelId("568749x2597x0")) :: Nil)
    register.expectMsg(Register.ForwardShortId(ActorRef.noSender, ShortChannelId("568749x2597x0"), CMD_FORCECLOSE(ActorRef.noSender)))

    eclair.forceClose(Left(ByteVector32.Zeroes) :: Right(ShortChannelId("568749x2597x0")) :: Nil)
    register.expectMsgAllOf(
      Register.Forward(ActorRef.noSender, ByteVector32.Zeroes, CMD_FORCECLOSE(ActorRef.noSender)),
      Register.ForwardShortId(ActorRef.noSender, ShortChannelId("568749x2597x0"), CMD_FORCECLOSE(ActorRef.noSender))
    )

    eclair.close(Left(ByteVector32.Zeroes) :: Nil, None)
    register.expectMsg(Register.Forward(ActorRef.noSender, ByteVector32.Zeroes, CMD_CLOSE(ActorRef.noSender, None)))

    eclair.close(Right(ShortChannelId("568749x2597x0")) :: Nil, None)
    register.expectMsg(Register.ForwardShortId(ActorRef.noSender, ShortChannelId("568749x2597x0"), CMD_CLOSE(ActorRef.noSender, None)))

    eclair.close(Right(ShortChannelId("568749x2597x0")) :: Nil, Some(ByteVector.empty))
    register.expectMsg(Register.ForwardShortId(ActorRef.noSender, ShortChannelId("568749x2597x0"), CMD_CLOSE(ActorRef.noSender, Some(ByteVector.empty))))

    eclair.close(Right(ShortChannelId("568749x2597x0")) :: Left(ByteVector32.One) :: Right(ShortChannelId("568749x2597x1")) :: Nil, None)
    register.expectMsgAllOf(
      Register.ForwardShortId(ActorRef.noSender, ShortChannelId("568749x2597x0"), CMD_CLOSE(ActorRef.noSender, None)),
      Register.Forward(ActorRef.noSender, ByteVector32.One, CMD_CLOSE(ActorRef.noSender, None)),
      Register.ForwardShortId(ActorRef.noSender, ShortChannelId("568749x2597x1"), CMD_CLOSE(ActorRef.noSender, None))
    )
  }

  test("receive should have an optional fallback address and use millisatoshi") { f =>
    import f._

    val fallBackAddressRaw = "muhtvdmsnbQEPFuEmxcChX58fGvXaaUoVt"
    val eclair = new EclairImpl(kit)
    eclair.receive("some desc", Some(123 msat), Some(456), Some(fallBackAddressRaw), None)
    val receive = paymentHandler.expectMsgType[ReceivePayment]

    assert(receive.amount_opt === Some(123 msat))
    assert(receive.expirySeconds_opt === Some(456))
    assert(receive.fallbackAddress === Some(fallBackAddressRaw))

    // try with wrong address format
    assertThrows[IllegalArgumentException](eclair.receive("some desc", Some(123 msat), Some(456), Some("wassa wassa"), None))
  }

  test("passing a payment_preimage to /createinvoice should result in an invoice with payment_hash=H(payment_preimage)") { f =>
    import f._

    val kitWithPaymentHandler = kit.copy(paymentHandler = system.actorOf(PaymentHandler.props(Alice.nodeParams, TestProbe().ref)))
    val eclair = new EclairImpl(kitWithPaymentHandler)
    val paymentPreimage = randomBytes32()

    val fResp = eclair.receive("some desc", None, None, None, Some(paymentPreimage))
    awaitCond({
      fResp.value match {
        case Some(Success(pr)) => pr.paymentHash == Crypto.sha256(paymentPreimage)
        case _ => false
      }
    })
  }

  test("networkFees/audit/allinvoices should use a default to/from filter expressed in seconds") { f =>
    import f._

    val auditDb = mock[AuditDb]
    val paymentDb = mock[PaymentsDb]

    auditDb.listNetworkFees(anyLong, anyLong) returns Seq.empty
    auditDb.listSent(anyLong, anyLong) returns Seq.empty
    auditDb.listReceived(anyLong, anyLong) returns Seq.empty
    auditDb.listRelayed(anyLong, anyLong) returns Seq.empty
    paymentDb.listIncomingPayments(anyLong, anyLong) returns Seq.empty

    val databases = mock[Databases]
    databases.audit returns auditDb
    databases.payments returns paymentDb

    val kitWithMockAudit = kit.copy(nodeParams = kit.nodeParams.copy(db = databases))
    val eclair = new EclairImpl(kitWithMockAudit)

    Await.result(eclair.networkFees(None, None), 10 seconds)
    auditDb.listNetworkFees(0, TimestampQueryFilters.MaxEpochMilliseconds).wasCalled(once) // assert the call was made only once and with the specified params

    Await.result(eclair.audit(None, None), 10 seconds)
    auditDb.listRelayed(0, TimestampQueryFilters.MaxEpochMilliseconds).wasCalled(once)
    auditDb.listReceived(0, TimestampQueryFilters.MaxEpochMilliseconds).wasCalled(once)
    auditDb.listSent(0, TimestampQueryFilters.MaxEpochMilliseconds).wasCalled(once)

    Await.result(eclair.allInvoices(None, None), 10 seconds)
    paymentDb.listIncomingPayments(0, TimestampQueryFilters.MaxEpochMilliseconds).wasCalled(once) // assert the call was made only once and with the specified params
  }

  test("sendtoroute should pass the parameters correctly") { f =>
    import f._

    val eclair = new EclairImpl(kit)
    val route = PredefinedNodeRoute(Seq(randomKey().publicKey))
    val trampolines = Seq(randomKey().publicKey, randomKey().publicKey)
    val parentId = UUID.randomUUID()
    val secret = randomBytes32()
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(1234 msat), ByteVector32.One, randomKey(), "Some invoice", CltvExpiryDelta(18))
    eclair.sendToRoute(1000 msat, Some(1200 msat), Some("42"), Some(parentId), pr, CltvExpiryDelta(123), route, Some(secret), Some(100 msat), Some(CltvExpiryDelta(144)), trampolines)

    paymentInitiator.expectMsg(SendPaymentToRoute(1000 msat, 1200 msat, pr, CltvExpiryDelta(123), route, Some("42"), Some(parentId), Some(secret), 100 msat, CltvExpiryDelta(144), trampolines))
  }

  test("call sendWithPreimage, which generates a random preimage, to perform a KeySend payment") { f =>
    import f._

    val eclair = new EclairImpl(kit)
    val nodeId = randomKey().publicKey

    eclair.sendWithPreimage(None, nodeId, 12345 msat)
    val send = paymentInitiator.expectMsgType[SendSpontaneousPayment]
    assert(send.externalId === None)
    assert(send.recipientNodeId === nodeId)
    assert(send.recipientAmount === 12345.msat)
    assert(send.paymentHash === Crypto.sha256(send.paymentPreimage))
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
    assert(send.externalId === None)
    assert(send.recipientNodeId === nodeId)
    assert(send.recipientAmount === 12345.msat)
    assert(send.paymentPreimage === expectedPaymentPreimage)
    assert(send.paymentHash === expectedPaymentHash)
    assert(send.userCustomTlvs.isEmpty)
  }

  test("sign and verify an arbitrary message with the node's private key") { f =>
    import f._

    val eclair = new EclairImpl(kit)

    val base64Msg = "aGVsbG8sIHdvcmxk" // echo -n 'hello, world' | base64
    val bytesMsg = ByteVector.fromValidBase64(base64Msg)

    val signedMessage: SignedMessage = eclair.signMessage(bytesMsg)
    assert(signedMessage.nodeId === kit.nodeParams.nodeId)
    assert(signedMessage.message === base64Msg)

    val verifiedMessage: VerifiedMessage = eclair.verifyMessage(bytesMsg, signedMessage.signature)
    assert(verifiedMessage.valid)
    assert(verifiedMessage.publicKey === kit.nodeParams.nodeId)

    val prefix = ByteVector("Lightning Signed Message:".getBytes)
    val dhash256 = Crypto.hash256(prefix ++ bytesMsg)
    val expectedDigest = ByteVector32(hex"cbedbc1542fb139e2e10954f1ff9f82e8a1031cc63260636bbc45a90114552ea")
    assert(dhash256 === expectedDigest)
    assert(Crypto.verifySignature(dhash256, ByteVector64(signedMessage.signature.tail), kit.nodeParams.nodeId))
  }

  test("verify an invalid signature for the given message") { f =>
    import f._

    val eclair = new EclairImpl(kit)

    val base64Msg = "aGVsbG8sIHdvcmxk" // echo -n 'hello, world' | base64
    val bytesMsg = ByteVector.fromValidBase64(base64Msg)

    val signedMessage: SignedMessage = eclair.signMessage(bytesMsg)
    assert(signedMessage.nodeId === kit.nodeParams.nodeId)
    assert(signedMessage.message === base64Msg)

    val wrongMsg = ByteVector.fromValidBase64(base64Msg.tail)
    val verifiedMessage: VerifiedMessage = eclair.verifyMessage(wrongMsg, signedMessage.signature)
    assert(verifiedMessage.valid)
    assert(verifiedMessage.publicKey !== kit.nodeParams.nodeId)
  }

  test("ensure that an invalid recoveryId cause the signature verification to fail") { f =>
    import f._

    val eclair = new EclairImpl(kit)

    val base64Msg = "aGVsbG8sIHdvcmxk" // echo -n 'hello, world' | base64
    val bytesMsg = ByteVector.fromValidBase64(base64Msg)

    val signedMessage: SignedMessage = eclair.signMessage(bytesMsg)
    assert(signedMessage.nodeId === kit.nodeParams.nodeId)
    assert(signedMessage.message === base64Msg)

    val invalidSignature = (if (signedMessage.signature.head.toInt == 31) 32 else 31).toByte +: signedMessage.signature.tail
    val verifiedMessage: VerifiedMessage = eclair.verifyMessage(bytesMsg, invalidSignature)
    assert(verifiedMessage.publicKey !== kit.nodeParams.nodeId)
  }

}
