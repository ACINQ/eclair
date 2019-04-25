/*
 * Copyright 2018 ACINQ SAS
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
import fr.acinq.bitcoin.{ByteVector32, Crypto, MilliSatoshi}
import fr.acinq.eclair.TestConstants._
import fr.acinq.eclair._
import fr.acinq.eclair.channel.RES_GETINFO
import fr.acinq.eclair.db.{IncomingPayment, NetworkFee, OutgoingPayment, Stats}
import fr.acinq.eclair.io.Peer.PeerInfo
import fr.acinq.eclair.payment.PaymentLifecycle.PaymentFailed
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.{ChannelDesc, RouteResponse}
import fr.acinq.eclair.wire.{ChannelUpdate, NodeAddress, NodeAnnouncement}
import org.json4s.jackson.Serialization
import org.scalatest.FunSuite
import scodec.bits.ByteVector

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.io.Source
import scala.reflect.ClassTag
import scala.util.Try

class ApiServiceSpec extends FunSuite with ScalatestRouteTest {

  trait EclairMock extends Eclair {
    override def connect(uri: String)(implicit timeout: Timeout): Future[String] = ???

    override def open(nodeId: Crypto.PublicKey, fundingSatoshis: Long, pushMsat: Option[Long], fundingFeerateSatByte: Option[Long], flags: Option[Int], timeout_opt: Option[Timeout])(implicit timeout: Timeout): Future[String] = ???

    override def close(channelIdentifier: Either[ByteVector32, ShortChannelId], scriptPubKey: Option[ByteVector])(implicit timeout: Timeout): Future[String] = ???

    override def forceClose(channelIdentifier: Either[ByteVector32, ShortChannelId])(implicit timeout: Timeout): Future[String] = ???

    override def updateRelayFee(channelId: String, feeBaseMsat: Long, feeProportionalMillionths: Long)(implicit timeout: Timeout): Future[String] = ???

    override def channelsInfo(toRemoteNode: Option[Crypto.PublicKey])(implicit timeout: Timeout): Future[Iterable[RES_GETINFO]] = ???

    override def channelInfo(channelIdentifier: Either[ByteVector32, ShortChannelId])(implicit timeout: Timeout): Future[RES_GETINFO] = ???

    override def peersInfo()(implicit timeout: Timeout): Future[Iterable[PeerInfo]] = ???

    override def receive(description: String, amountMsat: Option[Long], expire: Option[Long], fallbackAddress: Option[String])(implicit timeout: Timeout): Future[PaymentRequest] = ???

    override def receivedInfo(paymentHash: ByteVector32)(implicit timeout: Timeout): Future[Option[IncomingPayment]] = ???

    override def send(recipientNodeId: Crypto.PublicKey, amountMsat: Long, paymentHash: ByteVector32, assistedRoutes: Seq[Seq[PaymentRequest.ExtraHop]], minFinalCltvExpiry: Option[Long], maxAttempts: Option[Int])(implicit timeout: Timeout): Future[UUID] = ???

    override def sentInfo(id: Either[UUID, ByteVector32])(implicit timeout: Timeout): Future[Seq[OutgoingPayment]] = ???

    override def findRoute(targetNodeId: Crypto.PublicKey, amountMsat: Long, assistedRoutes: Seq[Seq[PaymentRequest.ExtraHop]])(implicit timeout: Timeout): Future[RouteResponse] = ???

    override def audit(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[AuditResponse] = ???

    override def networkFees(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[Seq[NetworkFee]] = ???

    override def channelStats()(implicit timeout: Timeout): Future[Seq[Stats]] = ???

    override def getInvoice(paymentHash: ByteVector32)(implicit timeout: Timeout): Future[Option[PaymentRequest]] = ???

    override def pendingInvoices(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[Seq[PaymentRequest]] = ???

    override def allInvoices(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[Seq[PaymentRequest]] = ???

    override def allNodes()(implicit timeout: Timeout): Future[Iterable[NodeAnnouncement]] = ???

    override def allChannels()(implicit timeout: Timeout): Future[Iterable[ChannelDesc]] = ???

    override def allUpdates(nodeId: Option[Crypto.PublicKey])(implicit timeout: Timeout): Future[Iterable[ChannelUpdate]] = ???

    override def getInfoResponse()(implicit timeout: Timeout): Future[GetInfoResponse] = ???
  }

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
    val mockService = new MockService(new EclairMock {})

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

    val mockService = new MockService(new EclairMock {
      override def peersInfo()(implicit timeout: Timeout): Future[Iterable[PeerInfo]] = Future.successful(List(
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
    })

    Post("/peers") ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        matchTestJson("peers", response)
      }
  }

  test("'getinfo' response should include this node ID") {

    val mockService = new MockService(new EclairMock {
      override def getInfoResponse()(implicit timeout: Timeout): Future[GetInfoResponse] = Future.successful(GetInfoResponse(
        nodeId = Alice.nodeParams.nodeId,
        alias = Alice.nodeParams.alias,
        chainHash = Alice.nodeParams.chainHash,
        blockHeight = 9999,
        publicAddresses = NodeAddress.fromParts("localhost", 9731).get :: Nil
      ))
    })

    Post("/getinfo") ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val resp = entityAs[String]
        assert(resp.toString.contains(Alice.nodeParams.nodeId.toString))
        matchTestJson("getinfo", resp)
      }
  }

  test("'close' method should accept a shortChannelId") {

    val shortChannelIdSerialized = "42000x27x3"

    val mockService = new MockService(new EclairMock {
      override def close(channelIdentifier: Either[ByteVector32, ShortChannelId], scriptPubKey: Option[ByteVector])(implicit timeout: Timeout): Future[String] = {
        Future.successful(Alice.nodeParams.nodeId.toString())
      }
    })

    Post("/close", FormData("shortChannelId" -> shortChannelIdSerialized).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val resp = entityAs[String]
        assert(resp.contains(Alice.nodeParams.nodeId.toString))
        matchTestJson("close", resp)
      }
  }

  test("'connect' method should accept an URI and a triple with nodeId/host/port") {

    val remoteNodeId = "030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87"
    val remoteHost = "93.137.102.239"
    val remoteUri = "030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87@93.137.102.239:9735"

    val mockService = new MockService(new EclairMock {
      override def connect(uri: String)(implicit timeout: Timeout): Future[String] = Future.successful("connected")
    })

    Post("/connect", FormData("nodeId" -> remoteNodeId, "host" -> remoteHost).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(entityAs[String] == "\"connected\"")
      }

    Post("/connect", FormData("uri" -> remoteUri).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(entityAs[String] == "\"connected\"")
      }
  }

  test("'send' method should return the UUID of the outgoing payment") {

    val id = UUID.randomUUID()
    val invoice = "lnbc12580n1pw2ywztpp554ganw404sh4yjkwnysgn3wjcxfcq7gtx53gxczkjr9nlpc3hzvqdq2wpskwctddyxqr4rqrzjqwryaup9lh50kkranzgcdnn2fgvx390wgj5jd07rwr3vxeje0glc7z9rtvqqwngqqqqqqqlgqqqqqeqqjqrrt8smgjvfj7sg38dwtr9kc9gg3era9k3t2hvq3cup0jvsrtrxuplevqgfhd3rzvhulgcxj97yjuj8gdx8mllwj4wzjd8gdjhpz3lpqqvk2plh"

    val mockService = new MockService(new EclairMock {
      override def send(recipientNodeId: Crypto.PublicKey, amountMsat: Long, paymentHash: ByteVector32, assistedRoutes: Seq[Seq[PaymentRequest.ExtraHop]], minFinalCltvExpiry: Option[Long], maxAttempts: Option[Int] = None)(implicit timeout: Timeout): Future[UUID] = Future.successful(
        id
      )
    })

    Post("/payinvoice", FormData("invoice" -> invoice).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        assert(entityAs[String] == "\""+id.toString+"\"")
      }
  }

  test("'receivedinfo' method should respond HTTP 404 with a JSON encoded response if the element is not found") {

    val mockService = new MockService(new EclairMock {
      override def receivedInfo(paymentHash: ByteVector32)(implicit timeout: Timeout): Future[Option[IncomingPayment]] = Future.successful(None) // element not found
    })

    Post("/getreceivedinfo", FormData("paymentHash" -> ByteVector32.Zeroes.toHex).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == NotFound)
        val resp = entityAs[ErrorResponse](JsonSupport.unmarshaller, ClassTag(classOf[ErrorResponse]))
        assert(resp == ErrorResponse("Not found"))
      }
  }


  test("the websocket should return typed objects") {

    val mockService = new MockService(new EclairMock {})
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