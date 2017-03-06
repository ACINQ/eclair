package fr.acinq.eclair.payment

import akka.actor.Status.Failure
import akka.actor.{ActorRef, FSM, LoggingFSM, Props, Status}
import fr.acinq.bitcoin.BinaryData
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair._
import fr.acinq.eclair.channel.{CMD_ADD_HTLC, Register}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.router._
import fr.acinq.eclair.wire.{Codecs, PerHopPayload, UpdateFailHtlc, UpdateFulfillHtlc}
import scodec.Attempt

// @formatter:off

case class CreatePayment(amountMsat: Long, paymentHash: BinaryData, targetNodeId: PublicKey)

sealed trait Data
case object WaitingForRequest extends Data
case class WaitingForRoute(sender: ActorRef, c: CreatePayment) extends Data
case class WaitingForComplete(sender: ActorRef,c: CMD_ADD_HTLC) extends Data

sealed trait State
case object WAITING_FOR_REQUEST extends State
case object WAITING_FOR_ROUTE extends State
case object WAITING_FOR_PAYMENT_COMPLETE extends State

// @formatter:on

/**
  * Created by PM on 26/08/2016.
  */
class PaymentLifecycle(sourceNodeId: PublicKey, router: ActorRef, register: ActorRef) extends LoggingFSM[State, Data] {

  import PaymentLifecycle._

  startWith(WAITING_FOR_REQUEST, WaitingForRequest)

  when(WAITING_FOR_REQUEST) {
    case Event(c: CreatePayment, WaitingForRequest) =>
      router ! RouteRequest(sourceNodeId, c.targetNodeId)
      goto(WAITING_FOR_ROUTE) using WaitingForRoute(sender, c)
  }

  when(WAITING_FOR_ROUTE) {
    case Event(RouteResponse(hops), WaitingForRoute(s, c)) =>
      val firstHop = hops.head
      val cmd = buildCommand(c.amountMsat, c.paymentHash, hops, Globals.blockCount.get().toInt)
      register ! Register.ForwardShortId(firstHop.lastUpdate.shortChannelId, cmd)
      goto(WAITING_FOR_PAYMENT_COMPLETE) using WaitingForComplete(s, cmd)

    case Event(f@Failure(t), WaitingForRoute(s, _)) =>
      s ! f
      stop(FSM.Failure(t))
  }

  when(WAITING_FOR_PAYMENT_COMPLETE) {
    case Event("ok", _) => stay()

    case Event(reason: String, WaitingForComplete(s, _)) =>
      s ! Status.Failure(new RuntimeException(reason))
      stop(FSM.Failure(reason))

    case Event(fulfill: UpdateFulfillHtlc, WaitingForComplete(s, cmd)) =>
      s ! "sent"
      stop(FSM.Normal)

    case Event(fail: UpdateFailHtlc, WaitingForComplete(s, cmd)) =>
      // TODO: fix new String(fail.reason)
      val reason = new String(fail.reason)
      s ! Status.Failure(new RuntimeException(reason))
      stop(FSM.Failure(reason))

    case Event(failure: Failure, WaitingForComplete(s, cmd)) => {
      s ! failure
      stop(FSM.Failure(failure.cause))
    }
  }
}

object PaymentLifecycle {

  def props(sourceNodeId: PublicKey, router: ActorRef, register: ActorRef) = Props(classOf[PaymentLifecycle], sourceNodeId, router, register)

  /**
    *
    * @param baseMsat     fixed fee
    * @param proportional proportional fee
    * @param msat         amount in millisatoshi
    * @return the fee (in msat) that a node should be paid to forward an HTLC of 'amount' millisatoshis
    */
  def nodeFee(baseMsat: Long, proportional: Long, msat: Long): Long = baseMsat + (proportional * msat) / 1000000

  def buildOnion(nodes: Seq[BinaryData], payloads: Seq[PerHopPayload], associatedData: BinaryData): BinaryData = {
    require(nodes.size == payloads.size + 1, s"count mismatch: there should be one less payload than nodes (nodes=${nodes.size} payloads=${payloads.size})")

    val pubkeys = nodes.map(PublicKey(_))

    val sessionKey = randomKey

    val payloadsbin: Seq[BinaryData] = payloads
      .map(Codecs.perHopPayloadCodec.encode(_))
      .map {
        case Attempt.Successful(bitVector) => BinaryData(bitVector.toByteArray)
        case Attempt.Failure(cause) => throw new RuntimeException(s"serialization error: $cause")
      } :+ BinaryData("00" * 20)

    Sphinx.makePacket(sessionKey, pubkeys, payloadsbin, associatedData)
  }

  /**
    *
    * @param finalAmountMsat the final htlc amount in millisatoshis
    * @param hops            the hops as computed by the router
    * @return a (firstAmountMsat, firstExpiry, payloads) tuple where:
    *         - firstAmountMsat is the amount for the first htlc in the route
    *         - firstExpiry is the cltv expiry for the first htlc in the route
    *         - a sequence of payloads that will be used to build the onion
    */
  def buildRoute(finalAmountMsat: Long, hops: Seq[Hop], currentBlockCount: Int): (Long, Int, Seq[PerHopPayload]) =
    hops.reverse.foldLeft((finalAmountMsat, currentBlockCount + defaultHtlcExpiry, Seq.empty[PerHopPayload])) {
      case ((msat, expiry, payloads), hop) =>
        val feeMsat = nodeFee(hop.lastUpdate.feeBaseMsat, hop.lastUpdate.feeProportionalMillionths, msat)
        val expiryDelta = hop.lastUpdate.cltvExpiryDelta
        (msat + feeMsat, expiry + expiryDelta, PerHopPayload(msat, expiry) +: payloads)
    }

  // TODO: set correct initial expiry
  val defaultHtlcExpiry = 10

  def buildCommand(finalAmountMsat: Long, paymentHash: BinaryData, hops: Seq[Hop], currentBlockCount: Int): CMD_ADD_HTLC = {
    val (firstAmountMsat, firstExpiry, payloads) = buildRoute(finalAmountMsat, hops.drop(1), currentBlockCount)
    val nodes = hops.map(_.nextNodeId)
    // BOLT 2 requires that associatedData == paymentHash
    val onion = buildOnion(nodes, payloads, paymentHash)
    CMD_ADD_HTLC(firstAmountMsat, paymentHash, firstExpiry, onion, upstream_opt = None, commit = true)
  }

}
