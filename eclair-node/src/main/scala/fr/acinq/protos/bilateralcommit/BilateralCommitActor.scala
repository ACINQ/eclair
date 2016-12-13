package fr.acinq.protos.bilateralcommit

import java.util.concurrent.atomic.AtomicLong

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, Stash}

import scala.concurrent.duration._
import scala.util.Random

/**
  * Created by PM on 18/08/2016.
  */

case class CMD_SendChange(change: String)

case class CMD_SendSig()

case class PKT_ReceiveChange(change: String)

case class PKT_ReceiveSig(sig: Set[Change])

case class PKT_ReceiveRev()

class BilateralCommitActor(counterparty: ActorRef) extends Actor with ActorLogging {

  override def receive: Receive = main(new Commitments(), Set.empty)

  val random = new Random()

  def main(commitments: Commitments, fulfills: Set[String]): Receive = {
    case CMD_SendChange(change) =>
      log.info(s"send $change")
      val commitments1 = commitments.sendChange(change)
      counterparty ! PKT_ReceiveChange(change)
      context.become(main(commitments1, fulfills))
    case PKT_ReceiveChange(change) =>
      log.info(s"recv $change")
      val commitments1 = commitments.receiveChange(change)
      val f = if (change.startsWith("A")) change :: Nil else Nil
      context.become(main(commitments1, fulfills ++ f))
    case CMD_SendSig() if commitments.waitingForRev =>
      log.info("ignoring sendsig")
    case CMD_SendSig() =>
      val commitments1 = commitments.sendSig()
      val sig = commitments1.their_commit
      log.info(s"send sig=$sig")
      counterparty ! PKT_ReceiveSig(sig)
      context.become(main(commitments1, fulfills))
    case PKT_ReceiveSig(sig) =>
      log.info(s"recv sig=$sig")
      val commitments1 = commitments.receiveSig()
      val x = sig
      val y = commitments1.our_commit
      assert(x == y, s"$x != $y")
      counterparty ! PKT_ReceiveRev()
      val done = sig.map(_.c).filter(a => fulfills.contains(a))
      // ~ FULFILL/FAIL
      done.foreach(a => self ! CMD_SendChange(a.replace("A", "F")))
      context.become(main(commitments1, fulfills -- done))
    case PKT_ReceiveRev() =>
      log.info(s"recv rev")
      val commitments1 = commitments.receiveRev()
      context.become(main(commitments1, fulfills))
  }
}

class Pipe extends Actor with Stash {

  def receive = {
    case (a: ActorRef, b: ActorRef) =>
      unstashAll()
      context become ready(a, b)

    case msg => stash()
  }

  val random = new Random()

  def ready(a: ActorRef, b: ActorRef): Receive = {
    case msg if sender() == a => b forward msg
    case msg if sender() == b => a forward msg
  }
}

object BilateralCommitActorTest extends App {

  val system = ActorSystem()
  val pipe = system.actorOf(Props[Pipe], "pipe")
  val a = system.actorOf(Props(classOf[BilateralCommitActor], pipe), "a")
  val b = system.actorOf(Props(classOf[BilateralCommitActor], pipe), "b")

  pipe ! (a, b)

  var i = new AtomicLong(0)
  val random = new Random()

  def msg = random.nextInt(100) % 5 match {
    case 0 | 1 | 2 | 3 => CMD_SendChange(s"A${i.incrementAndGet()}")
    case 4 => CMD_SendSig()
  }

  import scala.concurrent.ExecutionContext.Implicits.global

  system.scheduler.schedule(0 seconds, 5 milliseconds, new Runnable() {
    override def run(): Unit = a ! msg
  })
  system.scheduler.schedule(0 seconds, 7 milliseconds, new Runnable() {
    override def run(): Unit = b ! msg
  })


}