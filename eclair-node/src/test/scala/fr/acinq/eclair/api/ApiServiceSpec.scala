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

import java.util.UUID

import akka.util.Timeout
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{Block, ByteVector32}
import fr.acinq.eclair.ApiTypes.ChannelIdentifier
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features.{ChannelRangeQueriesExtended, OptionDataLossProtect}
import fr.acinq.eclair.channel.ChannelCommandResponse
import fr.acinq.eclair.channel.ChannelCommandResponse.ChannelClosed
import fr.acinq.eclair.io.NodeURI
import fr.acinq.eclair.io.Peer.PeerInfo
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.relay.Relayer.UsableBalance
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentToRouteResponse
import fr.acinq.eclair.wire.{Color, NodeAddress}
import fr.acinq.eclair.{CltvExpiryDelta, Eclair, MilliSatoshi, _}
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.matchers.should.Matchers
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits._
import spray.http.StatusCodes._
import spray.http.{BasicHttpCredentials, FormData, HttpResponse}
import spray.httpx.unmarshalling.Deserializer
import spray.routing.HttpService
import spray.testkit.{RouteTest, ScalatestRouteTest}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.io.Source
import scala.util.Try

class ApiServiceSpec extends AnyFunSuiteLike with ScalatestRouteTest with RouteTest with IdiomaticMockito with Matchers {

  implicit val formats = JsonSupport.json4sJacksonFormats
  implicit val serialization = JsonSupport.serialization
  implicit val unmarshaller = JsonSupport.json4sUnmarshaller[ErrorResponse]
  implicit val routeTestTimeout = RouteTestTimeout(3 seconds)

  val mockPassword = "mockPassword"

  val aliceNodeId = PublicKey(hex"03af0ed6052cf28d670665549bc86f4b721c9fdb309d40c58f5811f63966e005d0")
  val bobNodeId = PublicKey(hex"039dc0e0b1d25905e44fdf6f8e89755a5e219685840d0bc1d28d3308f9628a3585")

  implicit val errorResponseDeserializer = Deserializer.fromFunction2Converter[HttpResponse, ErrorResponse] {
    case HttpResponse(_, entity, _, _) => serialization.read[ErrorResponse](entity.asString)
    case _ => ???
  }

  class MockService(eclair: Eclair) extends Service {
    override val eclairApi: Eclair = eclair
    override val password: String = mockPassword
  }

  test("API service should handle failures correctly") {
    val service = new MockService(mock[Eclair])

    // no auth
    Post("/getinfo") ~>
      HttpService.sealRoute(service.route) ~>
      check {
        assert(handled)
        assert(status == Unauthorized)
      }

    // wrong auth
    Post("/getinfo") ~>
      addCredentials(BasicHttpCredentials("", mockPassword + "what!")) ~>
      HttpService.sealRoute(service.route) ~>
      check {
        assert(handled)
        assert(status == Unauthorized)
      }

    // correct auth but wrong URL
    Post("/mistake") ~>
      addCredentials(BasicHttpCredentials("", mockPassword)) ~>
      HttpService.sealRoute(service.route) ~>
      check {
        assert(handled)
        assert(status == NotFound)
      }

    // wrong param type
    Post("/channel", FormData(Map("channelId" -> "hey"))) ~>
      addCredentials(BasicHttpCredentials("", mockPassword)) ~>
      HttpService.sealRoute(service.route) ~>
      check {
        assert(handled)
        assert(status == BadRequest)
        val resp = responseAs[String]
        assert(resp == "The form field 'channelId' was malformed:\njava.lang.IllegalArgumentException: Invalid hexadecimal character 'h' at index 0")
      }

    // wrong params
    Post("/connect", FormData(Map("urb" -> "030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87@93.137.102.239:9735"))) ~>
      addCredentials(BasicHttpCredentials("", mockPassword)) ~>
      HttpService.sealRoute(service.route) ~>
      check {
        assert(handled)
        assert(status == InternalServerError)
      }
  }

