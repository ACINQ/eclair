package fr.acinq.eclair.payment.send

import java.util.UUID

import akka.actor.Actor.Receive
import akka.actor.{Actor, ActorContext, ActorRef, DiagnosticActorLogging, Props}
import akka.event.DiagnosticLoggingAdapter
import akka.event.Logging.MDC
import fr.acinq.eclair.{Logs, NodeParams}
import fr.acinq.eclair.payment.send.PaymentInitiatorHandler.{PendingPayment, SendPaymentConfig}

trait InitiatorHandler {

  def spawnPaymentFsm(paymentCfg: SendPaymentConfig, ctx: ActorContext): ActorRef

  def spawnMultiPartPaymentFsm(paymentCfg: SendPaymentConfig, ctx: ActorContext): ActorRef

  def handle(pending: Map[UUID, PendingPayment])(implicit ctx: ActorContext, log: DiagnosticLoggingAdapter): Receive

}

class PaymentInitiator(nodeParams: NodeParams, router: ActorRef, relayer: ActorRef, register: ActorRef) extends Actor with DiagnosticActorLogging {

  private val defaultPaymentInitiator = new PaymentInitiatorHandler(nodeParams, router, relayer, register)

  override def receive: Receive = main(defaultPaymentInitiator.handle(Map.empty)(context, log))

  def main(handler: Receive): Receive = handler orElse {
    case newHandler: InitiatorHandler =>
      log.info(s"registering initiator handler of type=${handler.getClass.getSimpleName}")
      context.become(newHandler.handle(Map.empty)(context, log))
  }

  override def mdc(currentMessage: Any): MDC = Logs.mdc(category_opt = Some(Logs.LogCategory.PAYMENT))

}

object PaymentInitiator {

  def props(nodeParams: NodeParams, router: ActorRef, relayer: ActorRef, register: ActorRef) = Props(new PaymentInitiator(nodeParams, router, relayer, register))

}