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

import akka.actor.ActorSystem
import akka.http.scaladsl.model.FormData
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest, WSProbe}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, Crypto, MilliSatoshi}
import fr.acinq.eclair.TestConstants._
import fr.acinq.eclair._
import fr.acinq.eclair.channel.RES_GETINFO
import fr.acinq.eclair.db.{IncomingPayment, NetworkFee, OutgoingPayment, Stats}
import fr.acinq.eclair.io.NodeURI
import fr.acinq.eclair.io.Peer.PeerInfo
import fr.acinq.eclair.payment.PaymentLifecycle.PaymentFailed
import fr.acinq.eclair.payment._
import fr.acinq.eclair.wire.{ChannelUpdate, NodeAddress, NodeAnnouncement}
import org.json4s.jackson.Serialization
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.{FunSuite, Matchers}
import scodec.bits._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.io.Source
import scala.reflect.ClassTag
import scala.util.Try

class ApiServiceSpec extends FunSuite with ScalatestRouteTest with IdiomaticMockito with Matchers {

  implicit val formats = JsonSupport.formats
  implicit val serialization = JsonSupport.serialization
  implicit val marshaller = JsonSupport.marshaller
  implicit val unmarshaller = JsonSupport.unmarshaller

  implicit val routeTestTimeout = RouteTestTimeout(3 seconds)

  class MockService(eclair: Eclair) extends Service {
    override val eclairApi: Eclair = eclair

    override def password: String = "mock"

    override implicit val actorSystem: ActorSystem = system
    override implicit val mat: ActorMaterializer = materializer
  }

