package fr.acinq.protos

import java.io.{BufferedWriter, File, FileWriter}

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, LoggingFSM, Props, Stash}

// @formatter:off

sealed trait StateMock
sealed trait DataMock
case object NORMAL extends StateMock
case class MyChanges(proposed: List[Int], signed: List[List[Int]], acked_by_them: List[Int])
case class TheirChanges(unacked_by_me: List[Int], acked_by_me: List[Int])
case class Commit(index: Int, selected: List[Int], fee_level: Int)
case class MyState(
                    commit_signed_by_me: Commit,
                    commit_signed_by_them: Commit,
                    my_changes: MyChanges,
                    their_changes: TheirChanges) extends DataMock

case class CmdOffer(htlc: Int)
case class CmdRemove(htlc: Int)
case class CmdCommit()
case class CmdFeeChange()
case class Offer(htlc: Int)
case class Remove(htlc: Int)
case class Sig(htlc: List[Int])
case class Revoke()
case class FeeChange()
case class Dump()

// @formatter:on

/**
  * Created by PM on 17/05/2016.
  */
class ChannelMock(them: ActorRef) extends LoggingFSM[StateMock, DataMock] {

  startWith(NORMAL, MyState(Commit(0, Nil, 0), Commit(0, Nil, 0), MyChanges(Nil, Nil, Nil), TheirChanges(Nil, Nil)))

  def removeFulfilled(selected: List[Int]): List[Int] = selected.filterNot(x => selected.contains(-x)).filterNot(_ == 0).sorted

  when(NORMAL) {
    case Event(CmdOffer(htlc), s: MyState) =>
      assert(htlc != 0, s"htlc 0 is forbidden (reserved for fee change")
      them ! Offer(htlc)
      stay using s.copy(my_changes = s.my_changes.copy(proposed = s.my_changes.proposed :+ htlc))

    case Event(Offer(htlc), s: MyState) =>
      stay using s.copy(their_changes = s.their_changes.copy(unacked_by_me = s.their_changes.unacked_by_me :+ htlc))

    case Event(CmdCommit(), MyState(my_commit, their_commit, my_changes, their_changes)) =>
      val selected = removeFulfilled(my_changes.proposed ++ my_changes.signed.flatten ++ my_changes.acked_by_them ++ their_changes.acked_by_me)
      val fee_level = their_changes.acked_by_me.count(_ == 0)
      them ! Sig(selected)
      stay using MyState(Commit(my_commit.index + 1, selected, fee_level), their_commit, MyChanges(proposed = Nil, signed = my_changes.signed :+ my_changes.proposed, acked_by_them = my_changes.acked_by_them), their_changes)

    case Event(Sig(htlcs), MyState(my_commit, their_commit, my_changes, their_changes)) =>
      val selected = removeFulfilled(my_changes.acked_by_them ++ their_changes.acked_by_me ++ their_changes.unacked_by_me)
      val fee_level = my_changes.acked_by_them.count(_ == 0)
      assert(htlcs == selected, s"sig mismatch: $htlcs != $selected")
      them ! Revoke()
      stay using MyState(my_commit, Commit(their_commit.index + 1, htlcs, fee_level), my_changes, TheirChanges(unacked_by_me = Nil, acked_by_me = their_changes.acked_by_me ++ their_changes.unacked_by_me))

    case Event(Revoke(), MyState(my_commit, their_commit, my_changes, their_changes)) =>
      stay using MyState(my_commit, their_commit, MyChanges(proposed = my_changes.proposed, signed = my_changes.signed.drop(1), acked_by_them = my_changes.signed.head ++ my_changes.acked_by_them), their_changes)

    case Event(CmdRemove(htlc), s: MyState) =>
      assert(s.their_changes.acked_by_me.contains(htlc), "cannot remove an htlc that I didn't acknowledge")
      them ! Remove(htlc)
      stay using s.copy(my_changes = s.my_changes.copy(proposed = s.my_changes.proposed :+ -htlc))

    case Event(Remove(htlc), s: MyState) =>
      assert(s.my_changes.acked_by_them.contains(htlc), "cannot remove an htlc that they didn't acknowledge")
      stay using s.copy(their_changes = s.their_changes.copy(unacked_by_me = s.their_changes.unacked_by_me :+ -htlc))

    case Event(CmdFeeChange(), s: MyState) =>
      them ! FeeChange()
      stay using s.copy(my_changes = s.my_changes.copy(proposed = s.my_changes.proposed :+ 0))

    case Event(FeeChange(), s: MyState) =>
      stay using s.copy(their_changes = s.their_changes.copy(unacked_by_me = s.their_changes.unacked_by_me :+ 0))

    case Event(Dump(), s@MyState(my_commit, their_commit, _, _)) =>
      sender ! s
      stay
  }

}

