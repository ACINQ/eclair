package fr.acinq.eclair.router

import akka.actor.Status.Failure
import akka.testkit.TestProbe
import fr.acinq.bitcoin.Script.{pay2wsh, write}
import fr.acinq.bitcoin.{Satoshi, Transaction, TxOut}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.BITCOIN_FUNDING_OTHER_CHANNEL_SPENT
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire.Error
import fr.acinq.eclair.{randomKey, toShortId}
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
    val chan_ac = channelAnnouncement(channelId_ac, priv_a, priv_c, priv_funding_a, priv_funding_c)

    router ! chan_ac
    watcher.expectMsg(GetTx(420000, 5, 0, chan_ac))
    watcher.send(router, GetTxResponse(Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(1000000), write(pay2wsh(Scripts.multiSig2of2(funding_a, funding_c)))) :: Nil, lockTime = 0), true, chan_ac))
    watcher.expectMsgType[WatchSpentBasic]

    eventListener.expectMsg(ChannelDiscovered(chan_ac, Satoshi(1000000)))
  }

  test("properly announce lost channels and nodes") { case (router, watcher) =>
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[NetworkEvent])

    router ! WatchEventSpentBasic(BITCOIN_FUNDING_OTHER_CHANNEL_SPENT(channelId_ab))
    eventListener.expectMsg(ChannelLost(channelId_ab))
    // a doesn't have any channels, b still has one with c
    eventListener.expectMsg(NodeLost(a))
    eventListener.expectNoMsg(200 milliseconds)

    router ! WatchEventSpentBasic(BITCOIN_FUNDING_OTHER_CHANNEL_SPENT(channelId_cd))
    eventListener.expectMsg(ChannelLost(channelId_cd))
    // d doesn't have any channels, c still has one with b
    eventListener.expectMsg(NodeLost(d))
    eventListener.expectNoMsg(200 milliseconds)

    router ! WatchEventSpentBasic(BITCOIN_FUNDING_OTHER_CHANNEL_SPENT(channelId_bc))
    eventListener.expectMsg(ChannelLost(channelId_bc))
    // now b and c do not have any channels
    eventListener.expectMsgAllOf(NodeLost(b), NodeLost(c))
    eventListener.expectNoMsg(200 milliseconds)

  }

  test("handle bad signature for ChannelAnnouncement") { case (router, _) =>
    val sender = TestProbe()
    val channelId_ac = toShortId(420000, 5, 0)
    val chan_ac = channelAnnouncement(channelId_ac, priv_a, priv_c, priv_funding_a, priv_funding_c)
    val buggy_chan_ac = chan_ac.copy(nodeSignature1 = chan_ac.nodeSignature2)
    sender.send(router, buggy_chan_ac)
    sender.expectMsgType[Error]
  }

  test("handle bad signature for NodeAnnouncement") { case (router, _) =>
    val sender = TestProbe()
    val buggy_ann_a = ann_a.copy(signature = ann_b.signature, timestamp = 1)
    sender.send(router, buggy_ann_a)
    sender.expectMsgType[Error]
  }

  test("handle bad signature for ChannelUpdate") { case (router, _) =>
    val sender = TestProbe()
    val buggy_channelUpdate_ab = channelUpdate_ab.copy(signature = ann_b.signature, timestamp = 1)
    sender.send(router, buggy_channelUpdate_ab)
    sender.expectMsgType[Error]
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
    sender.send(router, RouteRequest(randomKey.publicKey, f))
    val res = sender.expectMsgType[Failure]
    assert(res.cause.getMessage === "graph must contain the source vertex")
  }

  test("route not found (non-existing target)") { case (router, _) =>
    val sender = TestProbe()
    // no route a->f
    sender.send(router, RouteRequest(a, randomKey.publicKey))
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
