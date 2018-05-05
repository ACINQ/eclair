package fr.acinq.eclair.payment
import akka.testkit.{TestKit, TestProbe}
import akka.actor.{ActorSystem, Status}
import org.junit.runner.RunWith
import org.scalatest.FunSuiteLike
import org.scalatest.junit.JUnitRunner
import akka.actor.Status.Failure
import akka.testkit.{TestKit, TestProbe}
import fr.acinq.bitcoin.{MilliSatoshi, Satoshi}
import fr.acinq.eclair.Globals
import fr.acinq.eclair.TestConstants.Alice
import fr.acinq.eclair.payment._
import grizzled.slf4j.Logging
import scala.concurrent.duration._
import fr.acinq.eclair.db.ChannelBalances

@RunWith(classOf[JUnitRunner])
class AuditLoggerSpec extends TestKit(ActorSystem("test")) with FunSuiteLike with Logging {
  test("relayed payments should split profit") {
    val nodeParams = Alice.nodeParams
    val handler = system.actorOf(AuditLogger.props(nodeParams, true))
    val sender = TestProbe()
    val db=nodeParams.auditDb
    
    handler ! PaymentRelayed(MilliSatoshi(100001),MilliSatoshi(100000),"00"*32, "01"*32, 1, "02"*32, 2)
    
    // This is to test that profit is rounded correctly and extra 1 mSat goes to the in channel.
    awaitAssert({
       assert(db.channelBalances("01"*32)==ChannelBalances(-1,1,MilliSatoshi(100001),MilliSatoshi(0),
           MilliSatoshi(1),MilliSatoshi(0),MilliSatoshi(0),0,0,0,sentMsat=MilliSatoshi(0),receivedMsat=MilliSatoshi(100001),
           relayReceivedMsat=MilliSatoshi(100001)))
           
        }, max = 2 seconds, interval = 1 second)
    awaitAssert({
       assert(db.channelBalances("02"*32)==ChannelBalances(2,-1,MilliSatoshi(0),MilliSatoshi(-100000),
           sentMsat=MilliSatoshi(-100000),relaySentMsat=MilliSatoshi(-100000)))
           
        }, max = 2 seconds, interval = 1 second)
  }
  
  test("relayed payments should split profit even when very big") {
    val nodeParams = Alice.nodeParams
    val handler = system.actorOf(AuditLogger.props(nodeParams, true))
    val sender = TestProbe()
    val db=nodeParams.auditDb
    
    handler ! PaymentRelayed(MilliSatoshi(100000000L*1000L*1000L+1),MilliSatoshi(100000000L*1000L*1000L),"00"*32, "03"*32, 1, "04"*32, 2)
    
    // This is to test that profit is rounded correctly and extra 1 mSat goes to the in channel.
    awaitAssert({
       assert(db.channelBalances("03"*32)==ChannelBalances(-1,1,MilliSatoshi(100000000L*1000L*1000L+1),MilliSatoshi(0),
           MilliSatoshi(1),sentMsat=MilliSatoshi(0),receivedMsat=MilliSatoshi(100000000L*1000L*1000L+1),
           relayReceivedMsat=MilliSatoshi(100000000000001L)))
           
        }, max = 2 seconds, interval = 1 second)
    awaitAssert({
       assert(db.channelBalances("04"*32)==ChannelBalances(2,-1,MilliSatoshi(0),MilliSatoshi(-100000000L*1000L*1000L),
           sentMsat=MilliSatoshi(-100000000L*1000L*1000L),relaySentMsat=MilliSatoshi(-100000000000000L)))
           
        }, max = 2 seconds, interval = 1 second)
  }
}