  test("API service should handle failures correctly") {
    val mockService = new MockService(mock[Eclair])

    // no auth
    Post("/getinfo") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == Unauthorized)
      }

    // wrong auth
    Post("/getinfo") ~>
      addCredentials(BasicHttpCredentials("", mockService.password + "what!")) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == Unauthorized)
      }

    // correct auth but wrong URL
    Post("/mistake") ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == NotFound)
      }

    // wrong param type
    Post("/channel", FormData(Map("channelId" -> "hey")).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == BadRequest)
        val resp = entityAs[ErrorResponse](JsonSupport.unmarshaller, ClassTag(classOf[ErrorResponse]))
        assert(resp.error == "The form field 'channelId' was malformed:\nInvalid hexadecimal character 'h' at index 0")
      }

    // wrong params
    Post("/connect", FormData("urb" -> "030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87@93.137.102.239:9735").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == BadRequest)
      }

  }

  test("'peers' should ask the switchboard for current known peers") {

    val eclair = mock[Eclair]
    val mockService = new MockService(eclair)
    eclair.peersInfo()(any[Timeout]) returns Future.successful(List(
      PeerInfo(
        nodeId = Alice.nodeParams.nodeId,
        state = "CONNECTED",
        address = Some(Alice.nodeParams.publicAddresses.head.socketAddress),
        channels = 1),
      PeerInfo(
        nodeId = Bob.nodeParams.nodeId,
        state = "DISCONNECTED",
        address = None,
        channels = 1)))

    Post("/peers") ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        eclair.peersInfo()(any[Timeout]).wasCalled(once)
        matchTestJson("peers", response)
      }
  }

  test("'getinfo' response should include this node ID") {

    val eclair = mock[Eclair]
    val mockService = new MockService(eclair)
    eclair.getInfoResponse()(any[Timeout]) returns Future.successful(GetInfoResponse(
      nodeId = Alice.nodeParams.nodeId,
      alias = Alice.nodeParams.alias,
      chainHash = Alice.nodeParams.chainHash,
      blockHeight = 9999,
      publicAddresses = NodeAddress.fromParts("localhost", 9731).get :: Nil
    ))

    Post("/getinfo") ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val resp = entityAs[String]
        assert(resp.toString.contains(Alice.nodeParams.nodeId.toString))
        eclair.getInfoResponse()(any[Timeout]).wasCalled(once)
        matchTestJson("getinfo", resp)
      }
  }

  test("'close' method should accept a channelId and shortChannelId") {

    val shortChannelIdSerialized = "42000x27x3"
    val channelId = "56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e"

    val eclair = mock[Eclair]
    eclair.close(any, any)(any[Timeout]) returns Future.successful(Alice.nodeParams.nodeId.toString())
    val mockService = new MockService(eclair)

    Post("/close", FormData("shortChannelId" -> shortChannelIdSerialized).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val resp = entityAs[String]
        assert(resp.contains(Alice.nodeParams.nodeId.toString))
        eclair.close(Right(ShortChannelId(shortChannelIdSerialized)), None)(any[Timeout]).wasCalled(once)
        matchTestJson("close", resp)
      }

    Post("/close", FormData("channelId" -> channelId).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val resp = entityAs[String]
        assert(resp.contains(Alice.nodeParams.nodeId.toString))
        eclair.close(Left(ByteVector32.fromValidHex(channelId)), None)(any[Timeout]).wasCalled(once)
        matchTestJson("close", resp)
      }
  }

  test("'connect' method should accept an URI and a triple with nodeId/host/port") {

    val remoteNodeId = PublicKey(hex"030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87")
    val remoteUri = NodeURI.parse("030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87@93.137.102.239:9735")

    val eclair = mock[Eclair]
    eclair.connect(any[Either[NodeURI, PublicKey]])(any[Timeout]) returns Future.successful("connected")
    val mockService = new MockService(eclair)

    Post("/connect", FormData("nodeId" -> remoteNodeId.toHex).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(entityAs[String] == "\"connected\"")
        eclair.connect(Right(remoteNodeId))(any[Timeout]).wasCalled(once)
      }

    Post("/connect", FormData("uri" -> remoteUri.toString).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(entityAs[String] == "\"connected\"")
        eclair.connect(Left(remoteUri))(any[Timeout]).wasCalled(once) // must account for the previous, identical, invocation
      }
  }


  test("'send' method should correctly forward amount parameters to EclairImpl") {

    val invoice = "lnbc12580n1pw2ywztpp554ganw404sh4yjkwnysgn3wjcxfcq7gtx53gxczkjr9nlpc3hzvqdq2wpskwctddyxqr4rqrzjqwryaup9lh50kkranzgcdnn2fgvx390wgj5jd07rwr3vxeje0glc7z9rtvqqwngqqqqqqqlgqqqqqeqqjqrrt8smgjvfj7sg38dwtr9kc9gg3era9k3t2hvq3cup0jvsrtrxuplevqgfhd3rzvhulgcxj97yjuj8gdx8mllwj4wzjd8gdjhpz3lpqqvk2plh"

    val eclair = mock[Eclair]
    eclair.send(any, any, any, any, any, any)(any[Timeout]) returns Future.successful(UUID.randomUUID())
    val mockService = new MockService(eclair)

    Post("/payinvoice", FormData("invoice" -> invoice).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        eclair.send(any, 1258000, any, any, any, any)(any[Timeout]).wasCalled(once)
      }


    Post("/payinvoice", FormData("invoice" -> invoice, "amountMsat" -> "123").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        eclair.send(any, 123, any, any, any, any)(any[Timeout]).wasCalled(once)
      }

  }

  test("'getreceivedinfo' method should respond HTTP 404 with a JSON encoded response if the element is not found") {

    val eclair = mock[Eclair]
    eclair.receivedInfo(any[ByteVector32])(any) returns Future.successful(None)
    val mockService = new MockService(eclair)

    Post("/getreceivedinfo", FormData("paymentHash" -> ByteVector32.Zeroes.toHex).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == NotFound)
        val resp = entityAs[ErrorResponse](JsonSupport.unmarshaller, ClassTag(classOf[ErrorResponse]))
        assert(resp == ErrorResponse("Not found"))
        eclair.receivedInfo(ByteVector32.Zeroes)(any[Timeout]).wasCalled(once)
      }
  }

  test("'sendtoroute' method should accept a both a json-encoded AND comma separaterd list of pubkeys") {

    val rawUUID = "487da196-a4dc-4b1e-92b4-3e5e905e9f3f"
    val paymentUUID = UUID.fromString(rawUUID)
    val expectedRoute = List(PublicKey(hex"0217eb8243c95f5a3b7d4c5682d10de354b7007eb59b6807ae407823963c7547a9"), PublicKey(hex"0242a4ae0c5bef18048fbecf995094b74bfb0f7391418d71ed394784373f41e4f3"), PublicKey(hex"026ac9fcd64fb1aa1c491fc490634dc33da41d4a17b554e0adf1b32fee88ee9f28"))
    val csvNodes = "0217eb8243c95f5a3b7d4c5682d10de354b7007eb59b6807ae407823963c7547a9, 0242a4ae0c5bef18048fbecf995094b74bfb0f7391418d71ed394784373f41e4f3, 026ac9fcd64fb1aa1c491fc490634dc33da41d4a17b554e0adf1b32fee88ee9f28"
    val jsonNodes = serialization.write(expectedRoute)

    val eclair = mock[Eclair]
    eclair.sendToRoute(any[List[PublicKey]], anyLong, any[ByteVector32], anyLong)(any[Timeout]) returns Future.successful(paymentUUID)
    val mockService = new MockService(eclair)

    Post("/sendtoroute", FormData("route" -> jsonNodes, "amountMsat" -> "1234", "paymentHash" -> ByteVector32.Zeroes.toHex, "finalCltvExpiry" -> "190").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(entityAs[String] == "\""+rawUUID+"\"")
        eclair.sendToRoute(expectedRoute, 1234, ByteVector32.Zeroes, 190)(any[Timeout]).wasCalled(once)
      }

    // this test uses CSV encoded route
    Post("/sendtoroute", FormData("route" -> csvNodes, "amountMsat" -> "1234", "paymentHash" -> ByteVector32.One.toHex, "finalCltvExpiry" -> "190").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(entityAs[String] == "\""+rawUUID+"\"")
        eclair.sendToRoute(expectedRoute, 1234, ByteVector32.One, 190)(any[Timeout]).wasCalled(once)
      }
  }

  test("the websocket should return typed objects") {

    val mockService = new MockService(mock[Eclair])
    val fixedUUID = UUID.fromString("487da196-a4dc-4b1e-92b4-3e5e905e9f3f")

    val wsClient = WSProbe()

    WS("/ws", wsClient.flow) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      mockService.route ~>
      check {

        val pf = PaymentFailed(fixedUUID, ByteVector32.Zeroes, failures = Seq.empty)
        val expectedSerializedPf = """{"type":"payment-failed","id":"487da196-a4dc-4b1e-92b4-3e5e905e9f3f","paymentHash":"0000000000000000000000000000000000000000000000000000000000000000","failures":[]}"""
        Serialization.write(pf)(mockService.formatsWithTypeHint) === expectedSerializedPf
        system.eventStream.publish(pf)
        wsClient.expectMessage(expectedSerializedPf)

        val ps = PaymentSent(fixedUUID, amount = MilliSatoshi(21), feesPaid = MilliSatoshi(1), paymentHash = ByteVector32.Zeroes, paymentPreimage = ByteVector32.One, toChannelId = ByteVector32.Zeroes, timestamp = 1553784337711L)
        val expectedSerializedPs = """{"type":"payment-sent","id":"487da196-a4dc-4b1e-92b4-3e5e905e9f3f","amount":21,"feesPaid":1,"paymentHash":"0000000000000000000000000000000000000000000000000000000000000000","paymentPreimage":"0100000000000000000000000000000000000000000000000000000000000000","toChannelId":"0000000000000000000000000000000000000000000000000000000000000000","timestamp":1553784337711}"""
        Serialization.write(ps)(mockService.formatsWithTypeHint) === expectedSerializedPs
        system.eventStream.publish(ps)
        wsClient.expectMessage(expectedSerializedPs)

        val prel = PaymentRelayed(amountIn = MilliSatoshi(21), amountOut = MilliSatoshi(20), paymentHash = ByteVector32.Zeroes, fromChannelId = ByteVector32.Zeroes, ByteVector32.One, timestamp = 1553784963659L)
        val expectedSerializedPrel = """{"type":"payment-relayed","amountIn":21,"amountOut":20,"paymentHash":"0000000000000000000000000000000000000000000000000000000000000000","fromChannelId":"0000000000000000000000000000000000000000000000000000000000000000","toChannelId":"0100000000000000000000000000000000000000000000000000000000000000","timestamp":1553784963659}"""
        Serialization.write(prel)(mockService.formatsWithTypeHint) === expectedSerializedPrel
        system.eventStream.publish(prel)
        wsClient.expectMessage(expectedSerializedPrel)

        val precv = PaymentReceived(amount = MilliSatoshi(21), paymentHash = ByteVector32.Zeroes, fromChannelId = ByteVector32.One, timestamp = 1553784963659L)
        val expectedSerializedPrecv = """{"type":"payment-received","amount":21,"paymentHash":"0000000000000000000000000000000000000000000000000000000000000000","fromChannelId":"0100000000000000000000000000000000000000000000000000000000000000","timestamp":1553784963659}"""
        Serialization.write(precv)(mockService.formatsWithTypeHint) === expectedSerializedPrecv
        system.eventStream.publish(precv)
        wsClient.expectMessage(expectedSerializedPrecv)

        val pset = PaymentSettlingOnChain(fixedUUID, amount = MilliSatoshi(21), paymentHash = ByteVector32.One, timestamp = 1553785442676L)
        val expectedSerializedPset = """{"type":"payment-settling-onchain","id":"487da196-a4dc-4b1e-92b4-3e5e905e9f3f","amount":21,"paymentHash":"0100000000000000000000000000000000000000000000000000000000000000","timestamp":1553785442676}"""
        Serialization.write(pset)(mockService.formatsWithTypeHint) === expectedSerializedPset
        system.eventStream.publish(pset)
        wsClient.expectMessage(expectedSerializedPset)
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