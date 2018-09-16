package fr.acinq.eclair.router

import akka.actor.{Actor, ActorRef, Props}
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{BinaryData, Block, Satoshi, Script, Transaction, TxOut}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.{ValidateRequest, ValidateResult, WatchSpentBasic}
import fr.acinq.eclair.crypto.TransportHandler.ReadAck
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.router.Announcements.{makeChannelUpdate, makeNodeAnnouncement}
import fr.acinq.eclair.router.BaseRouterSpec.channelAnnouncement
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class RoutingSyncSpec extends TestkitBaseClass {

  import RoutingSyncSpec._

  val txid = BinaryData("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
  val idA = PrivateKey(BinaryData("01"  *32), true).publicKey
  val idB = PrivateKey(BinaryData("02"  *32), true).publicKey

  type FixtureParam = Tuple3[ActorRef, ActorRef, ActorRef]

  val shortChannelIds = ChannelRangeQueriesSpec.shortChannelIds.take(500)

  val fakeRoutingInfo = shortChannelIds.map(makeFakeRoutingInfo)
  // A will be missing the last 100 items
  val routingInfoA = fakeRoutingInfo.dropRight(100)
  // and B will be missing the first 100 items
  val routingInfoB = fakeRoutingInfo.drop(200)

  class FakeWatcher extends Actor {
    def receive = {
      case _: WatchSpentBasic => ()
      case ValidateRequest(ann) =>
        val txOut = TxOut(Satoshi(1000000), Script.pay2wsh(Scripts.multiSig2of2(ann.bitcoinKey1, ann.bitcoinKey2)))
        val TxCoordinates(_, _, outputIndex) = ShortChannelId.coordinates(ann.shortChannelId)
        sender ! ValidateResult(ann, Some(Transaction(version = 0, txIn = Nil, txOut = List.fill(outputIndex + 1)(txOut), lockTime = 0)), true, None)
      case unexpected => println(s"unexpected : $unexpected")
    }
  }

  override def withFixture(test: OneArgTest) = {
    val watcherA = system.actorOf(Props(new FakeWatcher()))
    val paramsA = Alice.nodeParams
    routingInfoA.map {
      case (a, u1, u2, n1, n2) =>
        paramsA.networkDb.addChannel(a, txid, Satoshi(100000))
        paramsA.networkDb.addChannelUpdate(u1)
        paramsA.networkDb.addChannelUpdate(u2)
        paramsA.networkDb.addNode(n1)
        paramsA.networkDb.addNode(n2)
    }
    val routerA = system.actorOf(Props(new Router(paramsA, watcherA)), "routerA")

    val watcherB = system.actorOf(Props(new FakeWatcher()))
    val paramsB = Bob.nodeParams
    routingInfoB.map {
      case (a, u1, u2, n1, n2) =>
        paramsB.networkDb.addChannel(a, txid, Satoshi(100000))
        paramsB.networkDb.addChannelUpdate(u1)
        paramsB.networkDb.addChannelUpdate(u2)
        paramsB.networkDb.addNode(n1)
        paramsB.networkDb.addNode(n2)
    }
    val routerB = system.actorOf(Props(new Router(paramsB, watcherB)), "routerB")

    val pipe = system.actorOf(Props(new RoutingSyncSpec.Pipe(routerA, idA, routerB, idB)))
    val sender = TestProbe()
    awaitCond({
      sender.send(routerA, 'channels)
      val channelsA = sender.expectMsgType[Iterable[ChannelAnnouncement]]
      channelsA.size == routingInfoA.size
    }, max = 30 seconds)

    test((routerA, routerB, pipe))
  }

  test("initial sync") {
    case (routerA, routerB, pipe) => {
      Globals.blockCount.set(shortChannelIds.map(id => ShortChannelId.coordinates(id).blockHeight).max)

      val sender = TestProbe()
      routerA ! SendChannelQuery(idB, pipe)
      routerB ! SendChannelQuery(idA, pipe)

      awaitCond({
        sender.send(routerA, 'channels)
        val channelsA = sender.expectMsgType[Iterable[ChannelAnnouncement]]
        sender.send(routerB, 'channels)
        val channelsB = sender.expectMsgType[Iterable[ChannelAnnouncement]]
        channelsA.toSet == channelsB.toSet
        channelsA.size == fakeRoutingInfo.size
      }, max = 30 seconds)
    }
  }

  test("handle split range replies") {
    case (routerA, _, _) => {
      Globals.blockCount.set(shortChannelIds.map(id => ShortChannelId.coordinates(id).blockHeight).max)

      val sender = TestProbe()
      sender.ignoreMsg {
        case ReadAck(_) => true
      }
      val routerB =  TestFSMRef(new Router(Bob.nodeParams, TestProbe().ref), "routerBB")
      routerB ! SendChannelQuery(Alice.nodeParams.nodeId, sender.ref)
      val query = sender.expectMsgType[QueryChannelRange]
      sender.expectMsgType[GossipTimestampFilter]

      sender.send(routerA, PeerRoutingMessage(idB, query))
      val reply1 = sender.expectMsgType[ReplyChannelRange]
      val reply2 = sender.expectMsgType[ReplyChannelRange]


      // tell routerB it's missing 150 channels
      sender.send(routerB, PeerRoutingMessage(idA, reply1))
      // now routerB thinks it's missing 150 channels
      awaitCond(routerB.stateData.sync(idA).count == 150, 3 seconds)

      // tell routerB it's missing another 150 channels
      sender.send(routerB, PeerRoutingMessage(idA, reply2))
      awaitCond(routerB.stateData.sync(idA).count == 300, 3 seconds)
    }
  }

}

object RoutingSyncSpec {
  def makeFakeRoutingInfo(shortChannelId: ShortChannelId): (ChannelAnnouncement, ChannelUpdate, ChannelUpdate, NodeAnnouncement, NodeAnnouncement) = {
    val (priv_a, priv_b, priv_funding_a, priv_funding_b) = (randomKey, randomKey, randomKey, randomKey)
    val channelAnn_ab = channelAnnouncement(shortChannelId, priv_a, priv_b, priv_funding_a, priv_funding_b)
    val TxCoordinates(blockHeight, _, _) = ShortChannelId.coordinates(shortChannelId)
    val channelUpdate_ab = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, priv_b.publicKey, shortChannelId, cltvExpiryDelta = 7, 0, feeBaseMsat = 766000, feeProportionalMillionths = 10, timestamp = blockHeight)
    val channelUpdate_ba = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_b, priv_a.publicKey, shortChannelId, cltvExpiryDelta = 7, 0, feeBaseMsat = 766000, feeProportionalMillionths = 10, timestamp = blockHeight)
    val nodeAnnouncement_a = makeNodeAnnouncement(priv_a, "a", Alice.nodeParams.color, List())
    val nodeAnnouncement_b = makeNodeAnnouncement(priv_b, "b", Bob.nodeParams.color, List())
    (channelAnn_ab, channelUpdate_ab, channelUpdate_ba, nodeAnnouncement_a, nodeAnnouncement_b)
  }

  class Pipe(a: ActorRef, idA: PublicKey, b: ActorRef, idB: PublicKey) extends Actor {
    def receive = {
      case msg: RoutingMessage if sender == a => b ! PeerRoutingMessage(idA, msg)
      case msg: RoutingMessage if sender == b => a ! PeerRoutingMessage(idB, msg)
    }
  }
}
