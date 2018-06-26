package fr.acinq.eclair.api

import java.nio.charset.StandardCharsets._
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.nio.file.StandardOpenOption._
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
import org.json4s.Formats
import org.json4s.JsonAST.{JInt, JString}
import org.json4s.jackson.Serialization
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.Future
import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class JsonRpcServiceSpec extends FunSuite with ScalatestRouteTest {
  
  //a WARN is being thrown by akka, currently there is an open issue to test the
  //withRequestTimeoutResponse https://github.com/akka/akka-http/issues/952
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

    //no auth
    Post("/", JsonRPCBody(method = "help", params = Seq.empty)) ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == Unauthorized)
      }

    //wrong auth
    Post("/", JsonRPCBody(method = "help", params = Seq.empty)) ~>
      addCredentials(BasicHttpCredentials("", mockService.password+"what!")) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == Unauthorized)
      }

    //correct auth but wrong URL
    Post("/mistake", JsonRPCBody(method = "help", params = Seq.empty)) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == NotFound)
      }

    //wrong rpc method
    Post("/", JsonRPCBody(method = "open_not_really", params = Seq.empty)) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == BadRequest)
      }

    //wrong params
    Post("/", JsonRPCBody(method = "open", params = Seq(JInt(123), JString("abc")))) ~>
      addCredentials(BasicHttpCredentials("", mockService.password)) ~>
      addHeader("Content-Type", "application/json") ~>
      Route.seal(mockService.route) ~>
      check {
        assert(handled)
        assert(status == BadRequest)
      }

  }

  test("Help should respond with a help message") {
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
  
  test("Peers should ask the switchboard for current known peers") {
    
    val mockAlicePeer = system.actorOf(Props(new {} with MockActor {
      override def receive = {
        case GetPeerInfo => sender() ! PeerInfo(
          nodeId = Alice.nodeParams.nodeId,
          state = "CONNECTED",
          address = Some(Alice.nodeParams.publicAddresses.head),
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
          case 'peers => sender() ! Map(Alice.nodeParams.nodeId -> mockAlicePeer, Bob.nodeParams.nodeId -> mockBobPeer)
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

  test("GetInfo response should include this node ID") {
    val mockService = new {} with MockService {
      override def getInfoResponse: Future[GetInfoResponse] = Future.successful(GetInfoResponse(
        nodeId = Alice.nodeParams.nodeId,
        alias = Alice.nodeParams.alias,
        port = 9735,
        chainHash = Alice.nodeParams.chainHash,
        blockHeight = 123456
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


  private def readFileAsString(path: Path): String = Files.exists(path) match {
    case true => new String(Files.readAllBytes(path.toAbsolutePath))
    case false => throw new IllegalArgumentException(s"Mock file for $path does not exist, please use 'overWrite' first.")
  }

  private def matchTestJson(rpcMethod: String, overWrite: Boolean, response: JsonRPCRes)(implicit formats: Formats) = {
    val responseContent = Serialization.writePretty(response)
    val path = Paths.get(s"src/test/resources/api/$rpcMethod")
    if(overWrite){
      Files.write(path, responseContent.getBytes(UTF_8), TRUNCATE_EXISTING, CREATE)
      assert(false, "'overWrite' should be false before commit")
    }else{
      val expectedResponse = readFileAsString(path)
      assert(responseContent == expectedResponse, s"test mock for $rpcMethod did not match the expected response")
    }

  }

}
