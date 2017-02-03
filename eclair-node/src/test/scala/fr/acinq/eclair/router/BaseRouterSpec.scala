package fr.acinq.eclair.router

import akka.actor.ActorRef
import akka.testkit.TestProbe
import fr.acinq.bitcoin.Script.{pay2wsh, write}
import fr.acinq.bitcoin.{BinaryData, Satoshi, Transaction, TxOut}
import fr.acinq.eclair.blockchain.{GetTx, GetTxResponse, WatchSpent}
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{TestkitBaseClass, randomKey}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Base class for router testing.
  * It is re-used in payment FSM tests
  * Created by PM on 29/08/2016.
  */
@RunWith(classOf[JUnitRunner])
abstract class BaseRouterSpec extends TestkitBaseClass {

  type FixtureParam = Tuple2[ActorRef, TestProbe]

  def randomPubkey = randomKey.publicKey

  val (a, b, c, d, e, f) = (randomPubkey, randomPubkey, randomPubkey, randomPubkey, randomPubkey, randomPubkey)
  val (funding_a, funding_b, funding_c, funding_d, funding_e, funding_f) = (randomPubkey, randomPubkey, randomPubkey, randomPubkey, randomPubkey, randomPubkey)

  val DUMMY_SIG = BinaryData("3045022100e0a180fdd0fe38037cc878c03832861b40a29d32bd7b40b10c9e1efc8c1468a002205ae06d1624896d0d29f4b31e32772ea3cb1b4d7ed4e077e5da28dcc33c0e781201")

  val ann_a = NodeAnnouncement(DUMMY_SIG, 0, a, (15, 10, -70), "node-A", "0000", Nil)
  val ann_b = NodeAnnouncement(DUMMY_SIG, 0, b, (50, 99, -80), "node-B", "0000", Nil)
  val ann_c = NodeAnnouncement(DUMMY_SIG, 0, c, (123, 100, -40), "node-C", "0000", Nil)
  val ann_d = NodeAnnouncement(DUMMY_SIG, 0, d, (-120, -20, 60), "node-D", "0000", Nil)
  val ann_e = NodeAnnouncement(DUMMY_SIG, 0, e, (-50, 0, 10), "node-E", "0000", Nil)
  val ann_f = NodeAnnouncement(DUMMY_SIG, 0, f, (30, 10, -50), "node-F", "0000", Nil)

  val channelId_ab = toShortId(420000, 1, 0)
  val channelId_bc = toShortId(420000, 2, 0)
  val channelId_cd = toShortId(420000, 3, 0)
  val channelId_ef = toShortId(420000, 4, 0)

  val chan_ab = ChannelAnnouncement(DUMMY_SIG, DUMMY_SIG, DUMMY_SIG, DUMMY_SIG, channelId_ab, a, b, funding_a, funding_b)
  val chan_bc = ChannelAnnouncement(DUMMY_SIG, DUMMY_SIG, DUMMY_SIG, DUMMY_SIG, channelId_bc, b, c, funding_b, funding_c)
  val chan_cd = ChannelAnnouncement(DUMMY_SIG, DUMMY_SIG, DUMMY_SIG, DUMMY_SIG, channelId_cd, c, d, funding_c, funding_d)
  val chan_ef = ChannelAnnouncement(DUMMY_SIG, DUMMY_SIG, DUMMY_SIG, DUMMY_SIG, channelId_ef, e, f, funding_e, funding_f)

  val defaultChannelUpdate = ChannelUpdate(DUMMY_SIG, 0, 0, "0000", 0, 0, 0, 0)
  val channelUpdate_ab = ChannelUpdate(DUMMY_SIG, channelId_ab, 0, "0000", cltvExpiryDelta = 7, 0, feeBaseMsat = 766000, feeProportionalMillionths = 10)
  val channelUpdate_bc = ChannelUpdate(DUMMY_SIG, channelId_bc, 0, "0000", cltvExpiryDelta = 5, 0, feeBaseMsat = 233000, feeProportionalMillionths = 1)
  val channelUpdate_cd = ChannelUpdate(DUMMY_SIG, channelId_cd, 0, "0000", cltvExpiryDelta = 3, 0, feeBaseMsat = 153000, feeProportionalMillionths = 4)
  val channelUpdate_ef = ChannelUpdate(DUMMY_SIG, channelId_ef, 0, "0000", cltvExpiryDelta = 9, 0, feeBaseMsat = 786000, feeProportionalMillionths = 8)


  override def withFixture(test: OneArgTest) = {
    // the network will be a --(1)--> b ---(2)--> c --(3)--> d and e --(4)--> f (we are a)

    within(30 seconds) {
      // first we set up the router
      val watcher = TestProbe()
      val router = system.actorOf(Router.props(watcher.ref))
      // we announce channels
      router ! chan_ab
      router ! chan_bc
      router ! chan_cd
      router ! chan_ef
      // watcher receives the get tx requests
      watcher.expectMsg(GetTx(420000, 1, 0, chan_ab))
      watcher.expectMsg(GetTx(420000, 2, 0, chan_bc))
      watcher.expectMsg(GetTx(420000, 3, 0, chan_cd))
      watcher.expectMsg(GetTx(420000, 4, 0, chan_ef))
      // and answers with valid scripts
      watcher.send(router, GetTxResponse(Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(1000000), write(pay2wsh(Scripts.multiSig2of2(funding_a, funding_b)))) :: Nil, lockTime = 0), true, chan_ab))
      watcher.send(router, GetTxResponse(Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(1000000), write(pay2wsh(Scripts.multiSig2of2(funding_b, funding_c)))) :: Nil, lockTime = 0), true, chan_bc))
      watcher.send(router, GetTxResponse(Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(1000000), write(pay2wsh(Scripts.multiSig2of2(funding_c, funding_d)))) :: Nil, lockTime = 0), true, chan_cd))
      watcher.send(router, GetTxResponse(Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(1000000), write(pay2wsh(Scripts.multiSig2of2(funding_e, funding_f)))) :: Nil, lockTime = 0), true, chan_ef))
      // watcher receives watch-spent request
      watcher.expectMsgType[WatchSpent]
      watcher.expectMsgType[WatchSpent]
      watcher.expectMsgType[WatchSpent]
      watcher.expectMsgType[WatchSpent]
      // then nodes
      router ! ann_a
      router ! ann_b
      router ! ann_c
      router ! ann_d
      router ! ann_e
      router ! ann_f
      // then channel updates
      router ! channelUpdate_ab
      router ! channelUpdate_bc
      router ! channelUpdate_cd
      router ! channelUpdate_ef

      val sender = TestProbe()

      sender.send(router, 'nodes)
      val nodes = sender.expectMsgType[Iterable[NodeAnnouncement]]
      assert(nodes.size === 6)

      sender.send(router, 'channels)
      val channels = sender.expectMsgType[Iterable[ChannelAnnouncement]]
      assert(channels.size === 4)

      sender.send(router, 'updates)
      val updates = sender.expectMsgType[Iterable[ChannelUpdate]]
      assert(updates.size === 4)

      test((router, watcher))
    }
  }

}
