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

import akka.actor.{Actor, ActorSystem, Props, Scheduler}
import org.scalatest.FunSuite
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import fr.acinq.eclair._
import fr.acinq.eclair.io.Peer.{GetPeerInfo, PeerInfo}
import TestConstants._
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.http.scaladsl.model.{ContentTypes, FormData, MediaTypes, Multipart}
import fr.acinq.bitcoin.{ByteVector32, Crypto}
import fr.acinq.eclair.channel.RES_GETINFO
import fr.acinq.eclair.db.{NetworkFee, Stats}
import fr.acinq.eclair.payment.{PaymentLifecycle, PaymentRequest}
import fr.acinq.eclair.router.{ChannelDesc, RouteResponse}
import fr.acinq.eclair.wire.{ChannelUpdate, NodeAddress, NodeAnnouncement}
import scodec.bits.ByteVector
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.io.Source
import scala.reflect.ClassTag
import scala.util.Try

class ApiServiceSpec extends FunSuite with ScalatestRouteTest {

  trait EclairMock extends Eclair {
    override def connect(uri: String): Future[String] = ???

    override def open(nodeId: Crypto.PublicKey, fundingSatoshis: Long, pushMsat: Option[Long], fundingFeerateSatByte: Option[Long], flags: Option[Int]): Future[String] = ???

    override def close(channelIdentifier: Either[ByteVector32, ShortChannelId], scriptPubKey: Option[ByteVector]): Future[String] = ???

    override def forceClose(channelIdentifier: Either[ByteVector32, ShortChannelId]): Future[String] = ???

    override def updateRelayFee(channelId: String, feeBaseMsat: Long, feeProportionalMillionths: Long): Future[String] = ???

    override def peersInfo(): Future[Iterable[PeerInfo]] = ???

    override def channelsInfo(toRemoteNode: Option[Crypto.PublicKey]): Future[Iterable[RES_GETINFO]] = ???

    override def channelInfo(channelId: ByteVector32): Future[RES_GETINFO] = ???

    override def allnodes(): Future[Iterable[NodeAnnouncement]] = ???

    override def allchannels(): Future[Iterable[ChannelDesc]] = ???

    override def allupdates(nodeId: Option[Crypto.PublicKey]): Future[Iterable[ChannelUpdate]] = ???

    override def receive(description: String, amountMsat: Option[Long], expire: Option[Long]): Future[String] = ???

    override def findRoute(targetNodeId: Crypto.PublicKey, amountMsat: Long, assistedRoutes: Seq[Seq[PaymentRequest.ExtraHop]]): Future[RouteResponse] = ???

    override def send(recipientNodeId: Crypto.PublicKey, amountMsat: Long, paymentHash: ByteVector32, assistedRoutes: Seq[Seq[PaymentRequest.ExtraHop]], minFinalCltvExpiry: Option[Long]): Future[PaymentLifecycle.PaymentResult] = ???

    override def checkpayment(paymentHash: ByteVector32): Future[Boolean] = ???

    override def audit(from_opt: Option[Long], to_opt: Option[Long]): Future[AuditResponse] = ???

    override def networkFees(from_opt: Option[Long], to_opt: Option[Long]): Future[Seq[NetworkFee]] = ???

    override def channelStats(): Future[Seq[Stats]] = ???

    override def getInfoResponse(): Future[GetInfoResponse] = ???
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
        println(resp.error)
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
      override def peersInfo(): Future[Iterable[PeerInfo]] = Future.successful(List(
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
      override def getInfoResponse(): Future[GetInfoResponse] = Future.successful(GetInfoResponse(
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
      override def close(channelIdentifier: Either[ByteVector32, ShortChannelId], scriptPubKey: Option[ByteVector]): Future[String] = {
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
    val remotePort = "9735"
    val remoteUri = "030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87@93.137.102.239:9735"

    val mockService = new MockService(new EclairMock {
      override def connect(uri: String): Future[String] = Future.successful("connected")
    })

    Post("/connect", FormData("nodeId" -> remoteNodeId, "host" -> remoteHost, "port" -> remotePort).toEntity) ~>
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
        println(entityAs[String])
        assert(entityAs[String] == "\"connected\"")
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