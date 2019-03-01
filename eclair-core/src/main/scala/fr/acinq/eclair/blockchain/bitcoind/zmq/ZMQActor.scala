/*
 * Copyright 2018 ACINQ SAS
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

package fr.acinq.eclair.blockchain.bitcoind.zmq

import akka.Done
import akka.actor.{Actor, ActorLogging}
import fr.acinq.bitcoin.{Block, Transaction}
import fr.acinq.eclair.blockchain.{NewBlock, NewTransaction}
import org.zeromq.ZMQ.Event
import org.zeromq.{SocketType, ZContext, ZMQ, ZMsg}

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Promise}
import scala.concurrent.duration._
import scala.util.Try

/**
  * Created by PM on 04/04/2017.
  */
class ZMQActor(address: String, connected: Option[Promise[Done]] = None) extends Actor with ActorLogging {

  import ZMQActor._

  val ctx = new ZContext

  val subscriber = ctx.createSocket(SocketType.SUB)
  subscriber.monitor("inproc://events", ZMQ.EVENT_CONNECTED | ZMQ.EVENT_DISCONNECTED)
  subscriber.connect(address)
  subscriber.subscribe("rawblock".getBytes(ZMQ.CHARSET))
  subscriber.subscribe("rawtx".getBytes(ZMQ.CHARSET))

  val monitor = ctx.createSocket(SocketType.PAIR)
  monitor.connect("inproc://events")

  implicit val ec: ExecutionContext = context.system.dispatcher

  // we check messages in a non-blocking manner with an interval, making sure to retrieve all messages before waiting again
  @tailrec
  final def checkEvent: Unit = Option(Event.recv(monitor, ZMQ.DONTWAIT)) match {
    case Some(event) =>
      self ! event
      checkEvent
    case None => ()
  }

  @tailrec
  final def checkMsg: Unit = Option(ZMsg.recvMsg(subscriber, ZMQ.DONTWAIT)) match {
    case Some(msg) =>
      self ! msg
      checkMsg
    case None => ()
  }

  self ! 'checkEvent
  self ! 'checkMsg

  override def receive: Receive = {
    case 'checkEvent =>
      checkEvent
      context.system.scheduler.scheduleOnce(1 second, self ,'checkEvent)

    case 'checkMsg =>
      checkMsg
      context.system.scheduler.scheduleOnce(1 second, self, 'checkMsg)

    case event: Event => event.getEvent match {
      case ZMQ.EVENT_CONNECTED =>
        log.info(s"connected to ${event.getAddress}")
        Try(connected.map(_.success(Done)))
        context.system.eventStream.publish(ZMQConnected)
      case ZMQ.EVENT_DISCONNECTED =>
        log.warning(s"disconnected from ${event.getAddress}")
        context.system.eventStream.publish(ZMQDisconnected)
      case x => log.error(s"unexpected event $x")
    }

    case msg: ZMsg => msg.popString() match {
      case "rawblock" =>
        val block = Block.read(msg.pop().getData)
        log.debug("received blockid={}", block.blockId)
        context.system.eventStream.publish(NewBlock(block))
      case "rawtx" =>
        val tx = Transaction.read(msg.pop().getData)
        log.debug("received txid={}", tx.txid)
        context.system.eventStream.publish(NewTransaction(tx))
      case topic => log.warning(s"unexpected topic=$topic")
    }
  }

}

object ZMQActor {

  // @formatter:off
  sealed trait ZMQEvent
  case object ZMQConnected extends ZMQEvent
  case object ZMQDisconnected extends ZMQEvent
  // @formatter:on

}
