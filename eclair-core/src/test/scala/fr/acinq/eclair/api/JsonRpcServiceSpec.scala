package fr.acinq.eclair.api

import akka.actor.Actor.Receive
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import org.scalatest.{FunSuite, FunSuiteLike}
import akka.http.scaladsl.model.{HttpRequest, StatusCodes}
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import de.heikoseeberger.akkahttpjson4s.Json4sSupport.{marshaller, unmarshaller}
import fr.acinq.eclair.blockchain.TestWallet
import fr.acinq.eclair.{Kit, TestConstants}
import TestConstants._
import fr.acinq.eclair.io.Peer.{GetPeerInfo, PeerInfo}

import scala.concurrent.Future
import scala.concurrent.duration._

class JsonRpcServiceSpec extends FunSuite with ScalatestRouteTest {
  
  implicit def default(implicit system: ActorSystem) = RouteTestTimeout(1 second)
  
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
    override def receive: Receive = {
      case msg =>
    }
  }
  
  class MockService(kit: Kit = defaultMockKit) extends Service {
    override def getInfoResponse: Future[GetInfoResponse] = Future.successful(???)
    override def appKit: Kit = kit
  }
  
  
  test("Calling non root path should result in HTTP 404") {
  
    val mockService = new MockService
    import mockService.formats
    import mockService.serialization
    
    val postBody = JsonRPCBody(
      method = "help",
      params = Seq.empty
    )
  
    Post("/some/wrong/path", postBody) ~>
      addHeader("Content-Type", "application/json") ~>
      mockService.route ~>
      check {
        assert(handled)
        assert(status == StatusCodes.NotFound)
      }
  }
  
  test("Help should respond with a help message") {
    val mockService = new MockService
    import mockService.formats
    import mockService.serialization
  
    val postBody = JsonRPCBody(
      method = "help",
      params = Seq.empty
    )

    Post("/", postBody) ~>
      addHeader("Content-Type", "application/json") ~>
      mockService.route ~>
      check {
        assert(handled)
        assert(status == StatusCodes.OK)
        val helpList = entityAs[JsonRPCRes].result.asInstanceOf[List[String]]
        assert(helpList.mkString == mockService.help.mkString)
    }
    
    
  }
  
  test("Peers should ask the switchboard for current known peers") {
    
    val mockAlicePeer = system.actorOf(Props(new {} with MockActor {
      override def receive = {
        case GetPeerInfo => sender() ! PeerInfo(
          nodeId = Alice.id,
          state = "CONNECTED",
          address = None,
          channels = 1)
      }
    }))
    val mockService = new MockService(defaultMockKit.copy(
      switchboard = system.actorOf(Props(new {} with MockActor {
        override def receive = {
          case 'peers => sender() ! Map(Alice.id -> mockAlicePeer)
        }
      }))
    ))
    
    import mockService.formats
    import mockService.serialization
    
    val postBody = JsonRPCBody(
      method = "peers",
      params = Seq.empty
    )
    
    Post("/", postBody) ~>
      addHeader("Content-Type", "application/json") ~>
      mockService.route ~>
      check {
        assert(handled)
        assert(status == StatusCodes.OK)
        val peerInfos = entityAs[JsonRPCRes].result.asInstanceOf[Seq[Map[String,String]]]
        assert(peerInfos.size == 1)
        assert(peerInfos.head.get("nodeId") == Some(Alice.id.toString))
        assert(peerInfos.head.get("state") == Some("CONNECTED"))
      }
  }
  
}
