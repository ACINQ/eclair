package fr.acinq.eclair

import akka.actor.{Actor, ActorLogging, OneForOneStrategy, Props, SupervisorStrategy}

/**
  * This supervisor will supervise a single child actor using the provided SupervisorStrategy
  * All incoming messages will be forwarded to the child actor.
  *
  * Created by PM on 17/03/2017.
  */
class SimpleSupervisor(childProps: Props, childName: String, strategy: SupervisorStrategy.Directive) extends Actor with ActorLogging {

  val child = context.actorOf(childProps, childName)

  override def receive: Receive = {
    case msg => child forward msg
  }

  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = true) { case _ => strategy }
}

object SimpleSupervisor {

  def props(childProps: Props, childName: String, strategy: SupervisorStrategy.Directive) = Props(new SimpleSupervisor(childProps, childName, strategy))

}
