package fr.acinq.lightning.mystatemachine

import akka.actor.{LoggingFSM, Props, ActorSystem}
import akka.util.Timeout
import scala.concurrent.duration._

sealed trait State
case object Idle extends State
case object Active extends State

sealed trait Data
case object Uninitialized extends Data

trait Closeable {
  def a: String
}
case class A(a: String, b: Int) extends Closeable with Data
case class B() extends Data


case object Event1
case object Event2

class StateMachine extends LoggingFSM[State, Data] {

  startWith(Idle, Uninitialized)

  when(Idle) {
    case Event(Event1, _) =>
      goto(Active) using A("x", 3)
  }

  // transition elided ...

  when(Active) {
    case Event(Event2, b: B) =>
      goto(Idle)

    case Event(Event2, a: Closeable) =>
      println(a)
      goto(Idle)
  }

  initialize()
}


/**
  * Created by PM on 12/01/2016.
  */
object StateMachineTest extends App {

  val system = ActorSystem()
  implicit val timeout = Timeout(30 seconds)

  val fsm = system.actorOf(Props[StateMachine])

  fsm ! Event1
  fsm ! Event2

}
