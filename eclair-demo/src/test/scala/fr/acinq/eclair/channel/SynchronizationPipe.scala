package fr.acinq.eclair.channel

import java.io.{BufferedWriter, File, FileWriter}

import akka.actor.{Actor, ActorLogging, ActorRef, Stash}
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair._
import lightning.locktime
import lightning.locktime.Locktime.Blocks

/**
  * Created by PM on 30/05/2016.
  */


/*
   Handles a bi-directional path between 2 actors
   used to avoid the chicken-and-egg problem of:
    a = new Channel(b)
    b = new Channel(a)
   This pipe executes scripted tests and allows for
   fine grained control on the order of messages
 */
class SynchronizationPipe() extends Actor with ActorLogging with Stash {

  val offer = "(.):offer ([0-9]+),([0-9a-f]+)".r
  val fulfill = "(.):fulfill ([0-9]+),([0-9a-f]+)".r
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
      case offer(x, amount, rhash) :: rest =>
        resolve(x) ! CMD_ADD_HTLC(amount.toInt, BinaryData(rhash), locktime(Blocks(4)), Seq())
        exec(rest, a, b)
      case fulfill(x, id, r) :: rest =>
        resolve(x) ! CMD_FULFILL_HTLC(id.toInt, BinaryData(r))
        exec(rest, a, b)
      case commit(x) :: rest =>
        resolve(x) ! CMD_SIGN
        exec(rest, a, b)
      /*case feechange(x) :: rest =>
        resolve(x) ! CmdFeeChange()
        exec(rest, a, b)*/
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
        resolve(x) ! CMD_GETSTATEDATA
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
    case (a: ActorRef, b: ActorRef) =>
      unstashAll()
      context become passthrough(a, b)
    case msg => stash()
  }

  def passthrough(a: ActorRef, b: ActorRef): Receive = {
    case file: File =>
      import scala.io.Source
      val script = Source.fromFile(file).getLines().filterNot(_.startsWith("#")).toList
      exec(script, a, b)
    case msg if sender() == a => b forward msg
    case msg if sender() == b => a forward msg
    case msg => log.error("" + msg)
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
    case d: DATA_NORMAL if script.head.endsWith(":dump") =>
      def rtrim(s: String) = s.replaceAll("\\s+$", "")
      val l = List(
        "LOCAL COMMITS:",
        s" Commit ${d.ourCommit.index}:",
        s"  Offered htlcs: ${d.ourCommit.spec.htlcs.filter(_.direction == OUT).map(h => (h.id, h.amountMsat)).mkString(" ")}",
        s"  Received htlcs: ${d.ourCommit.spec.htlcs.filter(_.direction == IN).map(h => (h.id, h.amountMsat)).mkString(" ")}",
        s"  Balance us: ${d.ourCommit.spec.amount_us_msat}",
        s"  Balance them: ${d.ourCommit.spec.amount_them_msat}",
        s"  Fee rate: ${d.ourCommit.spec.feeRate}",
        "REMOTE COMMITS:",
        s" Commit ${d.theirCommit.index}:",
        s"  Offered htlcs: ${d.theirCommit.spec.htlcs.filter(_.direction == OUT).map(h => (h.id, h.amountMsat)).mkString(" ")}",
        s"  Received htlcs: ${d.theirCommit.spec.htlcs.filter(_.direction == IN).map(h => (h.id, h.amountMsat)).mkString(" ")}",
        s"  Balance us: ${d.theirCommit.spec.amount_us_msat}",
        s"  Balance them: ${d.theirCommit.spec.amount_them_msat}",
        s"  Fee rate: ${d.theirCommit.spec.feeRate}")
        .foreach(s => {
          fout.write(rtrim(s))
          fout.newLine()
        })
      unstashAll()
      exec(script.drop(1), a, b)
    case other =>
      stash()
  }
}
