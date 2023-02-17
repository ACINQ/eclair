/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.interop.rustytests

import java.io.{BufferedWriter, File, FileWriter}
import java.util.UUID
import java.util.concurrent.CountDownLatch

import akka.actor.{Actor, ActorLogging, ActorRef, Stash}
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.channel._
import fr.acinq.eclair.transactions.{IncomingHtlc, OutgoingHtlc}
import fr.acinq.eclair.{CltvExpiry, MilliSatoshi, TestConstants, TestUtils}

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
class SynchronizationPipe(latch: CountDownLatch) extends Actor with ActorLogging with Stash {

  val offer = "(.):offer ([0-9]+),([0-9a-f]+)".r
  val fulfill = "(.):fulfill ([0-9]+),([0-9a-f]+)".r
  val commit = "(.):commit".r
  val feechange = "(.):feechange".r
  val recv = "(.):recv.*".r
  val nocommitwait = "(.):nocommitwait.*".r
  val echo = "echo (.*)".r
  val dump = "(.):dump".r

  val fout = new BufferedWriter(new FileWriter(new File(TestUtils.BUILD_DIRECTORY, "result.tmp")))

  def exec(script: List[String], a: ActorRef, b: ActorRef): Unit = {
    def resolve(x: String) = if (x == "A") a else b

    (script: @unchecked) match {
      case offer(x, amount, rhash) :: rest =>
        resolve(x) ! CMD_ADD_HTLC(self, MilliSatoshi(amount.toInt), ByteVector32.fromValidHex(rhash), CltvExpiry(144), TestConstants.emptyOnionPacket, None, Origin.LocalHot(self, UUID.randomUUID()))
        exec(rest, a, b)
      case fulfill(x, id, r) :: rest =>
        resolve(x) ! CMD_FULFILL_HTLC(id.toInt, ByteVector32.fromValidHex(r))
        exec(rest, a, b)
      case commit(x) :: rest =>
        resolve(x) ! CMD_SIGN()
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
        resolve(x) ! CMD_GET_CHANNEL_DATA(ActorRef.noSender)
        context.become(wait(a, b, script))
      case "" :: rest =>
        exec(rest, a, b)
      case List() | Nil =>
        log.info(s"done")
        fout.close()
        latch.countDown()
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
    case _: RES_SUCCESS[_] => {}
    case msg if sender() == a =>
      log.info(s"a -> b $msg")
      b forward msg
    case msg if sender() == b =>
      log.info(s"b -> a $msg")
      a forward msg
    case msg => log.error("" + msg)
  }

  def wait(a: ActorRef, b: ActorRef, script: List[String]): Receive = {
    case _: RES_SUCCESS[_] => {}
    case msg if sender() == a && script.head.startsWith("B:recv") =>
      log.info(s"a -> b $msg")
      b forward msg
      unstashAll()
      exec(script.drop(1), a, b)
    case msg if sender() == b && script.head.startsWith("A:recv") =>
      log.info(s"b -> a $msg")
      a forward msg
      unstashAll()
      exec(script.drop(1), a, b)
    case RES_GET_CHANNEL_DATA(d: DATA_NORMAL) if script.head.endsWith(":dump") =>
      def rtrim(s: String) = s.replaceAll("\\s+$", "")
      import d.commitments.latest._
      val l = List(
        "LOCAL COMMITS:",
        s" Commit ${localCommit.index}:",
        s"  Offered htlcs: ${localCommit.spec.htlcs.collect { case OutgoingHtlc(add) => (add.id, add.amountMsat) }.mkString(" ")}",
        s"  Received htlcs: ${localCommit.spec.htlcs.collect { case IncomingHtlc(add) => (add.id, add.amountMsat) }.mkString(" ")}",
        s"  Balance us: ${localCommit.spec.toLocal}",
        s"  Balance them: ${localCommit.spec.toRemote}",
        s"  Fee rate: ${localCommit.spec.commitTxFeerate.toLong}",
        "REMOTE COMMITS:",
        s" Commit ${remoteCommit.index}:",
        s"  Offered htlcs: ${remoteCommit.spec.htlcs.collect { case OutgoingHtlc(add) => (add.id, add.amountMsat) }.mkString(" ")}",
        s"  Received htlcs: ${remoteCommit.spec.htlcs.collect { case IncomingHtlc(add) => (add.id, add.amountMsat) }.mkString(" ")}",
        s"  Balance us: ${remoteCommit.spec.toLocal}",
        s"  Balance them: ${remoteCommit.spec.toRemote}",
        s"  Fee rate: ${remoteCommit.spec.commitTxFeerate.toLong}")
        .foreach(s => {
          fout.write(rtrim(s))
          fout.newLine()
        })
      unstashAll()
      exec(script.drop(1), a, b)
    case other =>
      stash()
  }


  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    reason.printStackTrace()
    super.preRestart(reason, message)
  }

}
