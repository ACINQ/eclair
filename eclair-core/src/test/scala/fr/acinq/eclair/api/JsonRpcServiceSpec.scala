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

class JsonRpcServiceSpec extends FunSuite with ScalatestRouteTest {
  
  def mockService = new Service {
    
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
      case msg =>
        println(s"mock actor got $msg")
        sender() ! "Yo"
    }
  }
  
  implicit val formats = mockService.formats
  implicit val ser = mockService.serialization
  import Json4sSupport.{marshaller, unmarshaller}
  
  test("Calling non root path should result in HTTP 404") {
  
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
  
}
