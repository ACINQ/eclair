package fr.acinq.eclair.router

import akka.actor.ActorRef
import akka.testkit.TestProbe
import fr.acinq.eclair.router.Router.DUMMY_SIG
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

  val ann_a = NodeAnnouncement(DUMMY_SIG, 0, a, (0, 0, 0), "node-A", "0000", Nil)
  val ann_b = NodeAnnouncement(DUMMY_SIG, 0, b, (0, 0, 0), "node-B", "0000", Nil)
  val ann_c = NodeAnnouncement(DUMMY_SIG, 0, c, (0, 0, 0), "node-C", "0000", Nil)
  val ann_d = NodeAnnouncement(DUMMY_SIG, 0, d, (0, 0, 0), "node-D", "0000", Nil)
  val ann_e = NodeAnnouncement(DUMMY_SIG, 0, e, (0, 0, 0), "node-E", "0000", Nil)
  val ann_f = NodeAnnouncement(DUMMY_SIG, 0, f, (0, 0, 0), "node-F", "0000", Nil)

  val chan_ab = ChannelAnnouncement(DUMMY_SIG, DUMMY_SIG, channelId = 1, DUMMY_SIG, DUMMY_SIG, a, b, "", "")
  val chan_bc = ChannelAnnouncement(DUMMY_SIG, DUMMY_SIG, channelId = 2, DUMMY_SIG, DUMMY_SIG, b, c, "", "")
  val chan_cd = ChannelAnnouncement(DUMMY_SIG, DUMMY_SIG, channelId = 3, DUMMY_SIG, DUMMY_SIG, c, d, "", "")
  val chan_ef = ChannelAnnouncement(DUMMY_SIG, DUMMY_SIG, channelId = 4, DUMMY_SIG, DUMMY_SIG, e, f, "", "")

  val defaultChannelUpdate = ChannelUpdate(Router.DUMMY_SIG, 0, 0, "0000", 0, 0, 0, 0)
  val channelUpdate_ab = ChannelUpdate(Router.DUMMY_SIG, channelId = 1, 0, "0000", cltvExpiryDelta = 7, 0, feeBaseMsat = 766000, feeProportionalMillionths = 10)
  val channelUpdate_bc = ChannelUpdate(Router.DUMMY_SIG, channelId = 2, 0, "0000", cltvExpiryDelta = 5, 0, feeBaseMsat = 233000, feeProportionalMillionths = 1)
  val channelUpdate_cd = ChannelUpdate(Router.DUMMY_SIG, channelId = 3, 0, "0000", cltvExpiryDelta = 3, 0, feeBaseMsat = 153000, feeProportionalMillionths = 4)
  val channelUpdate_ef = ChannelUpdate(Router.DUMMY_SIG, channelId = 4, 0, "0000", cltvExpiryDelta = 9, 0, feeBaseMsat = 786000, feeProportionalMillionths = 8)


  override def withFixture(test: OneArgTest) = {
    // the network will be a --(1)--> b ---(2)--> c --(3)--> d and e --(4)--> f (we are a)

    within(30 seconds) {
      // first we set up the router
      val watcher = TestProbe()
      val router = system.actorOf(Router.props(watcher.ref, ann_a))
      // we announce channels
      router ! chan_ab
      router ! chan_bc
      router ! chan_cd
      router ! chan_ef
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
