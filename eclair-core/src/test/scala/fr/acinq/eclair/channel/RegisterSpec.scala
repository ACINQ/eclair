package fr.acinq.eclair.channel

import akka.actor.ActorRef
import akka.actor.typed.scaladsl.adapter.ClassicActorRefOps
import akka.testkit.TestProbe
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair._
import fr.acinq.eclair.channel.Register._
import fr.acinq.eclair.io.PeerCreated
import org.scalatest.ParallelTestExecution
import org.scalatest.funsuite.AnyFunSuiteLike

class RegisterSpec extends TestKitBaseClass with AnyFunSuiteLike with ParallelTestExecution {

  case class CustomChannelRestored(channel: ActorRef, channelId: ByteVector32, peer: ActorRef, remoteNodeId: PublicKey) extends AbstractChannelRestored

  test("process custom restored events") {
    val sender = TestProbe()
    val register = system.actorOf(Register.props())
    val customRestoredEvent = CustomChannelRestored(TestProbe().ref, randomBytes32(), TestProbe().ref, randomKey().publicKey)
    register ! customRestoredEvent
    sender.send(register, GetChannels)
    assert(sender.expectMsgType[Map[ByteVector32, ActorRef]] == Map(customRestoredEvent.channelId -> customRestoredEvent.channel))
  }

  test("map nodeIds to peers") {
    val sender = TestProbe()
    val register = system.actorOf(Register.props())
    val (nodeId1, peer1a, peer1b) = (randomKey().publicKey, TestProbe(), TestProbe())
    val (nodeId2, peer2) = (randomKey().publicKey, TestProbe())
    register ! PeerCreated(peer1a.ref, nodeId1)
    register ! PeerCreated(peer2.ref, nodeId2)
    sender.send(register, GetNodes)
    assert(sender.expectMsgType[Map[PublicKey, ActorRef]] == Map(nodeId1 -> peer1a.ref, nodeId2 -> peer2.ref))
    // The first peer is stopped and recreated, the corresponding messages arrive out of order.
    register ! PeerCreated(peer1b.ref, nodeId1)
    register ! PeerTerminated(peer1a.ref, nodeId1)
    sender.send(register, GetNodes)
    assert(sender.expectMsgType[Map[PublicKey, ActorRef]] == Map(nodeId1 -> peer1b.ref, nodeId2 -> peer2.ref))
    // The second peer is stopped.
    register ! PeerTerminated(peer2.ref, nodeId2)
    sender.send(register, GetNodes)
    assert(sender.expectMsgType[Map[PublicKey, ActorRef]] == Map(nodeId1 -> peer1b.ref))
  }

  test("forward messages to nodes") {
    val sender = TestProbe()
    val register = system.actorOf(Register.props())
    val (nodeId, peer) = (randomKey().publicKey, TestProbe())
    register ! PeerCreated(peer.ref, nodeId)
    register ! ForwardNodeId(sender.ref.toTyped, nodeId, "hello")
    peer.expectMsg("hello")
    val fwd = ForwardNodeId(sender.ref.toTyped, randomKey().publicKey, "hello")
    register ! fwd
    sender.expectMsg(ForwardNodeIdFailure(fwd))
  }

}
