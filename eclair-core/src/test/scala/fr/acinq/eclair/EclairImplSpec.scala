package fr.acinq.eclair

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import fr.acinq.bitcoin.{ByteVector32, Crypto}
import akka.util.Timeout
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.blockchain.TestWallet
import fr.acinq.eclair.io.Peer.OpenChannel
import fr.acinq.eclair.payment.PaymentLifecycle.SendPayment
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import org.scalatest.{Outcome, fixture}
import scodec.bits._
import TestConstants._
import fr.acinq.eclair.channel.{CMD_FORCECLOSE, Register}
import fr.acinq.eclair.payment.LocalPaymentHandler
import fr.acinq.eclair.router.RouteCalculationSpec.makeUpdate

import scala.util.{Failure, Success}
import scala.concurrent.duration._

class EclairImplSpec extends TestKit(ActorSystem("mySystem")) with fixture.FunSuiteLike {

  implicit val timeout = Timeout(30 seconds)

  case class FixtureParam(register: TestProbe, router: TestProbe, paymentInitiator: TestProbe, switchboard: TestProbe, kit: Kit)

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

    withFixture(test.toNoArgTest(FixtureParam(register, router, paymentInitiator, switchboard, kit)))
  }

  test("convert fee rate properly") { f =>
    import f._

    val eclair = new EclairImpl(kit)
    val nodeId = PublicKey(hex"030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87")

    // standard conversion
    eclair.open(nodeId, fundingSatoshis = 10000000L, pushMsat = None, fundingFeerateSatByte = Some(5), flags = None, openTimeout_opt = None)
    val open = switchboard.expectMsgType[OpenChannel]
    assert(open.fundingTxFeeratePerKw_opt == Some(1250))

    // check that minimum fee rate of 253 sat/bw is used
    eclair.open(nodeId, fundingSatoshis = 10000000L, pushMsat = None, fundingFeerateSatByte = Some(1), flags = None, openTimeout_opt = None)
    val open1 = switchboard.expectMsgType[OpenChannel]
    assert(open1.fundingTxFeeratePerKw_opt == Some(MinimumFeeratePerKw))
  }

  test("call send with passing correct arguments") { f =>
    import f._

    val eclair = new EclairImpl(kit)
    val nodeId = PublicKey(hex"030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87")

    eclair.send(recipientNodeId = nodeId, amountMsat = 123, paymentHash = ByteVector32.Zeroes, assistedRoutes = Seq.empty, minFinalCltvExpiry_opt = None)
    val send = paymentInitiator.expectMsgType[SendPayment]
    assert(send.targetNodeId == nodeId)
    assert(send.amountMsat == 123)
    assert(send.paymentHash == ByteVector32.Zeroes)
    assert(send.assistedRoutes == Seq.empty)

    // with assisted routes
    val hints = Seq(Seq(ExtraHop(Bob.nodeParams.nodeId, ShortChannelId("569178x2331x1"), feeBaseMsat = 10, feeProportionalMillionths = 1, cltvExpiryDelta = 12)))
    eclair.send(recipientNodeId = nodeId, amountMsat = 123, paymentHash = ByteVector32.Zeroes, assistedRoutes = hints, minFinalCltvExpiry_opt = None)
    val send1 = paymentInitiator.expectMsgType[SendPayment]
    assert(send1.targetNodeId == nodeId)
    assert(send1.amountMsat == 123)
    assert(send1.paymentHash == ByteVector32.Zeroes)
    assert(send1.assistedRoutes == hints)

    // with finalCltvExpiry and failures response
    eclair.send(recipientNodeId = nodeId, amountMsat = 123, paymentHash = ByteVector32.Zeroes, assistedRoutes = Seq.empty, minFinalCltvExpiry_opt = Some(96))
    val send2 = paymentInitiator.expectMsgType[SendPayment]
    assert(send2.targetNodeId == nodeId)
    assert(send2.amountMsat == 123)
    assert(send2.paymentHash == ByteVector32.Zeroes)
    assert(send2.finalCltvExpiry == 96)
  }

  test("allupdates can filter by nodeId") { f =>
    import f._

    val (a, b, c, d, e) = (randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey)

    val updates = List(
      makeUpdate(1L, a, b, feeBaseMsat = 0, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 13),
      makeUpdate(4L, a, e, feeBaseMsat = 0, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 12),
      makeUpdate(2L, b, c, feeBaseMsat = 1, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 500),
      makeUpdate(3L, c, d, feeBaseMsat = 1, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 500),
      makeUpdate(7L, e, c, feeBaseMsat = 2, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 12)
    ).toMap

    val eclair = new EclairImpl(kit)
    val fResp = eclair.allUpdates(Some(b)) // ask updates filtered by 'b'
    f.router.expectMsg('updatesMap)

    f.router.reply(updates)

    awaitCond({
      fResp.value match {
        // check if the response must contains updates only for 'b'
        case Some(Success(res)) => res.forall { u => updates.exists(entry => entry._2.shortChannelId == u.shortChannelId && entry._1.a == b || entry._1.b == b) }
        case _ => false
      }
    })
  }

  test("forceclose should work both with channelId and shortChannelId") { f =>
    import f._

    val eclair = new EclairImpl(kit)

    eclair.forceClose(Left(ByteVector32.Zeroes))
    register.expectMsg(Register.Forward(ByteVector32.Zeroes, CMD_FORCECLOSE))

    eclair.forceClose(Right(ShortChannelId("568749x2597x0")))
    register.expectMsg(Register.ForwardShortId(ShortChannelId("568749x2597x0"), CMD_FORCECLOSE))
  }

  test("passing a preimage to /createinvoice should result in an invoice with payment_hash=H(preimage)") { fixture =>

    val paymentHandler = system.actorOf(LocalPaymentHandler.props(Alice.nodeParams))
    val kitWithPaymentHandler = fixture.kit.copy(paymentHandler = paymentHandler)
    val eclair = new EclairImpl(kitWithPaymentHandler)
    val preimage = randomBytes32

    val fResp = eclair.receive(description = "some desc", amountMsat = None, expire = None, fallbackAddress = None, preimage = Some(preimage))
    awaitCond({
      fResp.value match {
        case Some(Success(pr)) => pr.paymentHash == Crypto.sha256(preimage)
        case _ => false
      }
    })

  }

}
