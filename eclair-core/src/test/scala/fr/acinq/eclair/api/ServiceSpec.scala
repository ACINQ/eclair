package fr.acinq.eclair.api

import akka.actor.{Actor, Props}
import org.scalatest.{FunSuite, FunSuiteLike}
import akka.http.scaladsl.model.{HttpRequest, StatusCodes}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.testkit.TestKit
import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import fr.acinq.eclair.blockchain.TestWallet
import fr.acinq.eclair.{Kit, TestConstants}

import scala.concurrent.Future

class ServiceSpec extends FunSuite with ScalatestRouteTest {
  
  val mockService = new Service {
    
    override def getInfoResponse: Future[GetInfoResponse] = Future.successful(???)
  
    override def appKit: Kit = Kit(
      nodeParams = TestConstants.Alice.nodeParams,
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
  }
  
  class MockActor extends Actor {
    override def receive: Receive = {
      case _ => sender() ! "Yo"
    }
  }
  
  implicit val formats = mockService.formats
  implicit val ser = mockService.serialization
  import Json4sSupport.{marshaller, unmarshaller}
  
  test("Peers should ask the switchboard for its peers") {
    
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
        val response = entityAs[JsonRPCRes]
        assert(response.result.asInstanceOf[List[String]].last.contains("display this message"))
    }
    
    
  }
  
}
