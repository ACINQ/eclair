package fr.acinq.eclair.router

import akka.actor.{Actor, ActorRef, Props}
import akka.testkit.TestProbe
import fr.acinq.bitcoin.{BinaryData, Block, Satoshi, Script, Transaction, TxOut}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain.{ValidateRequest, ValidateResult, WatchSpentBasic}
import fr.acinq.eclair.router.Announcements.makeChannelUpdate
import fr.acinq.eclair.router.BaseRouterSpec.channelAnnouncement
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate}
import fr.acinq.eclair.{Globals, TestkitBaseClass, randomKey}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class RoutingSyncSpec extends TestkitBaseClass {

  val txid = BinaryData("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

  type FixtureParam = Tuple2[ActorRef, ActorRef]

  val shortChannelIds = ChannelRangeQueriesSpec.readShortChannelIds().take(100)

  def makeFakeRoutingInfo(shortChannelId: Long): (ChannelAnnouncement, ChannelUpdate, ChannelUpdate) = {
    val (priv_a, priv_b, priv_funding_a, priv_funding_b) = (randomKey, randomKey, randomKey, randomKey)
    val channelAnn_ab = channelAnnouncement(shortChannelId, priv_a, priv_b, priv_funding_a, priv_funding_b)
    val channelUpdate_ab = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, priv_b.publicKey, shortChannelId, cltvExpiryDelta = 7, 0, feeBaseMsat = 766000, feeProportionalMillionths = 10)
    val channelUpdate_ba = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_b, priv_a.publicKey, shortChannelId, cltvExpiryDelta = 7, 0, feeBaseMsat = 766000, feeProportionalMillionths = 10)
    (channelAnn_ab, channelUpdate_ab, channelUpdate_ba)
  }

  val fakeRoutingInfo = shortChannelIds.map(makeFakeRoutingInfo)
  val routingInfoA = fakeRoutingInfo.dropRight(20)
  val routingInfoB = fakeRoutingInfo.drop(20)

  class FakeWatcher extends Actor {
    def receive = {
      case _: WatchSpentBasic => ()
      case ValidateRequest(ann) =>
        val txOut = TxOut(Satoshi(1000000), Script.pay2wsh(Scripts.multiSig2of2(ann.bitcoinKey1, ann.bitcoinKey2)))
        val (_, _, outputIndex) = fr.acinq.eclair.fromShortId(ann.shortChannelId)
        sender ! ValidateResult(ann, Some(Transaction(version = 0, txIn = Nil, txOut = List.fill(outputIndex + 1)(txOut), lockTime = 0)), true, None)
      case unexpected => println(s"unexpected : $unexpected")
    }
  }

  override def withFixture(test: OneArgTest) = {
    val watcherA = system.actorOf(Props(new FakeWatcher()))
    val paramsA = Alice.nodeParams
    routingInfoA.map {
      case (a, u1, u2) =>
        paramsA.networkDb.addChannel(a, txid, Satoshi(100000))
        paramsA.networkDb.addChannelUpdate(u1)
        paramsA.networkDb.addChannelUpdate(u2)
    }
    val routerA = system.actorOf(Props(new Router(paramsA, watcherA)), "routerA")

    val watcherB = system.actorOf(Props(new FakeWatcher()))
    val paramsB = Bob.nodeParams
    routingInfoB.map {
      case (a, u1, u2) =>
        paramsB.networkDb.addChannel(a, txid, Satoshi(100000))
        paramsB.networkDb.addChannelUpdate(u1)
        paramsB.networkDb.addChannelUpdate(u2)
    }
    val routerB = system.actorOf(Props(new Router(paramsB, watcherB)), "routerB")

    test((routerA, routerB))
  }

  test("initial sync") {
    case (routerA, routerB) => {
      Globals.blockCount.set(shortChannelIds.map(id => fr.acinq.eclair.fromShortId(id)._1).max)

      val sender = TestProbe()
      sender.send(routerA, SendChannelQuery(routerB))
      sender.send(routerB, SendChannelQuery(routerA))

      awaitCond({
        sender.send(routerA, 'channels)
        val channelsA = sender.expectMsgType[Iterable[ChannelAnnouncement]]
        sender.send(routerB, 'channels)
        val channelsB = sender.expectMsgType[Iterable[ChannelAnnouncement]]
        channelsA.toSet == channelsB.toSet
      }, max = 30 seconds)
    }
  }
}
