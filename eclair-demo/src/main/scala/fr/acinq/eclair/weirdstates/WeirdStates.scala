package fr.acinq.eclair.weirdstates

import akka.actor.{ActorSystem, LoggingFSM, Props}
import akka.util.Timeout
import fr.acinq.bitcoin.Transaction
import fr.acinq.eclair._

import scala.concurrent.duration._

sealed trait State
case object WeirdState extends State
case object Closed extends State

sealed trait Data
case object NoData extends Data

case class BITCOIN_ANCHOR_SPENT(tx: Transaction)

/**
  * Created by PM on 05/01/2016.
  */
class WeirdStates extends LoggingFSM[State, Data] {

  import WeirdStates._

  startWith(WeirdState, NoData)

  when(WeirdState) {
    case Event(BITCOIN_ANCHOR_SPENT(tx), NoData) if (isOurCommit(tx)) =>
      // wait for timeout, then spend ours
      // watch spending tx
      // watch HTLCs
      goto(WeirdState)

    case Event(BITCOIN_ANCHOR_SPENT(tx), NoData) if (isTheirCommit(tx)) =>
      // spend ours immediately
      // watch spending tx
      // watch HTLCs
      goto(WeirdState)

    case Event(BITCOIN_ANCHOR_SPENT(tx), NoData) if (isRevokedCommit(tx)) =>
      // steal immediately
      // watch stealing tx
      // watch HTLCs
      goto(WeirdState)

    case Event(BITCOIN_ANCHOR_SPENT(tx), NoData) =>
      // we're fucked
      goto(WeirdState) // TODO : should be INFORMATION_LEAK ?

    case Event(BITCOIN_CLOSE_DONE) =>
      goto(Closed)

    case Event(BITCOIN_SPEND_OURS_DONE) =>
      goto(Closed)

    case Event(BITCOIN_SPEND_THEIRS_DONE) =>
      goto(Closed)

    case Event(BITCOIN_STEAL_DONE) =>
      goto(Closed)
  }

  when(Closed) {
    case _ => stay
  }

  initialize()

}

object WeirdStates {

  def isOurCommit(tx: Transaction): Boolean = ???

  def isTheirCommit(tx: Transaction): Boolean = ???

  def isRevokedCommit(tx: Transaction): Boolean = ???

}

object TestBoot extends App {

  val system = ActorSystem()
  implicit val timeout = Timeout(30 seconds)

  val fsm = system.actorOf(Props[WeirdStates], "fsm")

}