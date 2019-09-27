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
import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair._
import fr.acinq.eclair.db.{IncomingPayment, IncomingPaymentStatus, OutgoingPayment, OutgoingPaymentStatus}
import fr.acinq.eclair.io.NodeURI
import fr.acinq.eclair.io.Peer.PeerInfo
import fr.acinq.eclair.payment.{PaymentFailed, _}
import fr.acinq.eclair.wire.NodeAddress
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
  implicit val routeTestTimeout = RouteTestTimeout(3 seconds)

  val aliceNodeId = PublicKey(hex"03af0ed6052cf28d670665549bc86f4b721c9fdb309d40c58f5811f63966e005d0")
  val bobNodeId = PublicKey(hex"039dc0e0b1d25905e44fdf6f8e89755a5e219685840d0bc1d28d3308f9628a3585")

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
        val resp = entityAs[ErrorResponse](Json4sSupport.unmarshaller, ClassTag(classOf[ErrorResponse]))
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

  test("'usablebalances' asks router for current usable balances") {
    val eclair = mock[Eclair]
    val mockService = new MockService(eclair)
    eclair.usableBalances()(any[Timeout]) returns Future.successful(List(
      UsableBalances(canSend = 100000000 msat, canReceive = 20000000 msat, shortChannelId = ShortChannelId(1), remoteNodeId = aliceNodeId, isPublic = true),
      UsableBalances(canSend = 400000000 msat, canReceive = 30000000 msat, shortChannelId = ShortChannelId(2), remoteNodeId = aliceNodeId, isPublic = false)
    ))

    Post("/usablebalances") ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      Route.seal(mockService.route) ~>
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
    eclair.getInfoResponse()(any[Timeout]) returns Future.successful(GetInfoResponse(
      nodeId = aliceNodeId,
      alias = "alice",
      chainHash = ByteVector32(hex"06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f"),
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
        assert(resp.toString.contains(aliceNodeId.toString))
        eclair.getInfoResponse()(any[Timeout]).wasCalled(once)
        matchTestJson("getinfo", resp)
      }
  }

  test("'close' method should accept a channelId and shortChannelId") {
    val shortChannelIdSerialized = "42000x27x3"
    val channelId = "56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e"

    val eclair = mock[Eclair]
    eclair.close(any, any)(any[Timeout]) returns Future.successful(aliceNodeId.toString())
    val mockService = new MockService(eclair)

    Post("/close", FormData("shortChannelId" -> shortChannelIdSerialized).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val resp = entityAs[String]
        assert(resp.contains(aliceNodeId.toString))
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
        assert(resp.contains(aliceNodeId.toString))
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

    Post("/connect", FormData("nodeId" -> remoteNodeId.toString()).toEntity) ~>
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

  test("'send' method should handle payment failures") {
    val eclair = mock[Eclair]
    eclair.send(any, any, any, any, any, any, any, any)(any[Timeout]) returns Future.failed(new IllegalArgumentException("invoice has expired"))
    val mockService = new MockService(eclair)

    val invoice = "lnbc12580n1pw2ywztpp554ganw404sh4yjkwnysgn3wjcxfcq7gtx53gxczkjr9nlpc3hzvqdq2wpskwctddyxqr4rqrzjqwryaup9lh50kkranzgcdnn2fgvx390wgj5jd07rwr3vxeje0glc7z9rtvqqwngqqqqqqqlgqqqqqeqqjqrrt8smgjvfj7sg38dwtr9kc9gg3era9k3t2hvq3cup0jvsrtrxuplevqgfhd3rzvhulgcxj97yjuj8gdx8mllwj4wzjd8gdjhpz3lpqqvk2plh"

    Post("/payinvoice", FormData("invoice" -> invoice).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == BadRequest)
        val resp = entityAs[ErrorResponse](Json4sSupport.unmarshaller, ClassTag(classOf[ErrorResponse]))
        assert(resp.error == "invoice has expired")
        eclair.send(None, any, 1258000 msat, any, any, any, any, any)(any[Timeout]).wasCalled(once)
      }
  }

  test("'send' method should correctly forward amount parameters to EclairImpl") {
    val invoice = "lnbc12580n1pw2ywztpp554ganw404sh4yjkwnysgn3wjcxfcq7gtx53gxczkjr9nlpc3hzvqdq2wpskwctddyxqr4rqrzjqwryaup9lh50kkranzgcdnn2fgvx390wgj5jd07rwr3vxeje0glc7z9rtvqqwngqqqqqqqlgqqqqqeqqjqrrt8smgjvfj7sg38dwtr9kc9gg3era9k3t2hvq3cup0jvsrtrxuplevqgfhd3rzvhulgcxj97yjuj8gdx8mllwj4wzjd8gdjhpz3lpqqvk2plh"

    val eclair = mock[Eclair]
    eclair.send(any, any, any, any, any, any, any, any)(any[Timeout]) returns Future.successful(UUID.randomUUID())
    val mockService = new MockService(eclair)

    Post("/payinvoice", FormData("invoice" -> invoice).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        eclair.send(None, any, 1258000 msat, any, any, any, any, any)(any[Timeout]).wasCalled(once)
      }

    Post("/payinvoice", FormData("invoice" -> invoice, "amountMsat" -> "123", "feeThresholdSat" -> "112233", "maxFeePct" -> "2.34", "externalId" -> "42").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        eclair.send(Some("42"), any, 123 msat, any, any, any, Some(112233 sat), Some(2.34))(any[Timeout]).wasCalled(once)
      }
  }

  test("'getreceivedinfo'") {
    val invoice = "lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpuaztrnwngzn3kdzw5hydlzf03qdgm2hdq27cqv3agm2awhz5se903vruatfhq77w3ls4evs3ch9zw97j25emudupq63nyw24cg27h2rspfj9srp"
    val defaultPayment = IncomingPayment(PaymentRequest.read(invoice), ByteVector32.One, 42, IncomingPaymentStatus.Pending)
    val eclair = mock[Eclair]
    val notFound = randomBytes32
    eclair.receivedInfo(notFound)(any) returns Future.successful(None)
    val pending = randomBytes32
    eclair.receivedInfo(pending)(any) returns Future.successful(Some(defaultPayment))
    val expired = randomBytes32
    eclair.receivedInfo(expired)(any) returns Future.successful(Some(defaultPayment.copy(status = IncomingPaymentStatus.Expired)))
    val received = randomBytes32
    eclair.receivedInfo(received)(any) returns Future.successful(Some(defaultPayment.copy(status = IncomingPaymentStatus.Received(42 msat, 45))))
    val mockService = new MockService(eclair)

    Post("/getreceivedinfo", FormData("paymentHash" -> notFound.toHex).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == NotFound)
        val resp = entityAs[ErrorResponse](Json4sSupport.unmarshaller, ClassTag(classOf[ErrorResponse]))
        assert(resp == ErrorResponse("Not found"))
        eclair.receivedInfo(notFound)(any[Timeout]).wasCalled(once)
      }

    Post("/getreceivedinfo", FormData("paymentHash" -> pending.toHex).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        matchTestJson("received-pending", response)
        eclair.receivedInfo(pending)(any[Timeout]).wasCalled(once)
      }

    Post("/getreceivedinfo", FormData("paymentHash" -> expired.toHex).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        matchTestJson("received-expired", response)
        eclair.receivedInfo(expired)(any[Timeout]).wasCalled(once)
      }

    Post("/getreceivedinfo", FormData("paymentHash" -> received.toHex).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        matchTestJson("received-success", response)
        eclair.receivedInfo(received)(any[Timeout]).wasCalled(once)
      }
  }

  test("'getsentinfo'") {
    val defaultPayment = OutgoingPayment(UUID.fromString("00000000-0000-0000-0000-000000000000"), UUID.fromString("11111111-1111-1111-1111-111111111111"), None, ByteVector32.Zeroes, 42 msat, aliceNodeId, 1, None, OutgoingPaymentStatus.Pending)
    val eclair = mock[Eclair]
    val pending = UUID.randomUUID()
    eclair.sentInfo(Left(pending))(any) returns Future.successful(Seq(defaultPayment))
    val failed = UUID.randomUUID()
    eclair.sentInfo(Left(failed))(any) returns Future.successful(Seq(defaultPayment.copy(status = OutgoingPaymentStatus.Failed(Nil, 2))))
    val sent = UUID.randomUUID()
    eclair.sentInfo(Left(sent))(any) returns Future.successful(Seq(defaultPayment.copy(status = OutgoingPaymentStatus.Succeeded(ByteVector32.One, 5 msat, Nil, 3))))
    val mockService = new MockService(eclair)

    Post("/getsentinfo", FormData("id" -> pending.toString).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        matchTestJson("sent-pending", response)
        eclair.sentInfo(Left(pending))(any[Timeout]).wasCalled(once)
      }

    Post("/getsentinfo", FormData("id" -> failed.toString).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        matchTestJson("sent-failed", response)
        eclair.sentInfo(Left(failed))(any[Timeout]).wasCalled(once)
      }

    Post("/getsentinfo", FormData("id" -> sent.toString).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        matchTestJson("sent-success", response)
        eclair.sentInfo(Left(sent))(any[Timeout]).wasCalled(once)
      }
  }

  test("'sendtoroute' method should accept a both a json-encoded AND comma separaterd list of pubkeys") {
    val rawUUID = "487da196-a4dc-4b1e-92b4-3e5e905e9f3f"
    val paymentUUID = UUID.fromString(rawUUID)
    val externalId = UUID.randomUUID().toString
    val expectedRoute = List(PublicKey(hex"0217eb8243c95f5a3b7d4c5682d10de354b7007eb59b6807ae407823963c7547a9"), PublicKey(hex"0242a4ae0c5bef18048fbecf995094b74bfb0f7391418d71ed394784373f41e4f3"), PublicKey(hex"026ac9fcd64fb1aa1c491fc490634dc33da41d4a17b554e0adf1b32fee88ee9f28"))
    val csvNodes = "0217eb8243c95f5a3b7d4c5682d10de354b7007eb59b6807ae407823963c7547a9, 0242a4ae0c5bef18048fbecf995094b74bfb0f7391418d71ed394784373f41e4f3, 026ac9fcd64fb1aa1c491fc490634dc33da41d4a17b554e0adf1b32fee88ee9f28"
    val jsonNodes = serialization.write(expectedRoute)

    val eclair = mock[Eclair]
    eclair.sendToRoute(any[Option[String]], any[List[PublicKey]], any[MilliSatoshi], any[ByteVector32], any[CltvExpiryDelta])(any[Timeout]) returns Future.successful(paymentUUID)
    val mockService = new MockService(eclair)

    Post("/sendtoroute", FormData("route" -> jsonNodes, "amountMsat" -> "1234", "paymentHash" -> ByteVector32.Zeroes.toHex, "finalCltvExpiry" -> "190", "externalId" -> externalId.toString).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(entityAs[String] == "\"" + rawUUID + "\"")
        eclair.sendToRoute(Some(externalId), expectedRoute, 1234 msat, ByteVector32.Zeroes, CltvExpiryDelta(190))(any[Timeout]).wasCalled(once)
      }

    // this test uses CSV encoded route
    Post("/sendtoroute", FormData("route" -> csvNodes, "amountMsat" -> "1234", "paymentHash" -> ByteVector32.One.toHex, "finalCltvExpiry" -> "190").toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(entityAs[String] == "\"" + rawUUID + "\"")
        eclair.sendToRoute(None, expectedRoute, 1234 msat, ByteVector32.One, CltvExpiryDelta(190))(any[Timeout]).wasCalled(once)
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
        val pf = PaymentFailed(fixedUUID, ByteVector32.Zeroes, failures = Seq.empty, timestamp = 1553784963659L)
        val expectedSerializedPf = """{"type":"payment-failed","id":"487da196-a4dc-4b1e-92b4-3e5e905e9f3f","paymentHash":"0000000000000000000000000000000000000000000000000000000000000000","failures":[],"timestamp":1553784963659}"""
        assert(serialization.write(pf) === expectedSerializedPf)
        system.eventStream.publish(pf)
        wsClient.expectMessage(expectedSerializedPf)

        val ps = PaymentSent(fixedUUID, ByteVector32.Zeroes, ByteVector32.One, Seq(PaymentSent.PartialPayment(fixedUUID, 21 msat, 1 msat, ByteVector32.Zeroes, None, 1553784337711L)))
        val expectedSerializedPs = """{"type":"payment-sent","id":"487da196-a4dc-4b1e-92b4-3e5e905e9f3f","paymentHash":"0000000000000000000000000000000000000000000000000000000000000000","paymentPreimage":"0100000000000000000000000000000000000000000000000000000000000000","parts":[{"id":"487da196-a4dc-4b1e-92b4-3e5e905e9f3f","amount":21,"feesPaid":1,"toChannelId":"0000000000000000000000000000000000000000000000000000000000000000","timestamp":1553784337711}]}"""
        assert(serialization.write(ps) === expectedSerializedPs)
        system.eventStream.publish(ps)
        wsClient.expectMessage(expectedSerializedPs)

        val prel = PaymentRelayed(amountIn = 21 msat, amountOut = 20 msat, paymentHash = ByteVector32.Zeroes, fromChannelId = ByteVector32.Zeroes, ByteVector32.One, timestamp = 1553784963659L)
        val expectedSerializedPrel = """{"type":"payment-relayed","amountIn":21,"amountOut":20,"paymentHash":"0000000000000000000000000000000000000000000000000000000000000000","fromChannelId":"0000000000000000000000000000000000000000000000000000000000000000","toChannelId":"0100000000000000000000000000000000000000000000000000000000000000","timestamp":1553784963659}"""
        assert(serialization.write(prel) === expectedSerializedPrel)
        system.eventStream.publish(prel)
        wsClient.expectMessage(expectedSerializedPrel)

        val precv = PaymentReceived(ByteVector32.Zeroes, Seq(PaymentReceived.PartialPayment(21 msat, ByteVector32.Zeroes, 1553784963659L)))
        val expectedSerializedPrecv = """{"type":"payment-received","paymentHash":"0000000000000000000000000000000000000000000000000000000000000000","parts":[{"amount":21,"fromChannelId":"0000000000000000000000000000000000000000000000000000000000000000","timestamp":1553784963659}]}"""
        assert(serialization.write(precv) === expectedSerializedPrecv)
        system.eventStream.publish(precv)
        wsClient.expectMessage(expectedSerializedPrecv)

        val pset = PaymentSettlingOnChain(fixedUUID, amount = 21 msat, paymentHash = ByteVector32.One, timestamp = 1553785442676L)
        val expectedSerializedPset = """{"type":"payment-settling-onchain","id":"487da196-a4dc-4b1e-92b4-3e5e905e9f3f","amount":21,"paymentHash":"0100000000000000000000000000000000000000000000000000000000000000","timestamp":1553785442676}"""
        assert(serialization.write(pset) === expectedSerializedPset)
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