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

package fr.acinq.eclair.api

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.FormData
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest, WSProbe}
import akka.util.Timeout
import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{Block, ByteVector32, SatoshiLong}
import fr.acinq.eclair.ApiTypes.ChannelIdentifier
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features.{ChannelRangeQueriesExtended, OptionDataLossProtect}
import fr.acinq.eclair._
import fr.acinq.eclair.api.directives.{EclairDirectives, ErrorResponse}
import fr.acinq.eclair.api.serde.JsonSupport
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.ChannelOpenResponse.ChannelOpened
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db._
import fr.acinq.eclair.io.NodeURI
import fr.acinq.eclair.io.Peer.PeerInfo
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.relay.Relayer.UsableBalance
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle.PreimageReceived
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentToRouteResponse
import fr.acinq.eclair.router.Router.PredefinedNodeRoute
import fr.acinq.eclair.router.{NetworkStats, Stats}
import fr.acinq.eclair.wire.protocol.{Color, NodeAddress}
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scodec.bits._

import java.util.UUID
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.io.Source
import scala.reflect.ClassTag
import scala.util.Try

class ApiServiceSpec extends AnyFunSuite with ScalatestRouteTest with IdiomaticMockito with Matchers {

  implicit val formats = JsonSupport.formats
  implicit val serialization = JsonSupport.serialization
  implicit val routeTestTimeout = RouteTestTimeout(3 seconds)

  val aliceNodeId = PublicKey(hex"03af0ed6052cf28d670665549bc86f4b721c9fdb309d40c58f5811f63966e005d0")
  val bobNodeId = PublicKey(hex"039dc0e0b1d25905e44fdf6f8e89755a5e219685840d0bc1d28d3308f9628a3585")

  object PluginApi extends RouteProvider {
    override def route(directives: EclairDirectives): Route = {
      import directives._
      val route1 = postRequest("plugin-test") { implicit t =>
        complete("fine")
      }

      val route2 = postRequest("payinvoice") { implicit t =>
        complete("gets overridden by base API endpoint")
      }

      route1 ~ route2
    }
  }

  class MockService(eclair: Eclair) extends Service {
    override val eclairApi: Eclair = eclair

    override def password: String = "mock"

    override implicit val actorSystem: ActorSystem = system

    val route: Route = finalRoutes(PluginApi :: Nil)
  }

  def mockApi(eclair: Eclair = mock[Eclair]): MockService = {
    new MockService(eclair)
  }

  test("API returns unauthorized without basic credentials") {
    Post("/getinfo") ~>
      Route.seal(mockApi().route) ~>
      check {
        assert(handled)
        assert(status == Unauthorized)
      }
  }