  test("'peers' should ask the switchboard for current known peers") {
    val mockEclair = mock[Eclair]
    val service = new MockService(mockEclair)
    mockEclair.peersInfo()(any[Timeout]) returns Future.successful(List(
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

    Post("/peers") ~>
      addCredentials(BasicHttpCredentials("", mockPassword)) ~>
      HttpService.sealRoute(service.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = responseAs[String]
        mockEclair.peersInfo()(any[Timeout]).wasCalled(once)
        matchTestJson("peers", response)
      }
  }

  test("'usablebalances' asks router for current usable balances") {
    val mockEclair = mock[Eclair]
    val service = new MockService(mockEclair)

    mockEclair.usableBalances()(any[Timeout]) returns Future.successful(List(
      UsableBalance(canSend = 100000000 msat, canReceive = 20000000 msat, shortChannelId = ShortChannelId(1), remoteNodeId = aliceNodeId, isPublic = true),
      UsableBalance(canSend = 400000000 msat, canReceive = 30000000 msat, shortChannelId = ShortChannelId(2), remoteNodeId = aliceNodeId, isPublic = false)
    ))

    Post("/usablebalances") ~>
      addCredentials(BasicHttpCredentials("", mockPassword)) ~>
      HttpService.sealRoute(service.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = responseAs[String]
        mockEclair.usableBalances()(any[Timeout]).wasCalled(once)
        matchTestJson("usablebalances", response)
      }
  }

  test("'getinfo' response should include this node ID") {
    val mockEclair = mock[Eclair]
    val service = new MockService(mockEclair)

    mockEclair.getInfo()(any[Timeout]) returns Future.successful(GetInfoResponse(
      version = "1.0.0-SNAPSHOT-e3f1ec0",
      color = Color(0.toByte, 1.toByte, 2.toByte).toString,
      features = Features(Set(ActivatedFeature(OptionDataLossProtect, Mandatory), ActivatedFeature(ChannelRangeQueriesExtended, Optional))),
      nodeId = aliceNodeId,
      alias = "alice",
      chainHash = ByteVector32(hex"06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f"),
      network = "regtest",
      blockHeight = 9999,
      publicAddresses = NodeAddress.fromParts("localhost", 9731).get :: Nil
    ))

    Post("/getinfo") ~>
      addCredentials(BasicHttpCredentials("", mockPassword)) ~>
      HttpService.sealRoute(service.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val resp = responseAs[String]
        assert(resp.contains(aliceNodeId.toString))
        mockEclair.getInfo()(any[Timeout]).wasCalled(once)
        matchTestJson("getinfo", resp)
      }
  }

  test("'close' method should accept channelIds and shortChannelIds") {
    val shortChannelIdSerialized = "42000x27x3"
    val channelId = "56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e"
    val mockEclair = mock[Eclair]
    val service = new MockService(mockEclair)
    val identifier = ByteVector32.fromValidHex(channelId)
    val response = Map[ChannelIdentifier, Either[Throwable, ChannelCommandResponse]](
      Left(ByteVector32.fromValidHex(channelId)) -> Right(ChannelCommandResponse.ChannelClosed(ByteVector32.fromValidHex(channelId))),
      Left(ByteVector32.fromValidHex(channelId).reverse) -> Left(new RuntimeException("channel not found")),
      Right(ShortChannelId(shortChannelIdSerialized)) -> Right(ChannelCommandResponse.ChannelClosed(ByteVector32.fromValidHex(channelId.reverse)))
    )
    mockEclair.close(any, any)(any[Timeout]) returns Future.successful(response)

    Post("/close", FormData(Map("shortChannelId" -> shortChannelIdSerialized))) ~>
      addCredentials(BasicHttpCredentials("", mockPassword)) ~>
      addHeader("Content-Type", "application/json") ~>
      HttpService.sealRoute(service.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val resp = responseAs[String]
        assert(resp.contains(channelId.toString))
        mockEclair.close(Right(ShortChannelId(shortChannelIdSerialized)) :: Nil, None)(any[Timeout]).wasCalled(once)
        matchTestJson("close", resp)
      }

    Post("/close", FormData(Map("channelId" -> channelId))) ~>
      addCredentials(BasicHttpCredentials("", mockPassword)) ~>
      addHeader("Content-Type", "application/json") ~>
      HttpService.sealRoute(service.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val resp = responseAs[String]
        assert(resp.contains(channelId.toString))
        mockEclair.close(Left(ByteVector32.fromValidHex(channelId)) :: Nil, None)(any[Timeout]).wasCalled(once)
        matchTestJson("close", resp)
      }
  }

  test("'connect' method should accept an URI and a triple with nodeId/host/port") {
    val remoteNodeId = PublicKey(hex"030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87")
    val remoteUri = NodeURI.parse("030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87@93.137.102.239:9735")
    val mockEclair = mock[Eclair]
    mockEclair.connect(any[Either[NodeURI, PublicKey]])(any[Timeout]) returns Future.successful("connected")
    val service = new MockService(mockEclair)

    //    Post("/connect", FormData(Map("nodeId" -> remoteNodeId.value.toHex))) ~>
    //      addCredentials(BasicHttpCredentials("", mockPassword)) ~>
    //      HttpService.sealRoute(service.route) ~>
    //      check {
    //        assert(handled)
    //        assert(responseAs[String] == "\"connected\"")
    //        assert(status == OK)
    //        mockEclair.connect(Right(remoteNodeId))(any[Timeout]).wasCalled(once)
    //      }

    Post("/connect", FormData(Map("uri" -> remoteUri.toString))) ~>
      addCredentials(BasicHttpCredentials("", mockPassword)) ~>
      HttpService.sealRoute(service.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(responseAs[String] == "\"connected\"")
        mockEclair.connect(Left(remoteUri))(any[Timeout]).wasCalled(once) // must account for the previous, identical, invocation
      }
  }

  test("'send' method should handle payment failures") {
    val mockEclair = mock[Eclair]
    val service = new MockService(mockEclair)
    mockEclair.send(any, any, any, any, any, any, any, any)(any[Timeout]) returns Future.failed(new IllegalArgumentException("invoice has expired"))

    val invoice = "lnbc12580n1pw2ywztpp554ganw404sh4yjkwnysgn3wjcxfcq7gtx53gxczkjr9nlpc3hzvqdq2wpskwctddyxqr4rqrzjqwryaup9lh50kkranzgcdnn2fgvx390wgj5jd07rwr3vxeje0glc7z9rtvqqwngqqqqqqqlgqqqqqeqqjqrrt8smgjvfj7sg38dwtr9kc9gg3era9k3t2hvq3cup0jvsrtrxuplevqgfhd3rzvhulgcxj97yjuj8gdx8mllwj4wzjd8gdjhpz3lpqqvk2plh"

    Post("/payinvoice", FormData(Map("invoice" -> invoice))) ~>
      addCredentials(BasicHttpCredentials("", mockPassword)) ~>
      HttpService.sealRoute(service.route) ~>
      check {
        assert(handled)
        assert(status == BadRequest)
        val resp = responseAs[String]
        assert(resp == "{\"error\":\"invoice has expired\"}")
        mockEclair.send(None, any, 1258000 msat, any, any, any, any, any)(any[Timeout]).wasCalled(once)
      }
  }

  test("'send' method should correctly forward amount parameters to EclairImpl") {
    val invoice = "lnbc12580n1pw2ywztpp554ganw404sh4yjkwnysgn3wjcxfcq7gtx53gxczkjr9nlpc3hzvqdq2wpskwctddyxqr4rqrzjqwryaup9lh50kkranzgcdnn2fgvx390wgj5jd07rwr3vxeje0glc7z9rtvqqwngqqqqqqqlgqqqqqeqqjqrrt8smgjvfj7sg38dwtr9kc9gg3era9k3t2hvq3cup0jvsrtrxuplevqgfhd3rzvhulgcxj97yjuj8gdx8mllwj4wzjd8gdjhpz3lpqqvk2plh"
    val mockEclair = mock[Eclair]
    val service = new MockService(mockEclair)

    mockEclair.send(any, any, any, any, any, any, any, any)(any[Timeout]) returns Future.successful(UUID.randomUUID())

    Post("/payinvoice", FormData(Map("invoice" -> invoice))) ~>
      addCredentials(BasicHttpCredentials("", mockPassword)) ~>
      HttpService.sealRoute(service.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        mockEclair.send(None, any, 1258000 msat, any, any, any, any, any)(any[Timeout]).wasCalled(once)
      }

    Post("/payinvoice", FormData(Map("invoice" -> invoice, "amountMsat" -> "123", "feeThresholdSat" -> "112233", "maxFeePct" -> "2.34", "externalId" -> "42"))) ~>
      addCredentials(BasicHttpCredentials("", mockPassword)) ~>
      HttpService.sealRoute(service.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        mockEclair.send(Some("42"), any, 123 msat, any, any, any, Some(112233 sat), Some(2.34))(any[Timeout]).wasCalled(once)
      }
  }

  test("'getreceivedinfo' method should respond HTTP 404 with a JSON encoded response if the element is not found") {
    val mockEclair = mock[Eclair]
    val service = new MockService(mockEclair)

    mockEclair.receivedInfo(any[ByteVector32])(any) returns Future.successful(None)

    Post("/getreceivedinfo", FormData(Map("paymentHash" -> ByteVector32.Zeroes.toHex))) ~>
      addCredentials(BasicHttpCredentials("", mockPassword)) ~>
      HttpService.sealRoute(service.route) ~>
      check {
        assert(handled)
        assert(status == NotFound)
        val resp = responseAs[ErrorResponse] //(JsonSupport.unmarshaller, ClassTag(classOf[ErrorResponse]))
        assert(resp == ErrorResponse("Not found"))
        mockEclair.receivedInfo(ByteVector32.Zeroes)(any[Timeout]).wasCalled(once)
      }
  }

  test("'sendtoroute' method should accept a both a json-encoded AND comma separated list of pubkeys") {
    val payment = SendPaymentToRouteResponse(UUID.fromString("487da196-a4dc-4b1e-92b4-3e5e905e9f3f"), UUID.fromString("2ad8c6d7-99cb-4238-8f67-89024b8eed0d"), None)
    val expected = """{"paymentId":"487da196-a4dc-4b1e-92b4-3e5e905e9f3f","parentId":"2ad8c6d7-99cb-4238-8f67-89024b8eed0d"}"""
    val externalId = UUID.randomUUID().toString
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(1234 msat), ByteVector32.Zeroes, randomKey, "Some invoice")
    val expectedRoute = List(PublicKey(hex"0217eb8243c95f5a3b7d4c5682d10de354b7007eb59b6807ae407823963c7547a9"), PublicKey(hex"0242a4ae0c5bef18048fbecf995094b74bfb0f7391418d71ed394784373f41e4f3"), PublicKey(hex"026ac9fcd64fb1aa1c491fc490634dc33da41d4a17b554e0adf1b32fee88ee9f28"))
    val csvNodes = "0217eb8243c95f5a3b7d4c5682d10de354b7007eb59b6807ae407823963c7547a9, 0242a4ae0c5bef18048fbecf995094b74bfb0f7391418d71ed394784373f41e4f3, 026ac9fcd64fb1aa1c491fc490634dc33da41d4a17b554e0adf1b32fee88ee9f28"
    val jsonNodes = serialization.write(expectedRoute)
    val mockEclair = mock[Eclair]
    val service = new MockService(mockEclair)

    mockEclair.sendToRoute(any[MilliSatoshi], any[Option[MilliSatoshi]], any[Option[String]], any[Option[UUID]], any[PaymentRequest], any[CltvExpiryDelta], any[List[PublicKey]], any[Option[ByteVector32]], any[Option[MilliSatoshi]], any[Option[CltvExpiryDelta]], any[List[PublicKey]])(any[Timeout]) returns Future.successful(payment)

    Post("/sendtoroute", FormData(Map("route" -> jsonNodes, "amountMsat" -> "1234", "finalCltvExpiry" -> "190", "externalId" -> externalId.toString, "invoice" -> PaymentRequest.write(pr)))) ~>
      addCredentials(BasicHttpCredentials("", mockPassword)) ~>
      addHeader("Content-Type", "application/json") ~>
      HttpService.sealRoute(service.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(responseAs[String] == expected)
        mockEclair.sendToRoute(1234 msat, None, Some(externalId), None, pr, CltvExpiryDelta(190), expectedRoute, None, None, None, Nil)(any[Timeout]).wasCalled(once)
      }

    // this test uses CSV encoded route
    Post("/sendtoroute", FormData(Map("route" -> csvNodes, "amountMsat" -> "1234", "finalCltvExpiry" -> "190", "invoice" -> PaymentRequest.write(pr)))) ~>
      addCredentials(BasicHttpCredentials("", mockPassword)) ~>
      addHeader("Content-Type", "application/json") ~>
      HttpService.sealRoute(service.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(responseAs[String] == expected)
        mockEclair.sendToRoute(1234 msat, None, None, None, pr, CltvExpiryDelta(190), expectedRoute, None, None, None, Nil)(any[Timeout]).wasCalled(once)
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