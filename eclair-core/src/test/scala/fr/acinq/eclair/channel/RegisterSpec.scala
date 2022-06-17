package fr.acinq.eclair.channel

import akka.actor.{ActorRef, Props}
import akka.pattern._
import akka.testkit.TestProbe
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair._
import fr.acinq.eclair.channel.Register.GenerateLocalAlias
import org.scalatest.ParallelTestExecution
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.Promise

class RegisterSpec extends TestKitBaseClass with AnyFunSuiteLike with ParallelTestExecution {

  case class CustomChannelRestored(channel: ActorRef, channelId: ByteVector32, peer: ActorRef, remoteNodeId: PublicKey) extends AbstractChannelRestored

  test("register processes custom restored events") {
    val sender = TestProbe()
    val register = system.actorOf(Props(new Register))
    val customRestoredEvent = CustomChannelRestored(TestProbe().ref, randomBytes32(), TestProbe().ref, randomKey().publicKey)
    register ! customRestoredEvent
    sender.send(register, Symbol("channels"))
    sender.expectMsgType[Map[ByteVector32, ActorRef]] == Map(customRestoredEvent.channelId -> customRestoredEvent.channel)
  }

  test("register generates local alias") {
    val sender = TestProbe()
    val register = system.actorOf(Props(new Register))
    val localAlias_p = Promise[Alias]()
    register ! GenerateLocalAlias(localAlias_p)
    import scala.concurrent.ExecutionContext.Implicits.global
    localAlias_p.future.pipeTo(sender.ref)
    sender.expectMsgType[Alias]
  }
}