object NewChannel extends App {

  implicit val system = ActorSystem()

  val pipe = system.actorOf(Props(new TestPipe()))
  val a = system.actorOf(Props(new ChannelMock(pipe)), name = "a")
  val b = system.actorOf(Props(new ChannelMock(pipe)), name = "b")
  pipe !(a, b, new File("eclair-demo/rusty-scripts/15-fee-twice-back-to-back.script"))

}

// handle a bi-directional path between 2 actors
// used to avoid the chicken-and-egg problem of:
// a = new Channel(b)
// b = new Channel(a)
class TestPipe() extends Actor with ActorLogging with Stash {

  val offer = "(.):offer ([0-9]+)".r
  val remove = "(.):remove ([0-9]+)".r
  val commit = "(.):commit".r
  val feechange = "(.):feechange".r
  val recv = "(.):recv.*".r
  val nocommitwait = "(.):nocommitwait.*".r
  val echo = "echo (.*)".r
  val dump = "(.):dump".r

  val fout = new BufferedWriter(new FileWriter("result.txt"))

  def exec(script: List[String], a: ActorRef, b: ActorRef): Unit = {
    def resolve(x: String) = if (x == "A") a else b
    script match {
      case offer(x, i) :: rest =>
        resolve(x) ! CmdOffer(i.toInt)
        exec(rest, a, b)
      case remove(x, i) :: rest =>
        resolve(x) ! CmdRemove(i.toInt)
        exec(rest, a, b)
      case commit(x) :: rest =>
        resolve(x) ! CmdCommit()
        exec(rest, a, b)
      case feechange(x) :: rest =>
        resolve(x) ! CmdFeeChange()
        exec(rest, a, b)
      case recv(x) :: rest =>
        context.become(wait(a, b, script))
      case nocommitwait(x) :: rest =>
        log.warning("ignoring nocommitwait")
        exec(rest, a, b)
      case "checksync" :: rest =>
        log.warning("ignoring checksync")
        exec(rest, a, b)
      case echo(s) :: rest =>
        fout.write(s)
        fout.newLine()
        exec(rest, a, b)
      case dump(x) :: rest =>
        resolve(x) ! Dump()
        context.become(wait(a, b, script))
      case "" :: rest =>
        exec(rest, a, b)
      case List() | Nil =>
        log.info(s"done")
        fout.close()
        context stop self
    }
  }

  def receive = {
    case (a: ActorRef, b: ActorRef, file: File) =>
      unstashAll()
      import scala.io.Source
      val script = Source.fromFile(file).getLines().filterNot(_.startsWith("#")).toList
      exec(script, a, b)

    case msg => stash()
  }

  def wait(a: ActorRef, b: ActorRef, script: List[String]): Receive = {
    case msg if sender() == a && script.head.startsWith("B:recv") =>
      b forward msg
      unstashAll()
      exec(script.drop(1), a, b)
    case msg if sender() == b && script.head.startsWith("A:recv") =>
      a forward msg
      unstashAll()
      exec(script.drop(1), a, b)
    case MyState(my_commit, their_commit, _, _) if script.head.endsWith(":dump") =>
      val even = if (sender() == a) 1 else 0
      val l = List(
        "LOCAL COMMITS:",
        s" Commit ${their_commit.index}:",
        s"  Offered htlcs: ${their_commit.selected.filter(_ % 2 == even).mkString(" ")}",
        s"  Received htlcs: ${their_commit.selected.filter(_ % 2 != even).mkString(" ")}",
        s"  Fee level ${their_commit.fee_level}",
        s"  SIGNED", // TODO ???
        "REMOTE COMMITS:",
        s" Commit ${my_commit.index}:",
        s"  Offered htlcs: ${my_commit.selected.filter(_ % 2 != even).mkString(" ")}",
        s"  Received htlcs: ${my_commit.selected.filter(_ % 2 == even).mkString(" ")}",
        s"  Fee level ${my_commit.fee_level}",
        s"  SIGNED").filterNot(_ == "  Fee level 0") // TODO ???
    def rtrim(s: String) = s.replaceAll("\\s+$", "")
      l.foreach(s => {
        fout.write(rtrim(s))
        fout.newLine()
      })
      unstashAll()
      exec(script.drop(1), a, b)
    case other =>
      stash()
  }
}