package fr.acinq.eclair.router

import akka.actor.Status.Failure
import akka.testkit.TestProbe
import fr.acinq.bitcoin.Script.{pay2wsh, write}
import fr.acinq.bitcoin.{Satoshi, Transaction, TxOut}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.BITCOIN_FUNDING_OTHER_CHANNEL_SPENT
import fr.acinq.eclair.router.Announcements.makeChannelUpdate
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

  test("properly announce valid new channels and ignore invalid ones") { case (router, watcher) =>
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[NetworkEvent])

    val channelId_ac = toShortId(420000, 5, 0)
    val chan_ac = channelAnnouncement(channelId_ac, priv_a, priv_c, priv_funding_a, priv_funding_c)
    // a-x will not be found
    val chan_ax = channelAnnouncement(42001, priv_a, randomKey, priv_funding_a, randomKey)
    // a-y will have an invalid script
    val priv_y = randomKey
    val priv_funding_y = randomKey
    val chan_ay = channelAnnouncement(42002, priv_a, priv_y, priv_funding_a, priv_funding_y)
    // a-z will be spent
    val priv_z = randomKey
    val priv_funding_z = randomKey
    val chan_az = channelAnnouncement(42003, priv_a, priv_z, priv_funding_a, priv_funding_z)

    router ! chan_ac
    router ! chan_ax
    router ! chan_ay
    router ! chan_az
    router ! 'tick_validate // we manually trigger a validation
    watcher.expectMsg(ParallelGetRequest(chan_ac :: chan_ax :: chan_ay :: chan_az :: Nil))
    watcher.send(router, ParallelGetResponse(
      IndividualResult(chan_ac, Some(Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(1000000), write(pay2wsh(Scripts.multiSig2of2(funding_a, funding_c)))) :: Nil, lockTime = 0)), true) ::
        IndividualResult(chan_ax, None, false) ::
        IndividualResult(chan_ay, Some(Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(1000000), write(pay2wsh(Scripts.multiSig2of2(funding_a, randomKey.publicKey)))) :: Nil, lockTime = 0)), true) ::
        IndividualResult(chan_az, Some(Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(1000000), write(pay2wsh(Scripts.multiSig2of2(funding_a, priv_funding_z.publicKey)))) :: Nil, lockTime = 0)), false) :: Nil))
    watcher.expectMsgType[WatchSpentBasic]
    watcher.expectNoMsg(1 second)

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
    sender.expectMsg(Failure(RouteNotFound))
  }

  test("route not found (non-existing source)") { case (router, _) =>
    val sender = TestProbe()
    // no route a->f
    sender.send(router, RouteRequest(randomKey.publicKey, f))
    sender.expectMsg(Failure(RouteNotFound))
  }

  test("route not found (non-existing target)") { case (router, _) =>
    val sender = TestProbe()
    // no route a->f
    sender.send(router, RouteRequest(a, randomKey.publicKey))
    sender.expectMsg(Failure(RouteNotFound))
  }

  test("route found") { case (router, _) =>
    val sender = TestProbe()
    sender.send(router, RouteRequest(a, d))
    val res = sender.expectMsgType[RouteResponse]
    assert(res.hops.map(_.nodeId).toList === a :: b :: c :: Nil)
    assert(res.hops.last.nextNodeId === d)
  }

  test("route not found (channel disabled)") { case (router, _) =>
    val sender = TestProbe()
    sender.send(router, RouteRequest(a, d))
    val res = sender.expectMsgType[RouteResponse]
    assert(res.hops.map(_.nodeId).toList === a :: b :: c :: Nil)
    assert(res.hops.last.nextNodeId === d)

    val channelUpdate_cd1 = makeChannelUpdate(priv_c, d, channelId_cd, cltvExpiryDelta = 3, 0, feeBaseMsat = 153000, feeProportionalMillionths = 4, enable = false)
    sender.send(router, channelUpdate_cd1)
    sender.send(router, RouteRequest(a, d))
    sender.expectMsg(Failure(RouteNotFound))
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
