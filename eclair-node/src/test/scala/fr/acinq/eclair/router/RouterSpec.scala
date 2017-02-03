package fr.acinq.eclair.router

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, File}
import javafx.application.Platform

import akka.actor.Status.Failure
import akka.testkit.TestProbe
import com.google.common.io.Files
import fr.acinq.bitcoin.Script.{pay2wsh, write}
import fr.acinq.bitcoin.{BinaryData, Satoshi, Transaction, TxOut}
import fr.acinq.eclair.blockchain.{GetTx, GetTxResponse, WatchEventSpent, WatchSpent}
import fr.acinq.eclair.channel.BITCOIN_FUNDING_OTHER_CHANNEL_SPENT
import fr.acinq.eclair.toShortId
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire.ChannelAnnouncement
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 29/08/2016.
  */
@RunWith(classOf[JUnitRunner])
class RouterSpec extends BaseRouterSpec {

  test("properly announce new channels") { case (router, watcher) =>
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[NetworkEvent])

    val channelId_ac = toShortId(420000, 5, 0)
    val chan_ac = ChannelAnnouncement(DUMMY_SIG, DUMMY_SIG, DUMMY_SIG, DUMMY_SIG, channelId_ac, a, c, funding_a, funding_c)

    router ! chan_ac
    watcher.expectMsg(GetTx(420000, 5, 0, chan_ac))
    watcher.send(router, GetTxResponse(Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(1000000), write(pay2wsh(Scripts.multiSig2of2(funding_a, funding_c)))) :: Nil, lockTime = 0), true, chan_ac))
    watcher.expectMsgType[WatchSpent]

    eventListener.expectMsg(ChannelDiscovered(chan_ac))
  }

  test("properly announce lost channels and nodes") { case (router, watcher) =>
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[NetworkEvent])

    router ! WatchEventSpent(BITCOIN_FUNDING_OTHER_CHANNEL_SPENT(channelId_ab), Transaction(version = 0, txIn = Nil, txOut = Nil, lockTime = 0))
    eventListener.expectMsg(ChannelLost(channelId_ab))
    // a doesn't have any channels, b still has one with c
    eventListener.expectMsg(NodeLost(a))
    eventListener.expectNoMsg(200 milliseconds)

    router ! WatchEventSpent(BITCOIN_FUNDING_OTHER_CHANNEL_SPENT(channelId_cd), Transaction(version = 0, txIn = Nil, txOut = Nil, lockTime = 0))
    eventListener.expectMsg(ChannelLost(channelId_cd))
    // d doesn't have any channels, c still has one with b
    eventListener.expectMsg(NodeLost(d))
    eventListener.expectNoMsg(200 milliseconds)

    router ! WatchEventSpent(BITCOIN_FUNDING_OTHER_CHANNEL_SPENT(channelId_bc), Transaction(version = 0, txIn = Nil, txOut = Nil, lockTime = 0))
    eventListener.expectMsg(ChannelLost(channelId_bc))
    // now b and c do not have any channels
    eventListener.expectMsgAllOf(NodeLost(b), NodeLost(c))
    eventListener.expectNoMsg(200 milliseconds)

  }

  test("route not found (unreachable target)") { case (router, _) =>
    val sender = TestProbe()
    // no route a->f
    sender.send(router, RouteRequest(a, f))
    val res = sender.expectMsgType[Failure]
    assert(res.cause.getMessage === "route not found")
  }

  test("route not found (non-existing source)") { case (router, _) =>
    val sender = TestProbe()
    // no route a->f
    sender.send(router, RouteRequest(randomPubkey, f))
    val res = sender.expectMsgType[Failure]
    assert(res.cause.getMessage === "graph must contain the source vertex")
  }

  test("route not found (non-existing target)") { case (router, _) =>
    val sender = TestProbe()
    // no route a->f
    sender.send(router, RouteRequest(a, randomPubkey))
    val res = sender.expectMsgType[Failure]
    assert(res.cause.getMessage === "graph must contain the sink vertex")
  }

  test("route found") { case (router, _) =>
    val sender = TestProbe()
    sender.send(router, RouteRequest(a, d))
    val res = sender.expectMsgType[RouteResponse]
    assert(res.hops.map(_.nodeId).toList === a.toBin :: b.toBin :: c.toBin :: Nil)
    assert(res.hops.last.nextNodeId === d.toBin)
  }

  test("export graph in dot format") { case (router, _) =>
    val sender = TestProbe()
    sender.send(router, 'dot)
    val dot = sender.expectMsgType[String]
    /*Files.write(dot.getBytes(), new File("graph.dot"))

    import scala.sys.process._
    val input = new ByteArrayInputStream(dot.getBytes)
    val output = new ByteArrayOutputStream()
    "dot -Tpng" #< input #> output !
    val img = output.toByteArray
    Files.write(img, new File("graph.png"))*/
  }

}
