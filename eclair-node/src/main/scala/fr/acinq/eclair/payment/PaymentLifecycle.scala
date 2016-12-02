package fr.acinq.eclair.payment

import akka.actor.Status.Failure
import akka.actor.{ActorRef, FSM, LoggingFSM, Props, Status}
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.peer.CurrentBlockCount
import fr.acinq.eclair.channel.{CMD_ADD_HTLC, PaymentFailed, PaymentSent}
import fr.acinq.eclair.router._
import lightning.locktime.Locktime.Blocks
import lightning.route_step.Next
import lightning.{locktime, route_step, sha256_hash}

// @formatter:off

case class CreatePayment(amountMsat: Int, h: sha256_hash, targetNodeId: BinaryData)

sealed trait Data
case class WaitingForRequest(currentBlockCount: Long) extends Data
case class WaitingForRoute(sender: ActorRef, c: CreatePayment, currentBlockCount: Long) extends Data
case class WaitingForChannel(sender: ActorRef,c: CreatePayment, r: Seq[BinaryData], currentBlockCount: Long) extends Data
case class WaitingForComplete(sender: ActorRef,c: CMD_ADD_HTLC, channel: ActorRef) extends Data

sealed trait State
case object WAITING_FOR_REQUEST extends State
case object WAITING_FOR_ROUTE extends State
case object WAITING_FOR_CHANNEL extends State
case object WAITING_FOR_PAYMENT_COMPLETE extends State

// @formatter:on

/**
  * Created by PM on 26/08/2016.
  */
class PaymentLifecycle(router: ActorRef, selector: ActorRef, initialBlockCount: Long) extends LoggingFSM[State, Data] {

  import PaymentLifecycle._

  context.system.eventStream.subscribe(self, classOf[CurrentBlockCount])

  startWith(WAITING_FOR_REQUEST, WaitingForRequest(initialBlockCount))

  when(WAITING_FOR_REQUEST) {
    case Event(c: CreatePayment, WaitingForRequest(currentBlockCount)) =>
      router ! RouteRequest(Globals.Node.publicKey, c.targetNodeId)
      goto(WAITING_FOR_ROUTE) using WaitingForRoute(sender, c, currentBlockCount)

    case Event(CurrentBlockCount(currentBlockCount), d: WaitingForRequest) =>
      stay using d.copy(currentBlockCount = currentBlockCount)
  }

  when(WAITING_FOR_ROUTE) {
    case Event(RouteResponse(r), WaitingForRoute(s, c, currentBlockCount)) =>
      selector ! SelectChannelRequest(r.drop(1).head)
      goto(WAITING_FOR_CHANNEL) using WaitingForChannel(s, c, r, currentBlockCount)

    case Event(f@Failure(t), WaitingForRoute(s, _, _)) =>
      s ! f
      stop(FSM.Failure(t))

    case Event(CurrentBlockCount(currentBlockCount), d: WaitingForRoute) =>
      stay using d.copy(currentBlockCount = currentBlockCount)
  }

  when(WAITING_FOR_CHANNEL) {
    case Event(SelectChannelResponse(Some(channel)), WaitingForChannel(s, c, r, currentBlockCount)) =>
      val next = r.drop(1).head
      val others = r.drop(2)
      val route = buildRoute(c.amountMsat, next +: others)
      val cmd = CMD_ADD_HTLC(route.steps(0).amount, c.h, currentBlockCount.toInt + 100 + route.steps.size - 2, route.copy(steps = route.steps.tail), commit = true)
      context.system.eventStream.subscribe(self, classOf[PaymentSent])
      context.system.eventStream.subscribe(self, classOf[PaymentFailed])
      context.system.eventStream.unsubscribe(self, classOf[CurrentBlockCount])
      channel ! cmd
      goto(WAITING_FOR_PAYMENT_COMPLETE) using WaitingForComplete(s, cmd, channel)

    case Event(CurrentBlockCount(currentBlockCount), d: WaitingForChannel) =>
      stay using d.copy(currentBlockCount = currentBlockCount)
  }

  when(WAITING_FOR_PAYMENT_COMPLETE) {
    case Event("ok", _) => stay()

    case Event(e@PaymentSent(_, h), WaitingForComplete(s, cmd, channel)) if h == cmd.rHash =>
      s ! "sent"
      stop(FSM.Normal)

    case Event(e@PaymentFailed(_, h, reason), WaitingForComplete(s, cmd, channel)) if h == cmd.rHash =>
      s ! Status.Failure(new RuntimeException(reason))
      stop(FSM.Failure(reason))

    case Event(CurrentBlockCount(_), _) =>
      stay
  }

}

object PaymentLifecycle {

  def props(router: ActorRef, selector: ActorRef, initialBlockCount: Long) = Props(classOf[PaymentLifecycle], router, selector, initialBlockCount)

  def buildRoute(finalAmountMsat: Int, nodeIds: Seq[BinaryData]): lightning.route = {

    // TODO : use actual fee parameters that are specific to each node
    def fee(amountMsat: Int) = nodeFee(Globals.base_fee, Globals.proportional_fee, amountMsat).toInt

    var amountMsat = finalAmountMsat
    val steps = nodeIds.reverse.map(nodeId => {
      val step = route_step(amountMsat, next = Next.Bitcoin(nodeId))
      amountMsat = amountMsat + fee(amountMsat)
      step
    })
    lightning.route(steps.reverse :+ route_step(0, next = route_step.Next.End(true)))
  }
}