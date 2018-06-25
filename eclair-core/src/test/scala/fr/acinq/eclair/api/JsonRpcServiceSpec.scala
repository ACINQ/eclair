package fr.acinq.eclair.api

import akka.actor.{Actor, ActorSystem, Props, Scheduler}
import org.scalatest.FunSuite
import akka.http.scaladsl.model.StatusCodes
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
import scala.concurrent.Future
import scala.concurrent.duration._

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
    override def receive: Receive = {
      case msg =>
    }
  }
  
  class MockService(kit: Kit = defaultMockKit) extends Service {
    override def getInfoResponse: Future[GetInfoResponse] = Future.successful(???)
    override def appKit: Kit = kit

    override val scheduler: Scheduler = system.scheduler

    override def password: String = "mock"

    override val socketHandler: Flow[Message, TextMessage.Strict, NotUsed] = makeSocketHandler(system)(materializer)
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
        assert(status == StatusCodes.OK)
        val helpList = entityAs[JsonRPCRes].result.asInstanceOf[List[String]]
        assert(helpList.mkString == mockService.help.mkString)
    }
    
    
  }
  
  test("Peers should ask the switchboard for current known peers") {
    
    val mockAlicePeer = system.actorOf(Props(new {} with MockActor {
      override def receive = {
        case GetPeerInfo => sender() ! PeerInfo(
          nodeId = Alice.nodeParams.nodeId,
          state = "CONNECTED",
          address = None,
          channels = 1)
      }
    }))
    val mockService = new MockService(defaultMockKit.copy(
      switchboard = system.actorOf(Props(new {} with MockActor {
        override def receive = {
          case 'peers => sender() ! Map(Alice.nodeParams.nodeId -> mockAlicePeer)
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
        assert(status == StatusCodes.OK)
        val peerInfos = entityAs[JsonRPCRes].result.asInstanceOf[Seq[Map[String,String]]]
        assert(peerInfos.size == 1)
        assert(peerInfos.head.get("nodeId") == Some(Alice.nodeParams.nodeId.toString))
        assert(peerInfos.head.get("state") == Some("CONNECTED"))
      }
  }
  
}
