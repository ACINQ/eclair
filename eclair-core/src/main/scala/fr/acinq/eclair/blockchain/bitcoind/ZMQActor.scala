package fr.acinq.eclair.blockchain.bitcoind.zmq

import akka.actor.{Actor, ActorLogging}
import fr.acinq.bitcoin.{Block, Transaction}
import fr.acinq.eclair.blockchain.{NewBlock, NewTransaction}
import org.zeromq.ZMQ.Event
import org.zeromq.{ZContext, ZMQ, ZMsg}

import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.Try

/**
  * Created by PM on 04/04/2017.
  */
class ZMQActor(address: String, connected: Option[Promise[Boolean]] = None) extends Actor with ActorLogging {

  import ZMQActor._

  val ctx = new ZContext

  val subscriber = ctx.createSocket(ZMQ.SUB)
  subscriber.monitor("inproc://events", ZMQ.EVENT_CONNECTED | ZMQ.EVENT_DISCONNECTED)
  subscriber.connect(address)
  subscriber.subscribe("rawblock".getBytes(ZMQ.CHARSET))
  subscriber.subscribe("rawtx".getBytes(ZMQ.CHARSET))

  val monitor = ctx.createSocket(ZMQ.PAIR)
  monitor.connect("inproc://events")

  import scala.concurrent.ExecutionContext.Implicits.global

  // we check messages in a non-blocking manner with an interval, making sure to retrieve all messages before waiting again
  def checkEvent: Unit = Option(Event.recv(monitor, ZMQ.DONTWAIT)) match {
    case Some(event) =>
      self ! event
      checkEvent
    case None =>
      context.system.scheduler.scheduleOnce(1 second)(checkEvent)
  }

  def checkMsg: Unit = Option(ZMsg.recvMsg(subscriber, ZMQ.DONTWAIT)) match {
    case Some(msg) =>
      self ! msg
      checkMsg
    case None =>
      context.system.scheduler.scheduleOnce(1 second)(checkMsg)
  }

  checkEvent
  checkMsg

  override def receive: Receive = {

    case event: Event => event.getEvent match {
      case ZMQ.EVENT_CONNECTED =>
        log.info(s"connected to ${event.getAddress}")
        Try(connected.map(_.success(true)))
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
