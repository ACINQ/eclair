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
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, ByteVector64, SatoshiLong}
import fr.acinq.eclair.ApiTypes.ChannelIdentifier
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features.{ChannelRangeQueriesExtended, DataLossProtect}
import fr.acinq.eclair._
import fr.acinq.eclair.api.directives.{EclairDirectives, ErrorResponse}
import fr.acinq.eclair.api.serde.JsonSupport
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.db._
import fr.acinq.eclair.io.Peer.PeerInfo
import fr.acinq.eclair.io.{NodeURI, Peer}
import fr.acinq.eclair.message.OnionMessages
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.relay.Relayer.ChannelBalance
import fr.acinq.eclair.payment.send.PaymentIdentifier
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentToRouteResponse
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.router.Router.{HopRelayParams, PredefinedNodeRoute}
import fr.acinq.eclair.wire.protocol._
import org.json4s.{Formats, Serialization}
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.TryValues.convertTryToSuccessOrFailure
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

  implicit val formats: Formats = JsonSupport.formats
  implicit val serialization: Serialization = JsonSupport.serialization
  implicit val routeTestTimeout: RouteTestTimeout = RouteTestTimeout(3 seconds)

  val aliceNodeId: PublicKey = PublicKey(hex"03af0ed6052cf28d670665549bc86f4b721c9fdb309d40c58f5811f63966e005d0")
  val bobNodeId: PublicKey = PublicKey(hex"039dc0e0b1d25905e44fdf6f8e89755a5e219685840d0bc1d28d3308f9628a3585")

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
        ActorRef.noSender,
        nodeId = aliceNodeId,
        state = Peer.CONNECTED,
        address = Some(NodeAddress.fromParts("127.0.0.1", 9731).get),
        channels = Set(ActorRef.noSender)),
      PeerInfo(
        ActorRef.noSender,
        nodeId = bobNodeId,
        state = Peer.DISCONNECTED,
        address = None,
        channels = Set(ActorRef.noSender))))

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

  test("'usablebalances' returns expected balance json only for enabled channels") {
    val eclair = mock[Eclair]
    eclair.usableBalances()(any[Timeout]) returns Future.successful(List(
      ChannelBalance(aliceNodeId, ShortIds(RealScidStatus.Unknown, Alias(1), None), 100000000 msat, 20000000 msat, isPublic = true, isEnabled = true),
      ChannelBalance(aliceNodeId, ShortIds(RealScidStatus.Final(RealShortChannelId(2)), Alias(3), Some(Alias(4))), 400000000 msat, 30000000 msat, isPublic = false, isEnabled = true)
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

  test("'channelbalances' returns expected balance json for all channels") {
    val eclair = mock[Eclair]
    eclair.channelBalances()(any[Timeout]) returns Future.successful(List(
      ChannelBalance(aliceNodeId, ShortIds(RealScidStatus.Temporary(RealShortChannelId(1)), Alias(2), None), 100000000 msat, 20000000 msat, isPublic = true, isEnabled = true),
      ChannelBalance(aliceNodeId, ShortIds(RealScidStatus.Final(RealShortChannelId(3)), Alias(3), Some(Alias(3))), 0 msat, 30000000 msat, isPublic = false, isEnabled = false)
    ))

    val mockService = mockApi(eclair)
    Post("/channelbalances") ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.channelBalances) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        eclair.channelBalances()(any[Timeout]).wasCalled(once)
        matchTestJson("channelbalances", response)
      }
  }

  test("'getinfo' response should include this node ID") {
    val eclair = mock[Eclair]
    val mockService = new MockService(eclair)
    eclair.getInfo()(any[Timeout]) returns Future.successful(GetInfoResponse(
      version = "1.0.0-SNAPSHOT-e3f1ec0",
      color = Color(0.toByte, 1.toByte, 2.toByte).toString,
      features = Features(DataLossProtect -> Mandatory, ChannelRangeQueriesExtended -> Optional),
      nodeId = aliceNodeId,
      alias = "alice",
      chainHash = ByteVector32(hex"06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f"),
      network = "regtest",
      blockHeight = 9999,
      publicAddresses = NodeAddress.fromParts("127.0.0.1", 9731).get :: Nil,
      onionAddress = None,
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
    eclair.open(any, any, any, any, any, any, any)(any[Timeout]) returns Future.successful(ChannelOpenResponse.ChannelOpened(channelId))
    val mockService = new MockService(eclair)

    Post("/open", FormData("nodeId" -> nodeId.toString(), "fundingSatoshis" -> "100002").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.open) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(entityAs[String] == "\"created channel 56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e\"")
        eclair.open(nodeId, 100002 sat, None, None, None, None, None)(any[Timeout]).wasCalled(once)
      }
  }

  test("'open' channels with bad channelType") {
    val nodeId = PublicKey(hex"030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87")

    val eclair = mock[Eclair]
    val mockService = new MockService(eclair)

    Post("/open", FormData("nodeId" -> nodeId.toString(), "fundingSatoshis" -> "100000", "channelType" -> "super_dope_channel").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.open) ~>
      check {
        assert(handled)
        assert(status == BadRequest)
      }
  }

  test("'open' channels with standard channelType") {
    val nodeId = PublicKey(hex"030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87")
    val channelId = ByteVector32(hex"56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e")

    val eclair = mock[Eclair]
    eclair.open(any, any, any, any, any, any, any)(any[Timeout]) returns Future.successful(ChannelOpenResponse.ChannelOpened(channelId))
    val mockService = new MockService(eclair)

    Post("/open", FormData("nodeId" -> nodeId.toString(), "fundingSatoshis" -> "25000", "channelType" -> "standard").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(entityAs[String] == "\"created channel 56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e\"")
        eclair.open(nodeId, 25000 sat, None, Some(ChannelTypes.Standard()), None, None, None)(any[Timeout]).wasCalled(once)
      }
  }

  test("'open' channels with static_remotekey channelType") {
    val nodeId = PublicKey(hex"030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87")
    val channelId = ByteVector32(hex"56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e")

    val eclair = mock[Eclair]
    eclair.open(any, any, any, any, any, any, any)(any[Timeout]) returns Future.successful(ChannelOpenResponse.ChannelOpened(channelId))
    val mockService = new MockService(eclair)

    Post("/open", FormData("nodeId" -> nodeId.toString(), "fundingSatoshis" -> "25000", "channelType" -> "static_remotekey").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(entityAs[String] == "\"created channel 56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e\"")
        eclair.open(nodeId, 25000 sat, None, Some(ChannelTypes.StaticRemoteKey()), None, None, None)(any[Timeout]).wasCalled(once)
      }
  }

  test("'open' channels with anchor_outputs channelType") {
    val nodeId = PublicKey(hex"030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87")
    val channelId = ByteVector32(hex"56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e")

    val eclair = mock[Eclair]
    eclair.open(any, any, any, any, any, any, any)(any[Timeout]) returns Future.successful(ChannelOpenResponse.ChannelOpened(channelId))
    val mockService = new MockService(eclair)

    Post("/open", FormData("nodeId" -> nodeId.toString(), "fundingSatoshis" -> "25000", "channelType" -> "anchor_outputs").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(entityAs[String] == "\"created channel 56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e\"")
        eclair.open(nodeId, 25000 sat, None, Some(ChannelTypes.AnchorOutputs()), None, None, None)(any[Timeout]).wasCalled(once)
      }

    Post("/open", FormData("nodeId" -> nodeId.toString(), "fundingSatoshis" -> "25000", "channelType" -> "anchor_outputs_zero_fee_htlc_tx").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(entityAs[String] == "\"created channel 56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e\"")
        eclair.open(nodeId, 25000 sat, None, Some(ChannelTypes.AnchorOutputsZeroFeeHtlcTx()), None, None, None)(any[Timeout]).wasCalled(once)
      }
  }

  test("'close' method should accept shortChannelIds") {
    val shortChannelIdSerialized = "42000x27x3"
    val shortChannelId = ShortChannelId.fromCoordinates(shortChannelIdSerialized).success.value
    val channelId = ByteVector32(hex"56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e")
    val channelIdSerialized = channelId.toHex
    val response = Map[ChannelIdentifier, Either[Throwable, CommandResponse[CMD_CLOSE]]](
      Left(channelId) -> Right(RES_SUCCESS(CMD_CLOSE(ActorRef.noSender, None, None), channelId)),
      Left(channelId.reverse) -> Left(new RuntimeException("channel not found")),
      Right(shortChannelId) -> Right(RES_SUCCESS(CMD_CLOSE(ActorRef.noSender, None, None), ByteVector32.fromValidHex(channelIdSerialized.reverse)))
    )

    val eclair = mock[Eclair]
    eclair.close(any, any, any)(any[Timeout]) returns Future.successful(response)
    val mockService = new MockService(eclair)

    Post("/close", FormData("shortChannelId" -> shortChannelIdSerialized).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.close) ~>
      check {
        assert(handled)
        assert(status == OK)
        val resp = entityAs[String]
        eclair.close(Right(shortChannelId) :: Nil, None, None)(any[Timeout]).wasCalled(once)
        matchTestJson("close", resp)
      }
  }

  test("'close' method should accept channelIds") {
    val shortChannelIdSerialized = "42000x27x3"
    val shortChannelId = ShortChannelId.fromCoordinates(shortChannelIdSerialized).success.value
    val channelId = ByteVector32(hex"56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e")
    val channelIdSerialized = channelId.toHex
    val response = Map[ChannelIdentifier, Either[Throwable, CommandResponse[CMD_CLOSE]]](
      Left(channelId) -> Right(RES_SUCCESS(CMD_CLOSE(ActorRef.noSender, None, None), channelId)),
      Left(channelId.reverse) -> Left(new RuntimeException("channel not found")),
      Right(shortChannelId) -> Right(RES_SUCCESS(CMD_CLOSE(ActorRef.noSender, None, None), ByteVector32.fromValidHex(channelIdSerialized.reverse)))
    )

    val eclair = mock[Eclair]
    eclair.close(any, any, any)(any[Timeout]) returns Future.successful(response)
    val mockService = new MockService(eclair)

    Post("/close", FormData("channelId" -> channelIdSerialized).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.close) ~>
      check {
        assert(handled)
        assert(status == OK)
        val resp = entityAs[String]
        eclair.close(Left(channelId) :: Nil, None, None)(any[Timeout]).wasCalled(once)
        matchTestJson("close", resp)
      }
  }

  test("'close' method should accept channelIds and shortChannelIds") {
    val shortChannelIdSerialized = "42000x27x3"
    val shortChannelId = ShortChannelId.fromCoordinates(shortChannelIdSerialized).success.value
    val channelId = ByteVector32(hex"56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e")
    val channelIdSerialized = channelId.toHex
    val response = Map[ChannelIdentifier, Either[Throwable, CommandResponse[CMD_CLOSE]]](
      Left(channelId) -> Right(RES_SUCCESS(CMD_CLOSE(ActorRef.noSender, None, None), channelId)),
      Left(channelId.reverse) -> Left(new RuntimeException("channel not found")),
      Right(shortChannelId) -> Right(RES_SUCCESS(CMD_CLOSE(ActorRef.noSender, None, None), ByteVector32.fromValidHex(channelIdSerialized.reverse)))
    )

    val eclair = mock[Eclair]
    eclair.close(any, any, any)(any[Timeout]) returns Future.successful(response)
    val mockService = new MockService(eclair)

    Post("/close", FormData("channelIds" -> channelIdSerialized, "shortChannelIds" -> "42000x27x3,42000x561x1").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.close) ~>
      check {
        assert(handled)
        assert(status == OK)
        val resp = entityAs[String]
        eclair.close(Left(channelId) :: Right(ShortChannelId.fromCoordinates("42000x27x3").success.value) :: Right(ShortChannelId.fromCoordinates("42000x561x1").success.value) :: Nil, None, None)(any[Timeout]).wasCalled(once)
        matchTestJson("close", resp)
      }
  }

  test("'close' accepts custom closing feerates 1") {
    val shortChannelIdSerialized = "1701x42x3"
    val shortChannelId = ShortChannelId.fromCoordinates(shortChannelIdSerialized).success.value
    val response = Map[ChannelIdentifier, Either[Throwable, CommandResponse[CMD_CLOSE]]](
      Right(shortChannelId) -> Right(RES_SUCCESS(CMD_CLOSE(ActorRef.noSender, None, None), randomBytes32()))
    )

    val eclair = mock[Eclair]
    eclair.close(any, any, any)(any[Timeout]) returns Future.successful(response)
    val mockService = new MockService(eclair)

    Post("/close", FormData("shortChannelId" -> shortChannelIdSerialized, "preferredFeerateSatByte" -> "10", "minFeerateSatByte" -> "2", "maxFeerateSatByte" -> "50").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.close) ~>
      check {
        assert(handled)
        assert(status == OK)
        val expectedFeerates = ClosingFeerates(FeeratePerKw(2500 sat), FeeratePerKw(500 sat), FeeratePerKw(12500 sat))
        eclair.close(Right(shortChannelId) :: Nil, None, Some(expectedFeerates))(any[Timeout]).wasCalled(once)
      }
  }

  test("'close' accepts custom closing feerates 2") {
    val shortChannelIdSerialized = "1701x42x3"
    val shortChannelId = ShortChannelId.fromCoordinates(shortChannelIdSerialized).success.value
    val response = Map[ChannelIdentifier, Either[Throwable, CommandResponse[CMD_CLOSE]]](
      Right(shortChannelId) -> Right(RES_SUCCESS(CMD_CLOSE(ActorRef.noSender, None, None), randomBytes32()))
    )

    val eclair = mock[Eclair]
    eclair.close(any, any, any)(any[Timeout]) returns Future.successful(response)
    val mockService = new MockService(eclair)

    Post("/close", FormData("shortChannelId" -> shortChannelIdSerialized, "preferredFeerateSatByte" -> "10").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.close) ~>
      check {
        assert(handled)
        assert(status == OK)
        val expectedFeerates = ClosingFeerates(FeeratePerKw(2500 sat), FeeratePerKw(1250 sat), FeeratePerKw(5000 sat))
        eclair.close(Right(shortChannelId) :: Nil, None, Some(expectedFeerates))(any[Timeout]).wasCalled(once)
      }
  }

  test("'close' accepts custom closing feerates 3") {
    val shortChannelIdSerialized = "1701x42x3"
    val shortChannelId = ShortChannelId.fromCoordinates(shortChannelIdSerialized).success.value
    val response = Map[ChannelIdentifier, Either[Throwable, CommandResponse[CMD_CLOSE]]](
      Right(shortChannelId) -> Right(RES_SUCCESS(CMD_CLOSE(ActorRef.noSender, None, None), randomBytes32()))
    )

    val eclair = mock[Eclair]
    eclair.close(any, any, any)(any[Timeout]) returns Future.successful(response)
    val mockService = new MockService(eclair)

    Post("/close", FormData("shortChannelId" -> shortChannelIdSerialized, "preferredFeerateSatByte" -> "10", "minFeerateSatByte" -> "2").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.close) ~>
      check {
        assert(handled)
        assert(status == OK)
        val expectedFeerates = ClosingFeerates(FeeratePerKw(2500 sat), FeeratePerKw(500 sat), FeeratePerKw(5000 sat))
        eclair.close(Right(shortChannelId) :: Nil, None, Some(expectedFeerates))(any[Timeout]).wasCalled(once)
      }
  }

  test("'close' accepts custom closing feerates 4") {
    val shortChannelIdSerialized = "1701x42x3"
    val shortChannelId = ShortChannelId.fromCoordinates(shortChannelIdSerialized).success.value
    val response = Map[ChannelIdentifier, Either[Throwable, CommandResponse[CMD_CLOSE]]](
      Right(shortChannelId) -> Right(RES_SUCCESS(CMD_CLOSE(ActorRef.noSender, None, None), randomBytes32()))
    )

    val eclair = mock[Eclair]
    eclair.close(any, any, any)(any[Timeout]) returns Future.successful(response)
    val mockService = new MockService(eclair)

    Post("/close", FormData("shortChannelId" -> shortChannelIdSerialized, "preferredFeerateSatByte" -> "10", "maxFeerateSatByte" -> "50").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.close) ~>
      check {
        assert(handled)
        assert(status == OK)
        val expectedFeerates = ClosingFeerates(FeeratePerKw(2500 sat), FeeratePerKw(1250 sat), FeeratePerKw(12500 sat))
        eclair.close(Right(shortChannelId) :: Nil, None, Some(expectedFeerates))(any[Timeout]).wasCalled(once)
      }
  }

  test("'close' rejects non-segwit scripts") {
    val shortChannelId = "1701x42x3"
    val eclair = mock[Eclair]
    val mockService = new MockService(eclair)

    Post("/close", FormData("shortChannelId" -> shortChannelId, "scriptPubKey" -> "a914748284390f9e263a4b766a75d0633c50426eb87587").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.close) ~>
      check {
        assert(handled)
        assert(status == BadRequest)
      }
  }

  test("'connect' method should accept a nodeId") {
    val remoteNodeId = PublicKey(hex"030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87")

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
  }

  test("'connect' method should accept an URI") {
    val remoteUri = NodeURI.parse("030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87@93.137.102.239:9735")

    val eclair = mock[Eclair]
    eclair.connect(any[Either[NodeURI, PublicKey]])(any[Timeout]) returns Future.successful("connected")
    val mockService = new MockService(eclair)

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
    eclair.send(any, any, any, any, any, any, any)(any[Timeout]) returns Future.failed(new IllegalArgumentException("invoice has expired"))
    val mockService = new MockService(eclair)

    val invoice = "lnbc12580n1pw2ywztsp5mx6av0wv087hew596jz8d7egzjdt4n8auwwj8yqxl3jemj050uxqpp554ganw404sh4yjkwnysgn3wjcxfcq7gtx53gxczkjr9nlpc3hzvqdq2wpskwctddyxqr4rqrzjqwryaup9lh50kkranzgcdnn2fgvx390wgj5jd07rwr3vxeje0glc7z9rtvqqwngqqqqqqqlgqqqqqeqqjqrrt8smgjvfj7sg38dwtr9kc9gg3era9k3t2hvq3cup0jvsrtrxuplevqgfhd3rzvhulgcxj97yjuj8gdx8mllwj4wzjd8gdjhpz3lpqq8w9xy4"

    Post("/payinvoice", FormData("invoice" -> invoice).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == BadRequest)
        val resp = entityAs[ErrorResponse](Json4sSupport.unmarshaller, ClassTag(classOf[ErrorResponse]))
        assert(resp.error == "invoice has expired")
        eclair.send(None, 1258000 msat, any, any, any, any, any)(any[Timeout]).wasCalled(once)
      }
  }

  test("'send' method should allow blocking until payment completes") {
    val invoice = "lnbc12580n1pw2ywztsp563x9gq6keftcu8kh9kh5qdj4p5wpmc3h802ekxwspcgfwpz2g25qpp554ganw404sh4yjkwnysgn3wjcxfcq7gtx53gxczkjr9nlpc3hzvqdq2wpskwctddyxqr4rqrzjqwryaup9lh50kkranzgcdnn2fgvx390wgj5jd07rwr3vxeje0glc7z9rtvqqwngqqqqqqqlgqqqqqeqqjqrrt8smgjvfj7sg38dwtr9kc9gg3era9k3t2hvq3cup0jvsrtrxuplevqgfhd3rzvhulgcxj97yjuj8gdx8mllwj4wzjd8gdjhpz3lpqqmfe0a6"
    val eclair = mock[Eclair]
    val mockService = new MockService(eclair)

    val uuid = UUID.fromString("487da196-a4dc-4b1e-92b4-3e5e905e9f3f")
    val paymentSent = PaymentSent(uuid, ByteVector32.Zeroes, ByteVector32.One, 25 msat, aliceNodeId, Seq(PaymentSent.PartialPayment(uuid, 21 msat, 1 msat, ByteVector32.Zeroes, None, TimestampMilli(1553784337711L))))
    eclair.sendBlocking(any, any, any, any, any, any, any)(any[Timeout]).returns(Future.successful(paymentSent))
    Post("/payinvoice", FormData("invoice" -> invoice, "blocking" -> "true").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.payInvoice) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        val expected = """{"type":"payment-sent","id":"487da196-a4dc-4b1e-92b4-3e5e905e9f3f","paymentHash":"0000000000000000000000000000000000000000000000000000000000000000","paymentPreimage":"0100000000000000000000000000000000000000000000000000000000000000","recipientAmount":25,"recipientNodeId":"03af0ed6052cf28d670665549bc86f4b721c9fdb309d40c58f5811f63966e005d0","parts":[{"id":"487da196-a4dc-4b1e-92b4-3e5e905e9f3f","amount":21,"feesPaid":1,"toChannelId":"0000000000000000000000000000000000000000000000000000000000000000","timestamp":{"iso":"2019-03-28T14:45:37.711Z","unix":1553784337}}]}"""
        assert(response == expected)
      }

    val paymentFailed = PaymentFailed(uuid, ByteVector32.Zeroes, failures = Seq.empty, timestamp = TimestampMilli(1553784963659L))
    eclair.sendBlocking(any, any, any, any, any, any, any)(any[Timeout]).returns(Future.successful(paymentFailed))
    Post("/payinvoice", FormData("invoice" -> invoice, "blocking" -> "true").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.payInvoice) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        val expected = """{"type":"payment-failed","id":"487da196-a4dc-4b1e-92b4-3e5e905e9f3f","paymentHash":"0000000000000000000000000000000000000000000000000000000000000000","failures":[],"timestamp":{"iso":"2019-03-28T14:56:03.659Z","unix":1553784963}}"""
        assert(response == expected)
      }
  }

  test("'send' method should correctly forward amount parameters to EclairImpl 1") {
    val invoice = "lnbc12580n1pw2ywztsp5h2xzrq2xceh0vtx083t557vlzlxt0e72t80maqkxfm6l05pqvpxqpp554ganw404sh4yjkwnysgn3wjcxfcq7gtx53gxczkjr9nlpc3hzvqdq2wpskwctddyxqr4rqrzjqwryaup9lh50kkranzgcdnn2fgvx390wgj5jd07rwr3vxeje0glc7z9rtvqqwngqqqqqqqlgqqqqqeqqjqrrt8smgjvfj7sg38dwtr9kc9gg3era9k3t2hvq3cup0jvsrtrxuplevqgfhd3rzvhulgcxj97yjuj8gdx8mllwj4wzjd8gdjhpz3lpqqyd9qst"

    val eclair = mock[Eclair]
    eclair.send(any, any, any, any, any, any, any)(any[Timeout]) returns Future.successful(UUID.randomUUID())
    val mockService = new MockService(eclair)

    Post("/payinvoice", FormData("invoice" -> invoice).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.payInvoice) ~>
      check {
        assert(handled)
        assert(status == OK)
        eclair.send(None, 1258000 msat, any, any, any, any, any)(any[Timeout]).wasCalled(once)
      }
  }

  test("'send' method should correctly forward amount parameters to EclairImpl 2") {
    val invoice = "lnbc12580n1pw2ywztsp5nyxta4fpym3q0fqx3f7l70hvlwu3murlm4y46tcyr7g8k4kz0stqpp554ganw404sh4yjkwnysgn3wjcxfcq7gtx53gxczkjr9nlpc3hzvqdq2wpskwctddyxqr4rqrzjqwryaup9lh50kkranzgcdnn2fgvx390wgj5jd07rwr3vxeje0glc7z9rtvqqwngqqqqqqqlgqqqqqeqqjqrrt8smgjvfj7sg38dwtr9kc9gg3era9k3t2hvq3cup0jvsrtrxuplevqgfhd3rzvhulgcxj97yjuj8gdx8mllwj4wzjd8gdjhpz3lpqqkj59dt"

    val eclair = mock[Eclair]
    eclair.send(any, any, any, any, any, any, any)(any[Timeout]) returns Future.successful(UUID.randomUUID())
    val mockService = new MockService(eclair)

    Post("/payinvoice", FormData("invoice" -> invoice, "amountMsat" -> "123", "maxFeeFlatSat" -> "112233", "maxFeePct" -> "2.34", "externalId" -> "42").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.payInvoice) ~>
      check {
        assert(handled)
        assert(status == OK)
        eclair.send(Some("42"), 123 msat, any, any, Some(112233 sat), Some(2.34), any)(any[Timeout]).wasCalled(once)
      }
  }

  test("'send' method should correctly forward amount parameters to EclairImpl 3") {
    val invoice = "lnbc12580n1pw2ywztsp5sf5ld85fm5w6s8d6eljdkzaddf3989lf2tdntj2hjc6wmskdye8qpp554ganw404sh4yjkwnysgn3wjcxfcq7gtx53gxczkjr9nlpc3hzvqdq2wpskwctddyxqr4rqrzjqwryaup9lh50kkranzgcdnn2fgvx390wgj5jd07rwr3vxeje0glc7z9rtvqqwngqqqqqqqlgqqqqqeqqjqrrt8smgjvfj7sg38dwtr9kc9gg3era9k3t2hvq3cup0jvsrtrxuplevqgfhd3rzvhulgcxj97yjuj8gdx8mllwj4wzjd8gdjhpz3lpqqdqm0m4"

    val eclair = mock[Eclair]
    eclair.send(any, any, any, any, any, any, any)(any[Timeout]) returns Future.successful(UUID.randomUUID())
    val mockService = new MockService(eclair)

    Post("/payinvoice", FormData("invoice" -> invoice, "amountMsat" -> "456", "maxFeeFlatSat" -> "10", "maxFeePct" -> "0.5").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        eclair.send(None, 456 msat, any, any, Some(10 sat), Some(0.5), any)(any[Timeout]).wasCalled(once)
      }
  }

  test("'send' method should correctly forward amount parameters to EclairImpl 4") {
    val invoice = "lnbc12580n1pw2ywztsp59rnute7a5aa7hpcx0tvmje805wjveknzh2c2f36tfp3xlazrmzrspp554ganw404sh4yjkwnysgn3wjcxfcq7gtx53gxczkjr9nlpc3hzvqdq2wpskwctddyxqr4rqrzjqwryaup9lh50kkranzgcdnn2fgvx390wgj5jd07rwr3vxeje0glc7z9rtvqqwngqqqqqqqlgqqqqqeqqjqrrt8smgjvfj7sg38dwtr9kc9gg3era9k3t2hvq3cup0jvsrtrxuplevqgfhd3rzvhulgcxj97yjuj8gdx8mllwj4wzjd8gdjhpz3lpqqkacgww"

    val eclair = mock[Eclair]
    eclair.send(any, any, any, any, any, any, any)(any[Timeout]) returns Future.successful(UUID.randomUUID())
    val mockService = new MockService(eclair)

    Post("/payinvoice", FormData("invoice" -> invoice, "amountMsat" -> "456", "maxFeeFlatSat" -> "10", "maxFeePct" -> "0.5", "pathFindingExperimentName" -> "my-test-experiment").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        eclair.send(None, 456 msat, any, any, Some(10 sat), Some(0.5), Some("my-test-experiment"))(any[Timeout]).wasCalled(once)
      }
  }

  test("'sendtonode' 1") {
    val eclair = mock[Eclair]
    val mockService = new MockService(eclair)

    Post("/sendtonode", FormData("amountMsat" -> "123").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.sendToNode) ~>
      check {
        assert(handled)
        assert(status == BadRequest)
      }
  }

  test("'sendtonode' 2") {
    val eclair = mock[Eclair]
    val mockService = new MockService(eclair)

    Post("/sendtonode", FormData("nodeId" -> "030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.sendToNode) ~>
      check {
        assert(handled)
        assert(status == BadRequest)
      }
  }

  test("'sendtonode' 3") {
    val eclair = mock[Eclair]
    eclair.sendWithPreimage(any, any, any, any, any, any, any, any)(any[Timeout]) returns Future.successful(UUID.randomUUID())
    val mockService = new MockService(eclair)
    val remoteNodeId = PublicKey(hex"030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87")

    Post("/sendtonode", FormData("amountMsat" -> "123", "nodeId" -> "030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.sendToNode) ~>
      check {
        assert(handled)
        assert(status == OK)
        eclair.sendWithPreimage(None, remoteNodeId, 123 msat, any, None, None, None, None)(any[Timeout]).wasCalled(once)
      }
  }

  test("'sendtonode' 4") {
    val eclair = mock[Eclair]
    eclair.sendWithPreimage(any, any, any, any, any, any, any, any)(any[Timeout]) returns Future.successful(UUID.randomUUID())
    val mockService = new MockService(eclair)
    val remoteNodeId = PublicKey(hex"030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87")

    Post("/sendtonode", FormData("amountMsat" -> "123", "nodeId" -> "030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87", "maxFeeFlatSat" -> "10000", "maxFeePct" -> "2.5", "externalId" -> "42").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.sendToNode) ~>
      check {
        assert(handled)
        assert(status == OK)
        eclair.sendWithPreimage(Some("42"), remoteNodeId, 123 msat, any, any, Some(10000 sat), Some(2.5), None)(any[Timeout]).wasCalled(once)
      }
  }

  test("'sendtonode' 5") {
    val eclair = mock[Eclair]
    eclair.sendWithPreimage(any, any, any, any, any, any, any, any)(any[Timeout]) returns Future.successful(UUID.randomUUID())
    val mockService = new MockService(eclair)
    val remoteNodeId = PublicKey(hex"030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87")

    Post("/sendtonode", FormData("amountMsat" -> "123", "nodeId" -> "030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87", "pathFindingExperimentName" -> "my-test-experiment").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.sendToNode) ~>
      check {
        assert(handled)
        assert(status == OK)
        eclair.sendWithPreimage(None, remoteNodeId, 123 msat, any, None, None, None, Some("my-test-experiment"))(any[Timeout]).wasCalled(once)
      }
  }

  test("'getreceivedinfo' 1") {
    val eclair = mock[Eclair]
    val notFound = randomBytes32()
    eclair.receivedInfo(notFound)(any) returns Future.successful(None)
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
  }

  test("'getreceivedinfo' 2") {
    val invoice = "lnbc2500u1pvjluezsp5p0yyewlxryakfx0pt6fu2j7ct2jhgaw7hd62nqwm9yvh2cp8eurspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpuaztrnwngzn3kdzw5hydlzf03qdgm2hdq27cqv3agm2awhz5se903vruatfhq77w3ls4evs3ch9zw97j25emudupq63nyw24cg27h2rsp9dda9d"
    val defaultPayment = IncomingStandardPayment(Bolt11Invoice.fromString(invoice).get, ByteVector32.One, PaymentType.Standard, 42 unixms, IncomingPaymentStatus.Pending)
    val eclair = mock[Eclair]
    val pending = randomBytes32()
    eclair.receivedInfo(pending)(any) returns Future.successful(Some(defaultPayment))
    val mockService = new MockService(eclair)

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
  }

  test("'getreceivedinfo' 3") {
    val invoice = "lnbc2500u1pvjluezsp5ggx65m9the9g2fjxhkhmguafsjv832charwnrpqtr4gwq3lae9sqpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpuaztrnwngzn3kdzw5hydlzf03qdgm2hdq27cqv3agm2awhz5se903vruatfhq77w3ls4evs3ch9zw97j25emudupq63nyw24cg27h2rspv9fxh2"
    val defaultPayment = IncomingStandardPayment(Bolt11Invoice.fromString(invoice).get, ByteVector32.One, PaymentType.Standard, 42 unixms, IncomingPaymentStatus.Pending)
    val eclair = mock[Eclair]
    val expired = randomBytes32()
    eclair.receivedInfo(expired)(any) returns Future.successful(Some(defaultPayment.copy(status = IncomingPaymentStatus.Expired)))
    val mockService = new MockService(eclair)

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
  }

  test("'getreceivedinfo' 4") {
    val invoice = "lnbc2500u1pvjluezsp5cssgls5lpvunj7zallxsn3v8g3f9wqfs75hsdmkrtxwgkafers0spp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpuaztrnwngzn3kdzw5hydlzf03qdgm2hdq27cqv3agm2awhz5se903vruatfhq77w3ls4evs3ch9zw97j25emudupq63nyw24cg27h2rspzma2k0"
    val defaultPayment = IncomingStandardPayment(Bolt11Invoice.fromString(invoice).get, ByteVector32.One, PaymentType.Standard, 42 unixms, IncomingPaymentStatus.Pending)
    val eclair = mock[Eclair]
    val received = randomBytes32()
    eclair.receivedInfo(received)(any) returns Future.successful(Some(defaultPayment.copy(status = IncomingPaymentStatus.Received(42 msat, TimestampMilli(1633439543777L)))))
    val mockService = new MockService(eclair)

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

  test("'getsentinfo' 1") {
    val defaultPayment = OutgoingPayment(UUID.fromString("00000000-0000-0000-0000-000000000000"), UUID.fromString("11111111-1111-1111-1111-111111111111"), None, ByteVector32.Zeroes, PaymentType.Standard, 42 msat, 50 msat, aliceNodeId, TimestampMilli(1633439429123L), None, None, OutgoingPaymentStatus.Pending)
    val eclair = mock[Eclair]
    val pending = UUID.randomUUID()
    eclair.sentInfo(PaymentIdentifier.PaymentUUID(pending))(any) returns Future.successful(Seq(defaultPayment))
    val mockService = new MockService(eclair)

    Post("/getsentinfo", FormData("id" -> pending.toString).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.getSentInfo) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        matchTestJson("sent-pending", response)
        eclair.sentInfo(PaymentIdentifier.PaymentUUID(pending))(any[Timeout]).wasCalled(once)
      }
  }

  test("'getsentinfo' 2") {
    val defaultPayment = OutgoingPayment(UUID.fromString("00000000-0000-0000-0000-000000000000"), UUID.fromString("11111111-1111-1111-1111-111111111111"), None, ByteVector32.Zeroes, PaymentType.Standard, 42 msat, 50 msat, aliceNodeId, TimestampMilli(1633439429123L), None, None, OutgoingPaymentStatus.Pending)
    val eclair = mock[Eclair]
    val failed = UUID.randomUUID()
    eclair.sentInfo(PaymentIdentifier.PaymentUUID(failed))(any) returns Future.successful(Seq(defaultPayment.copy(status = OutgoingPaymentStatus.Failed(Nil, TimestampMilli(1633439543777L)))))
    val mockService = new MockService(eclair)

    Post("/getsentinfo", FormData("id" -> failed.toString).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        matchTestJson("sent-failed", response)
        eclair.sentInfo(PaymentIdentifier.PaymentUUID(failed))(any[Timeout]).wasCalled(once)
      }
  }

  test("'getsentinfo' 3") {
    val defaultPayment = OutgoingPayment(UUID.fromString("00000000-0000-0000-0000-000000000000"), UUID.fromString("11111111-1111-1111-1111-111111111111"), None, ByteVector32.Zeroes, PaymentType.Standard, 42 msat, 50 msat, aliceNodeId, TimestampMilli(1633439429123L), None, None, OutgoingPaymentStatus.Pending)
    val eclair = mock[Eclair]
    val sent = UUID.randomUUID()
    eclair.sentInfo(PaymentIdentifier.PaymentUUID(sent))(any) returns Future.successful(Seq(defaultPayment.copy(status = OutgoingPaymentStatus.Succeeded(ByteVector32.One, 5 msat, Nil, TimestampMilli(1633439543777L)))))
    val mockService = new MockService(eclair)

    Post("/getsentinfo", FormData("id" -> sent.toString).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.getSentInfo) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        matchTestJson("sent-success", response)
        eclair.sentInfo(PaymentIdentifier.PaymentUUID(sent))(any[Timeout]).wasCalled(once)
      }
  }

  test("'sendtoroute' method should accept a json-encoded") {
    val payment = SendPaymentToRouteResponse(UUID.fromString("487da196-a4dc-4b1e-92b4-3e5e905e9f3f"), UUID.fromString("2ad8c6d7-99cb-4238-8f67-89024b8eed0d"), None)
    val expected = """{"paymentId":"487da196-a4dc-4b1e-92b4-3e5e905e9f3f","parentId":"2ad8c6d7-99cb-4238-8f67-89024b8eed0d"}"""
    val externalId = UUID.randomUUID().toString
    val pr = Bolt11Invoice(Block.LivenetGenesisBlock.hash, Some(1234 msat), ByteVector32.Zeroes, randomKey(), Left("Some invoice"), CltvExpiryDelta(24))
    val expectedRoute = PredefinedNodeRoute(1234 msat, Seq(PublicKey(hex"0217eb8243c95f5a3b7d4c5682d10de354b7007eb59b6807ae407823963c7547a9"), PublicKey(hex"0242a4ae0c5bef18048fbecf995094b74bfb0f7391418d71ed394784373f41e4f3"), PublicKey(hex"026ac9fcd64fb1aa1c491fc490634dc33da41d4a17b554e0adf1b32fee88ee9f28")))
    val jsonNodes = serialization.write(expectedRoute.nodes)

    val eclair = mock[Eclair]
    eclair.sendToRoute(any[Option[MilliSatoshi]], any[Option[String]], any[Option[UUID]], any[Bolt11Invoice], any[PredefinedNodeRoute], any[Option[ByteVector32]], any[Option[MilliSatoshi]], any[Option[CltvExpiryDelta]])(any[Timeout]) returns Future.successful(payment)
    val mockService = new MockService(eclair)

    Post("/sendtoroute", FormData("nodeIds" -> jsonNodes, "amountMsat" -> "1234", "finalCltvExpiry" -> "190", "externalId" -> externalId, "invoice" -> pr.toString).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.sendToRoute) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(entityAs[String] == expected)
        eclair.sendToRoute(None, Some(externalId), None, pr, expectedRoute, None, None, None)(any[Timeout]).wasCalled(once)
      }
  }

  test("'sendtoroute' method should accept a comma separated list of pubkeys") {
    val payment = SendPaymentToRouteResponse(UUID.fromString("487da196-a4dc-4b1e-92b4-3e5e905e9f3f"), UUID.fromString("2ad8c6d7-99cb-4238-8f67-89024b8eed0d"), None)
    val expected = """{"paymentId":"487da196-a4dc-4b1e-92b4-3e5e905e9f3f","parentId":"2ad8c6d7-99cb-4238-8f67-89024b8eed0d"}"""
    val pr = Bolt11Invoice(Block.LivenetGenesisBlock.hash, Some(1234 msat), ByteVector32.Zeroes, randomKey(), Left("Some invoice"), CltvExpiryDelta(24))
    val expectedRoute = PredefinedNodeRoute(1234 msat, Seq(PublicKey(hex"0217eb8243c95f5a3b7d4c5682d10de354b7007eb59b6807ae407823963c7547a9"), PublicKey(hex"0242a4ae0c5bef18048fbecf995094b74bfb0f7391418d71ed394784373f41e4f3"), PublicKey(hex"026ac9fcd64fb1aa1c491fc490634dc33da41d4a17b554e0adf1b32fee88ee9f28")))
    val csvNodes = "0217eb8243c95f5a3b7d4c5682d10de354b7007eb59b6807ae407823963c7547a9, 0242a4ae0c5bef18048fbecf995094b74bfb0f7391418d71ed394784373f41e4f3, 026ac9fcd64fb1aa1c491fc490634dc33da41d4a17b554e0adf1b32fee88ee9f28"

    val eclair = mock[Eclair]
    eclair.sendToRoute(any[Option[MilliSatoshi]], any[Option[String]], any[Option[UUID]], any[Bolt11Invoice], any[PredefinedNodeRoute], any[Option[ByteVector32]], any[Option[MilliSatoshi]], any[Option[CltvExpiryDelta]])(any[Timeout]) returns Future.successful(payment)
    val mockService = new MockService(eclair)

    // this test uses CSV encoded route
    Post("/sendtoroute", FormData("nodeIds" -> csvNodes, "amountMsat" -> "1234", "finalCltvExpiry" -> "190", "invoice" -> pr.toString).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.sendToRoute) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(entityAs[String] == expected)
        eclair.sendToRoute(None, None, None, pr, expectedRoute, None, None, None)(any[Timeout]).wasCalled(once)
      }
  }

  test("'findroute' method response should support nodeId, shortChannelId and full formats") {
    val serializedInvoice = "lnbc12580n1pw2ywztsp554k9atjswl6zwhacuvl9vx2md3kjxpgplde4ma4qr9j35s566zuqpp554ganw404sh4yjkwnysgn3wjcxfcq7gtx53gxczkjr9nlpc3hzvqdq2wpskwctddyxqr4rqrzjqwryaup9lh50kkranzgcdnn2fgvx390wgj5jd07rwr3vxeje0glc7z9rtvqqwngqqqqqqqlgqqqqqeqqjqrrt8smgjvfj7sg38dwtr9kc9gg3era9k3t2hvq3cup0jvsrtrxuplevqgfhd3rzvhulgcxj97yjuj8gdx8mllwj4wzjd8gdjhpz3lpqqk53t67"
    val invoice = Invoice.fromString(serializedInvoice).get

    val mockChannelUpdate1 = ChannelUpdate(
      signature = ByteVector64.fromValidHex("92cf3f12e161391986eb2cd7106ddab41a23c734f8f1ed120fb64f4b91f98f690ecf930388e62965f8aefbf1adafcd25a572669a125396dcfb83615208754679"),
      chainHash = ByteVector32.fromValidHex("024b7b3626554c44dcc2454ee3812458bfa68d9fced466edfab470844cb7ffe2"),
      shortChannelId = RealShortChannelId(BlockHeight(1), 2, 3),
      timestamp = 0 unixsec,
      messageFlags = ChannelUpdate.MessageFlags(dontForward = false),
      channelFlags = ChannelUpdate.ChannelFlags.DUMMY,
      cltvExpiryDelta = CltvExpiryDelta(0),
      htlcMinimumMsat = 1 msat,
      feeBaseMsat = 1 msat,
      feeProportionalMillionths = 1,
      htlcMaximumMsat = 20_000_000 msat
    )
    val mockChannelUpdate2 = mockChannelUpdate1.copy(shortChannelId = RealShortChannelId(BlockHeight(1), 2, 4))
    val mockChannelUpdate3 = mockChannelUpdate1.copy(shortChannelId = RealShortChannelId(BlockHeight(1), 2, 5))

    val mockHop1 = Router.ChannelHop(mockChannelUpdate1.shortChannelId, PublicKey.fromBin(ByteVector.fromValidHex("03007e67dc5a8fd2b2ef21cb310ab6359ddb51f3f86a8b79b8b1e23bc3a6ea150a")), PublicKey.fromBin(ByteVector.fromValidHex("026105f6cb4862810be989385d16f04b0f748f6f2a14040338b1a534d45b4be1c1")), HopRelayParams.FromAnnouncement(mockChannelUpdate1))
    val mockHop2 = Router.ChannelHop(mockChannelUpdate2.shortChannelId, mockHop1.nextNodeId, PublicKey.fromBin(ByteVector.fromValidHex("038cfa2b5857843ee90cff91b06f692c0d8fe201921ee6387aee901d64f43699f0")), HopRelayParams.FromAnnouncement(mockChannelUpdate2))
    val mockHop3 = Router.ChannelHop(mockChannelUpdate3.shortChannelId, mockHop2.nextNodeId, PublicKey.fromBin(ByteVector.fromValidHex("02be60276e294c6921240daae33a361d214d02578656df0e74c61a09c3196e51df")), HopRelayParams.FromAnnouncement(mockChannelUpdate3))
    val mockHops = Seq(mockHop1, mockHop2, mockHop3)

    val eclair = mock[Eclair]
    val mockService = new MockService(eclair)
    eclair.findRoute(any, any, any, any, any, any, any, any)(any[Timeout]) returns Future.successful(Router.RouteResponse(Seq(Router.Route(456.msat, mockHops, None))))

    // invalid format
    Post("/findroute", FormData("format" -> "invalid-output-format", "invoice" -> serializedInvoice, "amountMsat" -> "456")) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.findRoute) ~>
      check {
        assert(handled)
        assert(status == BadRequest)
        eclair.findRoute(invoice.nodeId, 456.msat, any, any, any, any, any, any)(any[Timeout]).wasNever(called)
      }

    // default format
    Post("/findroute", FormData("invoice" -> serializedInvoice, "amountMsat" -> "456")) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.findRoute) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        matchTestJson("findroute-nodeid", response)
        eclair.findRoute(invoice.nodeId, 456.msat, any, any, any, any, any, any)(any[Timeout]).wasCalled(once)
      }

    Post("/findroute", FormData("format" -> "nodeId", "invoice" -> serializedInvoice, "amountMsat" -> "456")) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.findRoute) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        matchTestJson("findroute-nodeid", response)
        eclair.findRoute(invoice.nodeId, 456.msat, any, any, any, any, any, any)(any[Timeout]).wasCalled(twice)
      }

    Post("/findroute", FormData("format" -> "shortChannelId", "invoice" -> serializedInvoice, "amountMsat" -> "456")) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.findRoute) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        matchTestJson("findroute-scid", response)
        eclair.findRoute(invoice.nodeId, 456.msat, any, any, any, any, any, any)(any[Timeout]).wasCalled(threeTimes)
      }

    Post("/findroute", FormData("format" -> "full", "invoice" -> serializedInvoice, "amountMsat" -> "456")) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.findRoute) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        matchTestJson("findroute-full", response)
        eclair.findRoute(invoice.nodeId, 456.msat, any, any, any, any, any, any)(any[Timeout]).wasCalled(fourTimes)
      }
  }

  test("'audit'") {
    val eclair = mock[Eclair]
    val mockService = new MockService(eclair)
    val auditResponse = AuditResponse(Seq.empty, Seq.empty, Seq.empty)
    eclair.audit(any, any, any)(any[Timeout]) returns Future.successful(auditResponse)

    Post("/audit") ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.audit) ~>
      check {
        assert(handled)
        assert(status == OK)
        // The default is to get data for the last day.
        val from = TimestampSecond.now() - 1.day
        val to = TimestampSecond.now()
        eclair.audit(argThat[TimestampSecond](t => from - 1.minute <= t && t <= from + 1.minute), argThat[TimestampSecond](t => to - 1.minute <= t && t <= to + 1.minute), None)(any[Timeout]).wasCalled(once)
      }

    Post("/audit", FormData("from" -> TimestampSecond.min.toLong.toString, "to" -> TimestampSecond.max.toLong.toString)) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.audit) ~>
      check {
        assert(handled)
        assert(status == OK)
        eclair.audit(TimestampSecond.min, TimestampSecond.max, None)(any[Timeout]).wasCalled(once)
      }

    Post("/audit", FormData("from" -> 123456.toString, "to" -> 654321.toString)) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.audit) ~>
      check {
        assert(handled)
        assert(status == OK)
        eclair.audit(123456 unixsec, 654321 unixsec, None)(any[Timeout]).wasCalled(once)
      }

    Post("/audit", FormData("from" -> 123456.toString, "to" -> 654321.toString, "count" -> 1.toString, "skip" -> 2.toString)) ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.audit) ~>
      check {
        assert(handled)
        assert(status == OK)
        eclair.audit(123456 unixsec, 654321 unixsec, Some(Paginated(count = 1, skip = 2)))(any[Timeout]).wasCalled(once)
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
        val pf = PaymentFailed(fixedUUID, ByteVector32.Zeroes, failures = Seq.empty, timestamp = TimestampMilli(1553784963659L))
        val expectedSerializedPf = """{"type":"payment-failed","id":"487da196-a4dc-4b1e-92b4-3e5e905e9f3f","paymentHash":"0000000000000000000000000000000000000000000000000000000000000000","failures":[],"timestamp":{"iso":"2019-03-28T14:56:03.659Z","unix":1553784963}}"""
        assert(serialization.write(pf) == expectedSerializedPf)
        system.eventStream.publish(pf)
        wsClient.expectMessage(expectedSerializedPf)

        val ps = PaymentSent(fixedUUID, ByteVector32.Zeroes, ByteVector32.One, 25 msat, aliceNodeId, Seq(PaymentSent.PartialPayment(fixedUUID, 21 msat, 1 msat, ByteVector32.Zeroes, None, TimestampMilli(1553784337711L))))
        val expectedSerializedPs = """{"type":"payment-sent","id":"487da196-a4dc-4b1e-92b4-3e5e905e9f3f","paymentHash":"0000000000000000000000000000000000000000000000000000000000000000","paymentPreimage":"0100000000000000000000000000000000000000000000000000000000000000","recipientAmount":25,"recipientNodeId":"03af0ed6052cf28d670665549bc86f4b721c9fdb309d40c58f5811f63966e005d0","parts":[{"id":"487da196-a4dc-4b1e-92b4-3e5e905e9f3f","amount":21,"feesPaid":1,"toChannelId":"0000000000000000000000000000000000000000000000000000000000000000","timestamp":{"iso":"2019-03-28T14:45:37.711Z","unix":1553784337}}]}"""
        assert(serialization.write(ps) == expectedSerializedPs)
        system.eventStream.publish(ps)
        wsClient.expectMessage(expectedSerializedPs)

        val prel = ChannelPaymentRelayed(21 msat, 20 msat, ByteVector32.Zeroes, ByteVector32.Zeroes, ByteVector32.One, TimestampMilli(1553784963659L))
        val expectedSerializedPrel = """{"type":"payment-relayed","amountIn":21,"amountOut":20,"paymentHash":"0000000000000000000000000000000000000000000000000000000000000000","fromChannelId":"0000000000000000000000000000000000000000000000000000000000000000","toChannelId":"0100000000000000000000000000000000000000000000000000000000000000","timestamp":{"iso":"2019-03-28T14:56:03.659Z","unix":1553784963}}"""
        assert(serialization.write(prel) == expectedSerializedPrel)
        system.eventStream.publish(prel)
        wsClient.expectMessage(expectedSerializedPrel)

        val ptrel = TrampolinePaymentRelayed(ByteVector32.Zeroes, Seq(PaymentRelayed.Part(21 msat, ByteVector32.Zeroes)), Seq(PaymentRelayed.Part(8 msat, ByteVector32.Zeroes), PaymentRelayed.Part(10 msat, ByteVector32.One)), bobNodeId, 17 msat, TimestampMilli(1553784963659L))
        val expectedSerializedPtrel = """{"type":"trampoline-payment-relayed","paymentHash":"0000000000000000000000000000000000000000000000000000000000000000","incoming":[{"amount":21,"channelId":"0000000000000000000000000000000000000000000000000000000000000000"}],"outgoing":[{"amount":8,"channelId":"0000000000000000000000000000000000000000000000000000000000000000"},{"amount":10,"channelId":"0100000000000000000000000000000000000000000000000000000000000000"}],"nextTrampolineNodeId":"039dc0e0b1d25905e44fdf6f8e89755a5e219685840d0bc1d28d3308f9628a3585","nextTrampolineAmount":17,"timestamp":{"iso":"2019-03-28T14:56:03.659Z","unix":1553784963}}"""
        assert(serialization.write(ptrel) == expectedSerializedPtrel)
        system.eventStream.publish(ptrel)
        wsClient.expectMessage(expectedSerializedPtrel)

        val precv = PaymentReceived(ByteVector32.Zeroes, Seq(PaymentReceived.PartialPayment(21 msat, ByteVector32.Zeroes, TimestampMilli(1553784963659L))))
        val expectedSerializedPrecv = """{"type":"payment-received","paymentHash":"0000000000000000000000000000000000000000000000000000000000000000","parts":[{"amount":21,"fromChannelId":"0000000000000000000000000000000000000000000000000000000000000000","timestamp":{"iso":"2019-03-28T14:56:03.659Z","unix":1553784963}}]}"""
        assert(serialization.write(precv) == expectedSerializedPrecv)
        system.eventStream.publish(precv)
        wsClient.expectMessage(expectedSerializedPrecv)

        val pset = PaymentSettlingOnChain(fixedUUID, 21 msat, ByteVector32.One, timestamp = TimestampMilli(1553785442676L))
        val expectedSerializedPset = """{"type":"payment-settling-onchain","id":"487da196-a4dc-4b1e-92b4-3e5e905e9f3f","amount":21,"paymentHash":"0100000000000000000000000000000000000000000000000000000000000000","timestamp":{"iso":"2019-03-28T15:04:02.676Z","unix":1553785442}}"""
        assert(serialization.write(pset) == expectedSerializedPset)
        system.eventStream.publish(pset)
        wsClient.expectMessage(expectedSerializedPset)

        val chcr = ChannelCreated(system.deadLetters, system.deadLetters, bobNodeId, isInitiator = true, ByteVector32.One, FeeratePerKw(25 sat), Some(FeeratePerKw(20 sat)))
        val expectedSerializedChcr = """{"type":"channel-created","remoteNodeId":"039dc0e0b1d25905e44fdf6f8e89755a5e219685840d0bc1d28d3308f9628a3585","isInitiator":true,"temporaryChannelId":"0100000000000000000000000000000000000000000000000000000000000000","commitTxFeeratePerKw":25,"fundingTxFeeratePerKw":20}"""
        assert(serialization.write(chcr) == expectedSerializedChcr)
        system.eventStream.publish(chcr)
        wsClient.expectMessage(expectedSerializedChcr)

        val chop = ChannelOpened(system.deadLetters, bobNodeId, ByteVector32.One)
        val expectedSerializedChop = """{"type":"channel-opened","remoteNodeId":"039dc0e0b1d25905e44fdf6f8e89755a5e219685840d0bc1d28d3308f9628a3585","channelId":"0100000000000000000000000000000000000000000000000000000000000000"}"""
        assert(serialization.write(chop) == expectedSerializedChop)
        system.eventStream.publish(chop)
        wsClient.expectMessage(expectedSerializedChop)

        val chsc = ChannelStateChanged(system.deadLetters, ByteVector32.One, system.deadLetters, bobNodeId, OFFLINE, NORMAL, null)
        val expectedSerializedChsc = """{"type":"channel-state-changed","channelId":"0100000000000000000000000000000000000000000000000000000000000000","remoteNodeId":"039dc0e0b1d25905e44fdf6f8e89755a5e219685840d0bc1d28d3308f9628a3585","previousState":"OFFLINE","currentState":"NORMAL"}"""
        assert(serialization.write(chsc) == expectedSerializedChsc)
        system.eventStream.publish(chsc)
        wsClient.expectMessage(expectedSerializedChsc)

        val chcl = ChannelClosed(system.deadLetters, ByteVector32.One, Closing.NextRemoteClose(null, null), null)
        val expectedSerializedChcl = """{"type":"channel-closed","channelId":"0100000000000000000000000000000000000000000000000000000000000000","closingType":"NextRemoteClose"}"""
        assert(serialization.write(chcl) == expectedSerializedChcl)
        system.eventStream.publish(chcl)
        wsClient.expectMessage(expectedSerializedChcl)

        val msgrcv = OnionMessages.ReceiveMessage(MessageOnion.FinalPayload(TlvStream[OnionMessagePayloadTlv](
          Set[OnionMessagePayloadTlv](
            OnionMessagePayloadTlv.EncryptedData(ByteVector.empty),
            OnionMessagePayloadTlv.ReplyPath(Sphinx.RouteBlinding.create(PrivateKey(hex"414141414141414141414141414141414141414141414141414141414141414101"), Seq(bobNodeId), Seq(hex"000000")).route)
          ), Set(
            GenericTlv(UInt64(5), hex"1111")
          )), TlvStream(RouteBlindingEncryptedDataTlv.PathId(hex"2222"))))
        val expectedSerializedMsgrcv = """{"type":"onion-message-received","pathId":"2222","encodedReplyPath":"039dc0e0b1d25905e44fdf6f8e89755a5e219685840d0bc1d28d3308f9628a358502eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f28368661901020303f91e620504cde242df38d04599d8b4d4c555149cc742a5f12de452cbdd400013126a26221759247584d704b382a5789f1d8c5a","replyPath":{"introductionNodeId":"039dc0e0b1d25905e44fdf6f8e89755a5e219685840d0bc1d28d3308f9628a3585","blindingKey":"02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619","blindedNodes":[{"blindedPublicKey":"020303f91e620504cde242df38d04599d8b4d4c555149cc742a5f12de452cbdd40","encryptedPayload":"126a26221759247584d704b382a5789f1d8c5a"}]},"unknownTlvs":{"5":"1111"}}"""
        assert(serialization.write(msgrcv) == expectedSerializedMsgrcv)
        system.eventStream.publish(msgrcv)
        wsClient.expectMessage(expectedSerializedMsgrcv)
      }
  }

  test("stop eclair") {
    val eclair = mock[Eclair]
    val mockService = new MockService(eclair)
    eclair.stop() returns Future.successful(())

    Post("/stop") ~>
      addCredentials(BasicHttpCredentials("", mockApi().password)) ~>
      Route.seal(mockService.stop) ~>
      check {
        assert(handled)
        assert(status == OK)
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