  test("API returns not found for broken getinfo") {
    Post("/getinf") ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockApi().route) ~>
      check {
        assert(handled)
        assert(status == NotFound)
      }
  }

  test("API returns unauthorized with invalid credentials") {
    Post("/getinfo") ~>
      addCredentials(BasicHttpCredentials("", mockApi().password + "what!")) ~>
      Route.seal(mockApi().route) ~>
      check {
        assert(handled)
        assert(status == Unauthorized)
      }
  }

  test("API returns 404 for invalid route") {
    Post("/mistake") ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockApi().route) ~>
      check {
        assert(handled)
        assert(status == NotFound)
      }
  }

  test("API returns invalid channelId on invalid channelId form data") {
    Post("/channel", FormData(Map("channelId" -> "hey")).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockApi().route) ~>
      check {
        assert(handled)
        assert(status == BadRequest)
        val resp = entityAs[ErrorResponse](Json4sSupport.unmarshaller, ClassTag(classOf[ErrorResponse]))
        assert(resp.error == "The form field 'channelId' was malformed:\nInvalid hexadecimal character 'h' at index 0")
      }
  }

  test("API should return bad request error on connect with missing uri") {
    Post("/connect", FormData("urb" -> "030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87@93.137.102.239:9735").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockApi().connect) ~>
      check {
        assert(handled)
        assert(status == BadRequest)
      }
  }

  test("'peers' should ask the switchboard for current known peers") {
    val eclair = mock[Eclair]

    eclair.peers()(any[Timeout]) returns Future.successful(List(
      PeerInfo(
        nodeId = aliceNodeId,
        state = "CONNECTED",
        address = Some(NodeAddress.fromParts("localhost", 9731).get.socketAddress),
        channels = 1),
      PeerInfo(
        nodeId = bobNodeId,
        state = "DISCONNECTED",
        address = None,
        channels = 1)))

    val mockService = mockApi(eclair)
    Post("/peers") ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.peers) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        eclair.peers()(any[Timeout]).wasCalled(once)
        matchTestJson("peers", response)
      }
  }

  test("plugin injects its own route") {
    Post("/plugin-test") ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockApi().route) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(entityAs[String] == "fine")
      }
  }

  test("'usablebalances' asks relayer for current usable balances") {
    val eclair = mock[Eclair]
    eclair.usableBalances()(any[Timeout]) returns Future.successful(List(
      UsableBalance(aliceNodeId, ShortChannelId(1), 100000000 msat, 20000000 msat, isPublic = true),
      UsableBalance(aliceNodeId, ShortChannelId(2), 400000000 msat, 30000000 msat, isPublic = false)
    ))

    val mockService = mockApi(eclair)
    Post("/usablebalances") ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.usableBalances) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        eclair.usableBalances()(any[Timeout]).wasCalled(once)
        matchTestJson("usablebalances", response)
      }
  }

  test("'getinfo' response should include this node ID") {
    val eclair = mock[Eclair]
    val mockService = new MockService(eclair)
    eclair.getInfo()(any[Timeout]) returns Future.successful(GetInfoResponse(
      version = "1.0.0-SNAPSHOT-e3f1ec0",
      color = Color(0.toByte, 1.toByte, 2.toByte).toString,
      features = Features(OptionDataLossProtect -> Mandatory, ChannelRangeQueriesExtended -> Optional),
      nodeId = aliceNodeId,
      alias = "alice",
      chainHash = ByteVector32(hex"06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f"),
      network = "regtest",
      blockHeight = 9999,
      publicAddresses = NodeAddress.fromParts("localhost", 9731).get :: Nil,
      instanceId = "01234567-0123-4567-89ab-0123456789ab"
    ))

    Post("/getinfo") ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.getInfo) ~>
      check {
        assert(handled)
        assert(status == OK)
        val resp = entityAs[String]
        assert(resp.contains(aliceNodeId.toString))
        eclair.getInfo()(any[Timeout]).wasCalled(once)
        matchTestJson("getinfo", resp)
      }
  }

  test("'open' channels") {
    val nodeId = PublicKey(hex"030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87")
    val channelId = ByteVector32(hex"56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e")

    val eclair = mock[Eclair]
    eclair.open(any, any, any, any, any, any, any, any)(any[Timeout]) returns Future.successful(ChannelOpened(channelId))
    val mockService = new MockService(eclair)

    Post("/open", FormData("nodeId" -> nodeId.toString(), "fundingSatoshis" -> "100000").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.open) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(entityAs[String] == "\"created channel 56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e\"")
        eclair.open(nodeId, 100000 sat, None, None, None, None, None, None)(any[Timeout]).wasCalled(once)
      }

    Post("/open", FormData("nodeId" -> nodeId.toString(), "fundingSatoshis" -> "50000", "feeBaseMsat" -> "100", "feeProportionalMillionths" -> "10").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.open) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(entityAs[String] == "\"created channel 56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e\"")
        eclair.open(nodeId, 50000 sat, None, None, None, Some(100 msat, 10), None, None)(any[Timeout]).wasCalled(once)
      }

    Post("/open", FormData("nodeId" -> nodeId.toString(), "fundingSatoshis" -> "25000", "feeBaseMsat" -> "250", "feeProportionalMillionths" -> "10").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(entityAs[String] == "\"created channel 56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e\"")
        eclair.open(nodeId, 25000 sat, None, None, None, Some(250 msat, 10), None, None)(any[Timeout]).wasCalled(once)
      }

    Post("/open", FormData("nodeId" -> nodeId.toString(), "fundingSatoshis" -> "100000", "channelType" -> "super_dope_channel").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.open) ~>
      check {
        assert(handled)
        assert(status == BadRequest)
      }

    Post("/open", FormData("nodeId" -> nodeId.toString(), "fundingSatoshis" -> "25000", "channelType" -> "standard").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(entityAs[String] == "\"created channel 56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e\"")
        eclair.open(nodeId, 25000 sat, None, Some(ChannelTypes.Standard), None, None, None, None)(any[Timeout]).wasCalled(once)
      }

    Post("/open", FormData("nodeId" -> nodeId.toString(), "fundingSatoshis" -> "25000", "channelType" -> "static_remotekey").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(entityAs[String] == "\"created channel 56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e\"")
        eclair.open(nodeId, 25000 sat, None, Some(ChannelTypes.StaticRemoteKey), None, None, None, None)(any[Timeout]).wasCalled(once)
      }

    Post("/open", FormData("nodeId" -> nodeId.toString(), "fundingSatoshis" -> "25000", "channelType" -> "anchor_outputs").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(entityAs[String] == "\"created channel 56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e\"")
        eclair.open(nodeId, 25000 sat, None, Some(ChannelTypes.AnchorOutputs), None, None, None, None)(any[Timeout]).wasCalled(once)
      }
  }

  test("'close' method should accept channelIds and shortChannelIds") {
    val shortChannelIdSerialized = "42000x27x3"
    val channelId = ByteVector32(hex"56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e")
    val channelIdSerialized = channelId.toHex
    val response = Map[ChannelIdentifier, Either[Throwable, CommandResponse[CMD_CLOSE]]](
      Left(channelId) -> Right(RES_SUCCESS(CMD_CLOSE(ActorRef.noSender, None), channelId)),
      Left(channelId.reverse) -> Left(new RuntimeException("channel not found")),
      Right(ShortChannelId(shortChannelIdSerialized)) -> Right(RES_SUCCESS(CMD_CLOSE(ActorRef.noSender, None), ByteVector32.fromValidHex(channelIdSerialized.reverse)))
    )

    val eclair = mock[Eclair]
    eclair.close(any, any)(any[Timeout]) returns Future.successful(response)
    val mockService = new MockService(eclair)

    Post("/close", FormData("shortChannelId" -> shortChannelIdSerialized).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.close) ~>
      check {
        assert(handled)
        assert(status == OK)
        val resp = entityAs[String]
        eclair.close(Right(ShortChannelId(shortChannelIdSerialized)) :: Nil, None)(any[Timeout]).wasCalled(once)
        matchTestJson("close", resp)
      }

    Post("/close", FormData("channelId" -> channelIdSerialized).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.close) ~>
      check {
        assert(handled)
        assert(status == OK)
        val resp = entityAs[String]
        eclair.close(Left(channelId) :: Nil, None)(any[Timeout]).wasCalled(once)
        matchTestJson("close", resp)
      }

    Post("/close", FormData("channelIds" -> channelIdSerialized, "shortChannelIds" -> "42000x27x3,42000x561x1").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.close) ~>
      check {
        assert(handled)
        assert(status == OK)
        val resp = entityAs[String]
        eclair.close(Left(channelId) :: Right(ShortChannelId("42000x27x3")) :: Right(ShortChannelId("42000x561x1")) :: Nil, None)(any[Timeout]).wasCalled(once)
        matchTestJson("close", resp)
      }
  }

  test("'connect' method should accept an URI and a triple with nodeId/host/port") {
    val remoteNodeId = PublicKey(hex"030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87")
    val remoteUri = NodeURI.parse("030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87@93.137.102.239:9735")

    val eclair = mock[Eclair]
    eclair.connect(any[Either[NodeURI, PublicKey]])(any[Timeout]) returns Future.successful("connected")
    val mockService = new MockService(eclair)

    Post("/connect", FormData("nodeId" -> remoteNodeId.toString()).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.connect) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(entityAs[String] == "\"connected\"")
        eclair.connect(Right(remoteNodeId))(any[Timeout]).wasCalled(once)
      }

    Post("/connect", FormData("uri" -> remoteUri.toString).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.connect) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(entityAs[String] == "\"connected\"")
        eclair.connect(Left(remoteUri))(any[Timeout]).wasCalled(once) // must account for the previous, identical, invocation
      }
  }

  test("'send' method should handle payment failures") {
    val eclair = mock[Eclair]
    eclair.send(any, any, any, any, any, any)(any[Timeout]) returns Future.failed(new IllegalArgumentException("invoice has expired"))
    val mockService = new MockService(eclair)

    val invoice = "lnbc12580n1pw2ywztpp554ganw404sh4yjkwnysgn3wjcxfcq7gtx53gxczkjr9nlpc3hzvqdq2wpskwctddyxqr4rqrzjqwryaup9lh50kkranzgcdnn2fgvx390wgj5jd07rwr3vxeje0glc7z9rtvqqwngqqqqqqqlgqqqqqeqqjqrrt8smgjvfj7sg38dwtr9kc9gg3era9k3t2hvq3cup0jvsrtrxuplevqgfhd3rzvhulgcxj97yjuj8gdx8mllwj4wzjd8gdjhpz3lpqqvk2plh"

    Post("/payinvoice", FormData("invoice" -> invoice).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == BadRequest)
        val resp = entityAs[ErrorResponse](Json4sSupport.unmarshaller, ClassTag(classOf[ErrorResponse]))
        assert(resp.error == "invoice has expired")
        eclair.send(None, 1258000 msat, any, any, any, any)(any[Timeout]).wasCalled(once)
      }
  }

  test("'send' method should allow blocking until payment completes") {
    val invoice = "lnbc12580n1pw2ywztpp554ganw404sh4yjkwnysgn3wjcxfcq7gtx53gxczkjr9nlpc3hzvqdq2wpskwctddyxqr4rqrzjqwryaup9lh50kkranzgcdnn2fgvx390wgj5jd07rwr3vxeje0glc7z9rtvqqwngqqqqqqqlgqqqqqeqqjqrrt8smgjvfj7sg38dwtr9kc9gg3era9k3t2hvq3cup0jvsrtrxuplevqgfhd3rzvhulgcxj97yjuj8gdx8mllwj4wzjd8gdjhpz3lpqqvk2plh"
    val eclair = mock[Eclair]
    val mockService = new MockService(eclair)

    eclair.sendBlocking(any, any, any, any, any, any)(any[Timeout]).returns(Future.successful(Left(PreimageReceived(ByteVector32.Zeroes, ByteVector32.One))))
    Post("/payinvoice", FormData("invoice" -> invoice, "blocking" -> "true").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.payInvoice) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        val expected = """{"paymentHash":"0000000000000000000000000000000000000000000000000000000000000000","paymentPreimage":"0100000000000000000000000000000000000000000000000000000000000000"}"""
        assert(response === expected)
      }

    val uuid = UUID.fromString("487da196-a4dc-4b1e-92b4-3e5e905e9f3f")
    val paymentSent = PaymentSent(uuid, ByteVector32.Zeroes, ByteVector32.One, 25 msat, aliceNodeId, Seq(PaymentSent.PartialPayment(uuid, 21 msat, 1 msat, ByteVector32.Zeroes, None, 1553784337711L)))
    eclair.sendBlocking(any, any, any, any, any, any)(any[Timeout]).returns(Future.successful(Right(paymentSent)))
    Post("/payinvoice", FormData("invoice" -> invoice, "blocking" -> "true").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.payInvoice) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        val expected = """{"type":"payment-sent","id":"487da196-a4dc-4b1e-92b4-3e5e905e9f3f","paymentHash":"0000000000000000000000000000000000000000000000000000000000000000","paymentPreimage":"0100000000000000000000000000000000000000000000000000000000000000","recipientAmount":25,"recipientNodeId":"03af0ed6052cf28d670665549bc86f4b721c9fdb309d40c58f5811f63966e005d0","parts":[{"id":"487da196-a4dc-4b1e-92b4-3e5e905e9f3f","amount":21,"feesPaid":1,"toChannelId":"0000000000000000000000000000000000000000000000000000000000000000","timestamp":1553784337711}]}"""
        assert(response === expected)
      }

    val paymentFailed = PaymentFailed(uuid, ByteVector32.Zeroes, failures = Seq.empty, timestamp = 1553784963659L)
    eclair.sendBlocking(any, any, any, any, any, any)(any[Timeout]).returns(Future.successful(Right(paymentFailed)))
    Post("/payinvoice", FormData("invoice" -> invoice, "blocking" -> "true").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.payInvoice) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        val expected = """{"type":"payment-failed","id":"487da196-a4dc-4b1e-92b4-3e5e905e9f3f","paymentHash":"0000000000000000000000000000000000000000000000000000000000000000","failures":[],"timestamp":1553784963659}"""
        assert(response === expected)
      }
  }

  test("'send' method should correctly forward amount parameters to EclairImpl") {
    val invoice = "lnbc12580n1pw2ywztpp554ganw404sh4yjkwnysgn3wjcxfcq7gtx53gxczkjr9nlpc3hzvqdq2wpskwctddyxqr4rqrzjqwryaup9lh50kkranzgcdnn2fgvx390wgj5jd07rwr3vxeje0glc7z9rtvqqwngqqqqqqqlgqqqqqeqqjqrrt8smgjvfj7sg38dwtr9kc9gg3era9k3t2hvq3cup0jvsrtrxuplevqgfhd3rzvhulgcxj97yjuj8gdx8mllwj4wzjd8gdjhpz3lpqqvk2plh"

    val eclair = mock[Eclair]
    eclair.send(any, any, any, any, any, any)(any[Timeout]) returns Future.successful(UUID.randomUUID())
    val mockService = new MockService(eclair)

    Post("/payinvoice", FormData("invoice" -> invoice).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.payInvoice) ~>
      check {
        assert(handled)
        assert(status == OK)
        eclair.send(None, 1258000 msat, any, any, any, any)(any[Timeout]).wasCalled(once)
      }

    Post("/payinvoice", FormData("invoice" -> invoice, "amountMsat" -> "123", "feeThresholdSat" -> "112233", "maxFeePct" -> "2.34", "externalId" -> "42").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.payInvoice) ~>
      check {
        assert(handled)
        assert(status == OK)
        eclair.send(Some("42"), 123 msat, any, any, Some(112233 sat), Some(2.34))(any[Timeout]).wasCalled(once)
      }

    Post("/payinvoice", FormData("invoice" -> invoice, "amountMsat" -> "456", "feeThresholdSat" -> "10", "maxFeePct" -> "0.5").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        eclair.send(None, 456 msat, any, any, Some(10 sat), Some(0.5))(any[Timeout]).wasCalled(once)
      }
  }

  test("'sendtonode'") {
    val eclair = mock[Eclair]
    eclair.sendWithPreimage(any, any, any, any, any, any, any)(any[Timeout]) returns Future.successful(UUID.randomUUID())
    val mockService = new MockService(eclair)
    val remoteNodeId = PublicKey(hex"030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87")

    Post("/sendtonode", FormData("amountMsat" -> "123").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.sendToNode) ~>
      check {
        assert(handled)
        assert(status == BadRequest)
      }

    Post("/sendtonode", FormData("nodeId" -> "030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.sendToNode) ~>
      check {
        assert(handled)
        assert(status == BadRequest)
      }

    Post("/sendtonode", FormData("amountMsat" -> "123", "nodeId" -> "030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.sendToNode) ~>
      check {
        assert(handled)
        assert(status == OK)
        eclair.sendWithPreimage(None, remoteNodeId, 123 msat, any, None, None, None)(any[Timeout]).wasCalled(once)
      }

    Post("/sendtonode", FormData("amountMsat" -> "123", "nodeId" -> "030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87", "feeThresholdSat" -> "10000", "maxFeePct" -> "2.5", "externalId" -> "42").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.sendToNode) ~>
      check {
        assert(handled)
        assert(status == OK)
        eclair.sendWithPreimage(Some("42"), remoteNodeId, 123 msat, any, any, Some(10000 sat), Some(2.5))(any[Timeout]).wasCalled(once)
      }
  }

  test("'getreceivedinfo'") {
    val invoice = "lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpuaztrnwngzn3kdzw5hydlzf03qdgm2hdq27cqv3agm2awhz5se903vruatfhq77w3ls4evs3ch9zw97j25emudupq63nyw24cg27h2rspfj9srp"
    val defaultPayment = IncomingPayment(PaymentRequest.read(invoice), ByteVector32.One, PaymentType.Standard, 42, IncomingPaymentStatus.Pending)
    val eclair = mock[Eclair]
    val notFound = randomBytes32()
    eclair.receivedInfo(notFound)(any) returns Future.successful(None)
    val pending = randomBytes32()
    eclair.receivedInfo(pending)(any) returns Future.successful(Some(defaultPayment))
    val expired = randomBytes32()
    eclair.receivedInfo(expired)(any) returns Future.successful(Some(defaultPayment.copy(status = IncomingPaymentStatus.Expired)))
    val received = randomBytes32()
    eclair.receivedInfo(received)(any) returns Future.successful(Some(defaultPayment.copy(status = IncomingPaymentStatus.Received(42 msat, 45))))
    val mockService = new MockService(eclair)

    Post("/getreceivedinfo", FormData("paymentHash" -> notFound.toHex).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.getReceivedInfo) ~>
      check {
        assert(handled)
        assert(status == NotFound)
        val resp = entityAs[ErrorResponse](Json4sSupport.unmarshaller, ClassTag(classOf[ErrorResponse]))
        assert(resp == ErrorResponse("Not found"))
        eclair.receivedInfo(notFound)(any[Timeout]).wasCalled(once)
      }

    Post("/getreceivedinfo", FormData("paymentHash" -> pending.toHex).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.getReceivedInfo) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        matchTestJson("received-pending", response)
        eclair.receivedInfo(pending)(any[Timeout]).wasCalled(once)
      }

    Post("/getreceivedinfo", FormData("paymentHash" -> expired.toHex).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        matchTestJson("received-expired", response)
        eclair.receivedInfo(expired)(any[Timeout]).wasCalled(once)
      }

    Post("/getreceivedinfo", FormData("paymentHash" -> received.toHex).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.getReceivedInfo) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        matchTestJson("received-success", response)
        eclair.receivedInfo(received)(any[Timeout]).wasCalled(once)
      }
  }

  test("'getsentinfo'") {
    val defaultPayment = OutgoingPayment(UUID.fromString("00000000-0000-0000-0000-000000000000"), UUID.fromString("11111111-1111-1111-1111-111111111111"), None, ByteVector32.Zeroes, PaymentType.Standard, 42 msat, 50 msat, aliceNodeId, 1, None, OutgoingPaymentStatus.Pending)
    val eclair = mock[Eclair]
    val pending = UUID.randomUUID()
    eclair.sentInfo(Left(pending))(any) returns Future.successful(Seq(defaultPayment))
    val failed = UUID.randomUUID()
    eclair.sentInfo(Left(failed))(any) returns Future.successful(Seq(defaultPayment.copy(status = OutgoingPaymentStatus.Failed(Nil, 2))))
    val sent = UUID.randomUUID()
    eclair.sentInfo(Left(sent))(any) returns Future.successful(Seq(defaultPayment.copy(status = OutgoingPaymentStatus.Succeeded(ByteVector32.One, 5 msat, Nil, 3))))
    val mockService = new MockService(eclair)

    Post("/getsentinfo", FormData("id" -> pending.toString).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.getSentInfo) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        matchTestJson("sent-pending", response)
        eclair.sentInfo(Left(pending))(any[Timeout]).wasCalled(once)
      }

    Post("/getsentinfo", FormData("id" -> failed.toString).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        matchTestJson("sent-failed", response)
        eclair.sentInfo(Left(failed))(any[Timeout]).wasCalled(once)
      }

    Post("/getsentinfo", FormData("id" -> sent.toString).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.getSentInfo) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        matchTestJson("sent-success", response)
        eclair.sentInfo(Left(sent))(any[Timeout]).wasCalled(once)
      }
  }

  test("'sendtoroute' method should accept a both a json-encoded AND comma separated list of pubkeys") {
    val payment = SendPaymentToRouteResponse(UUID.fromString("487da196-a4dc-4b1e-92b4-3e5e905e9f3f"), UUID.fromString("2ad8c6d7-99cb-4238-8f67-89024b8eed0d"), None)
    val expected = """{"paymentId":"487da196-a4dc-4b1e-92b4-3e5e905e9f3f","parentId":"2ad8c6d7-99cb-4238-8f67-89024b8eed0d"}"""
    val externalId = UUID.randomUUID().toString
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(1234 msat), ByteVector32.Zeroes, randomKey(), "Some invoice", CltvExpiryDelta(24))
    val expectedRoute = PredefinedNodeRoute(Seq(PublicKey(hex"0217eb8243c95f5a3b7d4c5682d10de354b7007eb59b6807ae407823963c7547a9"), PublicKey(hex"0242a4ae0c5bef18048fbecf995094b74bfb0f7391418d71ed394784373f41e4f3"), PublicKey(hex"026ac9fcd64fb1aa1c491fc490634dc33da41d4a17b554e0adf1b32fee88ee9f28")))
    val csvNodes = "0217eb8243c95f5a3b7d4c5682d10de354b7007eb59b6807ae407823963c7547a9, 0242a4ae0c5bef18048fbecf995094b74bfb0f7391418d71ed394784373f41e4f3, 026ac9fcd64fb1aa1c491fc490634dc33da41d4a17b554e0adf1b32fee88ee9f28"
    val jsonNodes = serialization.write(expectedRoute.nodes)

    val eclair = mock[Eclair]
    eclair.sendToRoute(any[MilliSatoshi], any[Option[MilliSatoshi]], any[Option[String]], any[Option[UUID]], any[PaymentRequest], any[CltvExpiryDelta], any[PredefinedNodeRoute], any[Option[ByteVector32]], any[Option[MilliSatoshi]], any[Option[CltvExpiryDelta]], any[List[PublicKey]])(any[Timeout]) returns Future.successful(payment)
    val mockService = new MockService(eclair)

    Post("/sendtoroute", FormData("nodeIds" -> jsonNodes, "amountMsat" -> "1234", "finalCltvExpiry" -> "190", "externalId" -> externalId, "invoice" -> PaymentRequest.write(pr)).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.sendToRoute) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(entityAs[String] == expected)
        eclair.sendToRoute(1234 msat, None, Some(externalId), None, pr, CltvExpiryDelta(190), expectedRoute, None, None, None, Nil)(any[Timeout]).wasCalled(once)
      }

    // this test uses CSV encoded route
    Post("/sendtoroute", FormData("nodeIds" -> csvNodes, "amountMsat" -> "1234", "finalCltvExpiry" -> "190", "invoice" -> PaymentRequest.write(pr)).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.sendToRoute) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(entityAs[String] == expected)
        eclair.sendToRoute(1234 msat, None, None, None, pr, CltvExpiryDelta(190), expectedRoute, None, None, None, Nil)(any[Timeout]).wasCalled(once)
      }
  }

  test("'networkstats' response should return expected statistics") {
    val capStat = Stats(30 sat, 12 sat, 14 sat, 20 sat, 40 sat, 46 sat, 48 sat)
    val cltvStat = Stats(CltvExpiryDelta(32), CltvExpiryDelta(11), CltvExpiryDelta(13), CltvExpiryDelta(22), CltvExpiryDelta(42), CltvExpiryDelta(51), CltvExpiryDelta(53))
    val feeBaseStat = Stats(32 msat, 11 msat, 13 msat, 22 msat, 42 msat, 51 msat, 53 msat)
    val feePropStat = Stats(32L, 11L, 13L, 22L, 42L, 51L, 53L)
    val networkStats = new NetworkStats(1, 2, capStat, cltvStat, feeBaseStat, feePropStat)

    val eclair = mock[Eclair]
    val mockService = new MockService(eclair)
    eclair.networkStats()(any[Timeout]) returns Future.successful(Some(networkStats))

    Post("/networkstats") ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.networkStats) ~>
      check {
        assert(handled)
        assert(status == OK)
        val resp = entityAs[String]
        eclair.networkStats()(any[Timeout]).wasCalled(once)
        matchTestJson("networkstats", resp)
      }
  }

  test("the websocket should return typed objects") {
    val mockService = new MockService(mock[Eclair])
    val fixedUUID = UUID.fromString("487da196-a4dc-4b1e-92b4-3e5e905e9f3f")
    val wsClient = WSProbe()

    WS("/ws", wsClient.flow) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      mockService.webSocket ~>
      check {
        val pf = PaymentFailed(fixedUUID, ByteVector32.Zeroes, failures = Seq.empty, timestamp = 1553784963659L)
        val expectedSerializedPf = """{"type":"payment-failed","id":"487da196-a4dc-4b1e-92b4-3e5e905e9f3f","paymentHash":"0000000000000000000000000000000000000000000000000000000000000000","failures":[],"timestamp":1553784963659}"""
        assert(serialization.write(pf) === expectedSerializedPf)
        system.eventStream.publish(pf)
        wsClient.expectMessage(expectedSerializedPf)

        val ps = PaymentSent(fixedUUID, ByteVector32.Zeroes, ByteVector32.One, 25 msat, aliceNodeId, Seq(PaymentSent.PartialPayment(fixedUUID, 21 msat, 1 msat, ByteVector32.Zeroes, None, 1553784337711L)))
        val expectedSerializedPs = """{"type":"payment-sent","id":"487da196-a4dc-4b1e-92b4-3e5e905e9f3f","paymentHash":"0000000000000000000000000000000000000000000000000000000000000000","paymentPreimage":"0100000000000000000000000000000000000000000000000000000000000000","recipientAmount":25,"recipientNodeId":"03af0ed6052cf28d670665549bc86f4b721c9fdb309d40c58f5811f63966e005d0","parts":[{"id":"487da196-a4dc-4b1e-92b4-3e5e905e9f3f","amount":21,"feesPaid":1,"toChannelId":"0000000000000000000000000000000000000000000000000000000000000000","timestamp":1553784337711}]}"""
        assert(serialization.write(ps) === expectedSerializedPs)
        system.eventStream.publish(ps)
        wsClient.expectMessage(expectedSerializedPs)

        val prel = ChannelPaymentRelayed(21 msat, 20 msat, ByteVector32.Zeroes, ByteVector32.Zeroes, ByteVector32.One, 1553784963659L)
        val expectedSerializedPrel = """{"type":"payment-relayed","amountIn":21,"amountOut":20,"paymentHash":"0000000000000000000000000000000000000000000000000000000000000000","fromChannelId":"0000000000000000000000000000000000000000000000000000000000000000","toChannelId":"0100000000000000000000000000000000000000000000000000000000000000","timestamp":1553784963659}"""
        assert(serialization.write(prel) === expectedSerializedPrel)
        system.eventStream.publish(prel)
        wsClient.expectMessage(expectedSerializedPrel)

        val ptrel = TrampolinePaymentRelayed(ByteVector32.Zeroes, Seq(PaymentRelayed.Part(21 msat, ByteVector32.Zeroes)), Seq(PaymentRelayed.Part(8 msat, ByteVector32.Zeroes), PaymentRelayed.Part(10 msat, ByteVector32.One)), bobNodeId, 17 msat, 1553784963659L)
        val expectedSerializedPtrel = """{"type":"trampoline-payment-relayed","paymentHash":"0000000000000000000000000000000000000000000000000000000000000000","incoming":[{"amount":21,"channelId":"0000000000000000000000000000000000000000000000000000000000000000"}],"outgoing":[{"amount":8,"channelId":"0000000000000000000000000000000000000000000000000000000000000000"},{"amount":10,"channelId":"0100000000000000000000000000000000000000000000000000000000000000"}],"nextTrampolineNodeId":"039dc0e0b1d25905e44fdf6f8e89755a5e219685840d0bc1d28d3308f9628a3585","nextTrampolineAmount":17,"timestamp":1553784963659}"""
        assert(serialization.write(ptrel) === expectedSerializedPtrel)
        system.eventStream.publish(ptrel)
        wsClient.expectMessage(expectedSerializedPtrel)

        val precv = PaymentReceived(ByteVector32.Zeroes, Seq(PaymentReceived.PartialPayment(21 msat, ByteVector32.Zeroes, 1553784963659L)))
        val expectedSerializedPrecv = """{"type":"payment-received","paymentHash":"0000000000000000000000000000000000000000000000000000000000000000","parts":[{"amount":21,"fromChannelId":"0000000000000000000000000000000000000000000000000000000000000000","timestamp":1553784963659}]}"""
        assert(serialization.write(precv) === expectedSerializedPrecv)
        system.eventStream.publish(precv)
        wsClient.expectMessage(expectedSerializedPrecv)

        val pset = PaymentSettlingOnChain(fixedUUID, 21 msat, ByteVector32.One, timestamp = 1553785442676L)
        val expectedSerializedPset = """{"type":"payment-settling-onchain","id":"487da196-a4dc-4b1e-92b4-3e5e905e9f3f","amount":21,"paymentHash":"0100000000000000000000000000000000000000000000000000000000000000","timestamp":1553785442676}"""
        assert(serialization.write(pset) === expectedSerializedPset)
        system.eventStream.publish(pset)
        wsClient.expectMessage(expectedSerializedPset)

        val chcr = ChannelCreated(system.deadLetters, system.deadLetters, bobNodeId, isFunder = true, ByteVector32.One, FeeratePerKw(25 sat), Some(FeeratePerKw(20 sat)))
        val expectedSerializedChcr = """{"type":"channel-opened","remoteNodeId":"039dc0e0b1d25905e44fdf6f8e89755a5e219685840d0bc1d28d3308f9628a3585","isFunder":true,"temporaryChannelId":"0100000000000000000000000000000000000000000000000000000000000000","initialFeeratePerKw":25,"fundingTxFeeratePerKw":20}"""
        assert(serialization.write(chcr) === expectedSerializedChcr)
        system.eventStream.publish(chcr)
        wsClient.expectMessage(expectedSerializedChcr)

        val chsc = ChannelStateChanged(system.deadLetters, ByteVector32.One, system.deadLetters, bobNodeId, OFFLINE, NORMAL, null)
        val expectedSerializedChsc = """{"type":"channel-state-changed","channelId":"0100000000000000000000000000000000000000000000000000000000000000","remoteNodeId":"039dc0e0b1d25905e44fdf6f8e89755a5e219685840d0bc1d28d3308f9628a3585","previousState":"OFFLINE","currentState":"NORMAL"}"""
        assert(serialization.write(chsc) === expectedSerializedChsc)
        system.eventStream.publish(chsc)
        wsClient.expectMessage(expectedSerializedChsc)

        val chcl = ChannelClosed(system.deadLetters, ByteVector32.One, Closing.NextRemoteClose(null, null), null)
        val expectedSerializedChcl = """{"type":"channel-closed","channelId":"0100000000000000000000000000000000000000000000000000000000000000","closingType":"NextRemoteClose"}"""
        assert(serialization.write(chcl) === expectedSerializedChcl)
        system.eventStream.publish(chcl)
        wsClient.expectMessage(expectedSerializedChcl)
      }
  }

  private def matchTestJson(apiName: String, response: String) = {
    val resource = getClass.getResourceAsStream(s"/api/$apiName")
    val expectedResponse = Try(Source.fromInputStream(resource).mkString).getOrElse {
      throw new IllegalArgumentException(s"Mock file for $apiName not found")
    }
    assert(response == expectedResponse, s"Test mock for $apiName did not match the expected response")
  }

}
