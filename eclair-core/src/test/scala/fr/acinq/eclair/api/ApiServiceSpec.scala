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


import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import akka.actor.{Actor, ActorSystem, Props, Scheduler}
import org.scalatest.FunSuite
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import fr.acinq.eclair.blockchain.TestWallet
import fr.acinq.eclair.{Kit, TestConstants}
import fr.acinq.eclair.io.Peer.{GetPeerInfo, PeerInfo}
import TestConstants._
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import fr.acinq.eclair.channel.Register.ForwardShortId
import org.json4s.{Formats, JValue}
import akka.http.scaladsl.model.{ContentTypes, FormData, MediaTypes, Multipart}
import fr.acinq.eclair.io.Peer
import scala.concurrent.duration._
import scala.io.Source

class ApiServiceSpec extends FunSuite with ScalatestRouteTest {

  implicit val formats = JsonSupport.formats
  implicit val serialization = JsonSupport.serialization
  implicit val marshaller = JsonSupport.marshaller
  implicit val unmarshaller = JsonSupport.unmarshaller

  implicit val routeTestTimeout = RouteTestTimeout(3 seconds)

  val defaultMockKit = Kit(
    nodeParams = Alice.nodeParams,
    system = system,
    watcher = system.actorOf(Props(new MockActor)),
    paymentHandler = system.actorOf(Props(new MockActor)),
    register = system.actorOf(Props(new MockActor)),
    relayer = system.actorOf(Props(new MockActor)),
    router = system.actorOf(Props(new MockActor)),
    switchboard = system.actorOf(Props(new MockActor)),
    paymentInitiator = system.actorOf(Props(new MockActor)),
    server = system.actorOf(Props(new MockActor)),
    wallet = new TestWallet
  )

  def defaultGetInfo = GetInfoResponse(
    nodeId = Alice.nodeParams.nodeId,
    alias = Alice.nodeParams.alias,
    chainHash = Alice.nodeParams.chainHash,
    blockHeight = 123456,
    publicAddresses = Alice.nodeParams.publicAddresses
  )

  class MockActor extends Actor {
    override def receive: Receive = {
      case _ =>
    }
  }

  class MockService(kit: Kit = defaultMockKit, getInfoResp: GetInfoResponse = defaultGetInfo) extends Service {
    override def eclairApi: EclairApi = new EclairApiImpl(kit)

    override def password: String = "mock"

    override implicit val actorSystem: ActorSystem = system
    override implicit val mat: ActorMaterializer = materializer
  }

  test("API service should handle failures correctly") {
    val mockService = new MockService

    // no auth
    Post("/help") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == Unauthorized)
      }

    // wrong auth
    Post("/help") ~>
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
        assert(entityAs[String].contains("The form field 'channelId' was malformed"))
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

  test("'help' should respond with a help message") {
    val mockService = new MockService()

    Post("/help") ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val resp = entityAs[String]
        matchTestJson("help", false, resp)
      }

  }

  test("'peers' should ask the switchboard for current known peers") {

    val mockAlicePeer = system.actorOf(Props(new {} with MockActor {
      override def receive = {
        case GetPeerInfo => sender() ! PeerInfo(
          nodeId = Alice.nodeParams.nodeId,
          state = "CONNECTED",
          address = Some(Alice.nodeParams.publicAddresses.head.socketAddress),
          channels = 1)
      }
    }))

    val mockBobPeer = system.actorOf(Props(new {} with MockActor {
      override def receive = {
        case GetPeerInfo => sender() ! PeerInfo(
          nodeId = Bob.nodeParams.nodeId,
          state = "DISCONNECTED",
          address = None,
          channels = 1)
      }
    }))


    val mockService = new MockService(defaultMockKit.copy(
      switchboard = system.actorOf(Props(new {} with MockActor {
        override def receive = {
          case 'peers => sender() ! List(mockAlicePeer, mockBobPeer)
        }
      }))
    ))

    Post("/peers") ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[String]
        matchTestJson("peers", false, response)
      }
  }

  test("'getinfo' response should include this node ID") {
    val mockService = new MockService()

    Post("/getinfo") ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val resp = entityAs[String]
        assert(resp.toString.contains(Alice.nodeParams.nodeId.toString))
        matchTestJson("getinfo", false, resp)
      }
  }

  test("'close' method should accept a shortChannelId") {

    val shortChannelIdSerialized = "42000x27x3"

    val mockService = new MockService(defaultMockKit.copy(
      register = system.actorOf(Props(new {} with MockActor {
        override def receive = {
          case ForwardShortId(shortChannelId, _) if shortChannelId.toString == shortChannelIdSerialized =>
            sender() ! Alice.nodeParams.nodeId.toString
        }
      }))
    ))


    Post("/close", FormData("shortChannelId" -> shortChannelIdSerialized).toEntity) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val resp = entityAs[String]
        assert(resp.contains(Alice.nodeParams.nodeId.toString))
        matchTestJson("close", false, resp)
      }
  }

  test("'connect' method should accept an URI and a triple with nodeId/host/port") {

    val remoteNodeId = "030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87"
    val remoteHost = "93.137.102.239"
    val remotePort = "9735"
    val remoteUri = "030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87@93.137.102.239:9735"

    val mockService = new MockService(defaultMockKit.copy(
      switchboard = system.actorOf(Props(new {} with MockActor {
        override def receive = {
          case Peer.Connect(_) => sender() ! "connected"
        }
      }))
    ))

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

  private def matchTestJson(apiName: String, overWrite: Boolean, response: String)(implicit formats: Formats) = {
    val p = Paths.get(s"src/test/resources/api/$apiName")

    if (overWrite) {
      Files.writeString(p, response)
      assert(false, "'overWrite' should be false before commit")
    } else {
      val expectedResponse = Source.fromFile(p.toUri).mkString
      assert(response == expectedResponse, s"Test mock for $apiName did not match the expected response")
    }
  }

}