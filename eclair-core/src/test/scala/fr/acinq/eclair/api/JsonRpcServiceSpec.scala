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


import java.io.{File, FileOutputStream}

import akka.actor.{Actor, ActorSystem, Props, Scheduler}
import org.scalatest.FunSuite
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import de.heikoseeberger.akkahttpjson4s.Json4sSupport.{marshaller, unmarshaller}
import fr.acinq.eclair.blockchain.TestWallet
import fr.acinq.eclair.{Kit, TestConstants}
import fr.acinq.eclair.io.Peer.{GetPeerInfo, PeerInfo}
import TestConstants._
import akka.NotUsed
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.Flow
import fr.acinq.eclair.channel.Register.ForwardShortId
import fr.acinq.eclair.router.{Graph, Router}
import org.json4s.Formats
import org.json4s.JsonAST.{JInt, JString}
import org.json4s.jackson.Serialization

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.io.Source
import scala.util.Try

class JsonRpcServiceSpec extends FunSuite with ScalatestRouteTest {

  implicit val routeTestTimeout = RouteTestTimeout(3 seconds)

  def defaultMockKit = Kit(
    nodeParams = Alice.nodeParams,
    system = system,
    watcher = system.actorOf(Props(new MockActor)),
    paymentHandler =  system.actorOf(Props(new MockActor)),
    register =  system.actorOf(Props(new MockActor)),
    relayer =  system.actorOf(Props(new MockActor)),
    router = system.actorOf(Props(new MockActor)),
    switchboard =  system.actorOf(Props(new MockActor)),
    paymentInitiator =  system.actorOf(Props(new MockActor)),
    server =  system.actorOf(Props(new MockActor)),
    wallet = new TestWallet
  )

  class MockActor extends Actor {
    override def receive: Receive = { case _ => }
  }

  class MockService(kit: Kit = defaultMockKit) extends Service {
    override def getInfoResponse: Future[GetInfoResponse] = Future.successful(???)

    override def appKit: Kit = kit

    override val scheduler: Scheduler = system.scheduler

    override def password: String = "mock"

    override val socketHandler: Flow[Message, TextMessage.Strict, NotUsed] = makeSocketHandler(system)(materializer)
  }

  test("API service should handle failures correctly"){
    val mockService = new MockService
    import mockService.formats
    import mockService.serialization

    // no auth
    Post("/", JsonRPCBody(method = "help", params = Seq.empty)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == Unauthorized)
      }

    // wrong auth
    Post("/", JsonRPCBody(method = "help", params = Seq.empty)) ~>
      addCredentials(BasicHttpCredentials("", mockService.password+"what!")) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == Unauthorized)
      }

    // correct auth but wrong URL
    Post("/mistake", JsonRPCBody(method = "help", params = Seq.empty)) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == NotFound)
      }

    // wrong rpc method
    Post("/", JsonRPCBody(method = "open_not_really", params = Seq.empty)) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == BadRequest)
      }

    // wrong params
    Post("/", JsonRPCBody(method = "open", params = Seq(JInt(123), JString("abc")))) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == BadRequest)
      }

  }

  test("'help' should respond with a help message") {
    val mockService = new MockService
    import mockService.formats
    import mockService.serialization

    val postBody = JsonRPCBody(method = "help", params = Seq.empty)

    Post("/", postBody) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val resp = entityAs[JsonRPCRes]
        matchTestJson("help", false ,resp)
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

    import mockService.formats
    import mockService.serialization

    val postBody = JsonRPCBody(method = "peers", params = Seq.empty)

    Post("/", postBody) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val response = entityAs[JsonRPCRes]
        val peerInfos = response.result.asInstanceOf[Seq[Map[String,String]]]
        assert(peerInfos.size == 2)
        assert(peerInfos.head.get("nodeId") == Some(Alice.nodeParams.nodeId.toString))
        assert(peerInfos.head.get("state") == Some("CONNECTED"))
        matchTestJson("peers", false, response)
      }
  }

  test("'getinfo' response should include this node ID") {
    val mockService = new {} with MockService {
      override def getInfoResponse: Future[GetInfoResponse] = Future.successful(GetInfoResponse(
        nodeId = Alice.nodeParams.nodeId,
        alias = Alice.nodeParams.alias,
        port = 9735,
        chainHash = Alice.nodeParams.chainHash,
        blockHeight = 123456,
        publicAddresses = Alice.nodeParams.publicAddresses
      ))
    }
    import mockService.formats
    import mockService.serialization

    val postBody = JsonRPCBody(method = "getinfo", params = Seq.empty)

    Post("/", postBody) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val resp = entityAs[JsonRPCRes]
        assert(resp.result.toString.contains(Alice.nodeParams.nodeId.toString))
        matchTestJson("getinfo", false ,resp)
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

    import mockService.formats
    import mockService.serialization


    val postBody = JsonRPCBody(method = "close", params = Seq(JString(shortChannelIdSerialized)))

    Post("/", postBody) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == OK)
        val resp = entityAs[JsonRPCRes]
        assert(resp.result.toString.contains(Alice.nodeParams.nodeId.toString))
        matchTestJson("close", false ,resp)
      }
  }

  private def readFileAsString(stream: File): Try[String] = Try(Source.fromFile(stream).mkString)

  private def matchTestJson(rpcMethod: String, overWrite: Boolean, response: JsonRPCRes)(implicit formats: Formats) = {
    val responseContent = Serialization.writePretty(response)
    val resourceName = s"/api/$rpcMethod"
    val resourceFile = new File(getClass.getResource(resourceName).toURI.toURL.getFile)
    if(overWrite) {
      new FileOutputStream(resourceFile).write(responseContent.getBytes)
      assert(false, "'overWrite' should be false before commit")
    } else {
      val expectedResponse = readFileAsString(resourceFile).getOrElse(throw new IllegalArgumentException(s"Mock file for '$resourceName' does not exist, please use 'overWrite' first."))
      assert(responseContent == expectedResponse, s"Test mock for $rpcMethod did not match the expected response")
    }
  }

}