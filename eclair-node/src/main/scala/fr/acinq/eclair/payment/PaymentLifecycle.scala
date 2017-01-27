package fr.acinq.eclair.payment

import akka.actor.Status.Failure
import akka.actor.{ActorRef, FSM, LoggingFSM, Props, Status}
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.peer.CurrentBlockCount
import fr.acinq.eclair.channel.{CMD_ADD_HTLC, PaymentFailed, PaymentSent, Register}
import fr.acinq.eclair.router._

// @formatter:off

case class CreatePayment(amountMsat: Int, h: BinaryData, targetNodeId: BinaryData)

sealed trait Data
case class WaitingForRequest(currentBlockCount: Long) extends Data
case class WaitingForRoute(sender: ActorRef, c: CreatePayment, currentBlockCount: Long) extends Data
case class WaitingForComplete(sender: ActorRef,c: CMD_ADD_HTLC) extends Data

sealed trait State
case object WAITING_FOR_REQUEST extends State
case object WAITING_FOR_ROUTE extends State
case object WAITING_FOR_PAYMENT_COMPLETE extends State

// @formatter:on

/**
  * Created by PM on 26/08/2016.
  */
class PaymentLifecycle(router: ActorRef, currentBlockCount: Long) extends LoggingFSM[State, Data] {

  import PaymentLifecycle._

  startWith(WAITING_FOR_REQUEST, WaitingForRequest(currentBlockCount))

  when(WAITING_FOR_REQUEST) {
    case Event(c: CreatePayment, WaitingForRequest(currentBlockCount)) =>
      router ! RouteRequest(Globals.Node.publicKey, c.targetNodeId)
      goto(WAITING_FOR_ROUTE) using WaitingForRoute(sender, c, currentBlockCount)
  }

  when(WAITING_FOR_ROUTE) {
    case Event(RouteResponse(hops), WaitingForRoute(s, c, currentBlockCount)) =>
      val cmd = buildCommand(c.amountMsat, hops, currentBlockCount)
      context.system.eventStream.subscribe(self, classOf[PaymentSent])
      context.system.eventStream.subscribe(self, classOf[PaymentFailed])
      context.system.eventStream.unsubscribe(self, classOf[CurrentBlockCount])
      context.actorSelection(Register.actorPathToChannelId(hops.head.lastUpdate.channelId)) ! cmd
      goto(WAITING_FOR_PAYMENT_COMPLETE) using WaitingForComplete(s, cmd)

    case Event(f@Failure(t), WaitingForRoute(s, _, _)) =>
      s ! f
      stop(FSM.Failure(t))
  }

  when(WAITING_FOR_PAYMENT_COMPLETE) {
    case Event("ok", _) => stay()

    case Event(e@PaymentSent(_, h), WaitingForComplete(s, cmd)) if h == cmd.paymentHash =>
      s ! "sent"
      stop(FSM.Normal)

    case Event(e@PaymentFailed(_, h, reason), WaitingForComplete(s, cmd)) if h == cmd.paymentHash =>
      s ! Status.Failure(new RuntimeException(reason))
      stop(FSM.Failure(reason))
  }

}

object PaymentLifecycle {

  def props(router: ActorRef, initialBlockCount: Long) = Props(classOf[PaymentLifecycle], router, initialBlockCount)

  def buildRoute(finalAmountMsat: Int, hops: Seq[Hop]): lightning.route = ???/*{

    // TODO: use actual fee parameters that are specific to each node
    def fee(amountMsat: Int) = nodeFee(Globals.fee_base_msat, Globals.fee_proportional_msat, amountMsat).toInt

    var amountMsat = finalAmountMsat
    val steps = nodeIds.reverse.map(nodeId => {
      val step = route_step(amountMsat, next = Next.Bitcoin(nodeId))
      amountMsat = amountMsat + fee(amountMsat)
      step
    })
    lightning.route(steps.reverse :+ route_step(0, next = route_step.Next.End(true)))
  }*/

  def buildCommand(finalAmountMsat: Int, hops: Seq[Hop], currentBlockCount: Long): CMD_ADD_HTLC = ???
  // CMD_ADD_HTLC(route.steps(0).amount, c.h, currentBlockCount.toInt + 100 + route.steps.size - 2, route.copy(steps = route.steps.tail), commit = true)
}