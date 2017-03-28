package fr.acinq.eclair.payment

import akka.actor.Status.Failure
import akka.actor.{ActorRef, FSM, LoggingFSM, Props, Status}
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair._
import fr.acinq.eclair.channel.{CMD_ADD_HTLC, Register}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.crypto.Sphinx.ErrorPacket
import fr.acinq.eclair.router._
import fr.acinq.eclair.wire._
import scodec.Attempt

// @formatter:off

case class CreatePayment(amountMsat: Long, paymentHash: BinaryData, targetNodeId: PublicKey)

sealed trait PaymentResult
case class PaymentSucceeded(paymentPreimage: BinaryData) extends PaymentResult
case class PaymentFailed(paymentHash: BinaryData, error: Option[ErrorPacket]) extends PaymentResult

sealed trait Data
case object WaitingForRequest extends Data
case class WaitingForRoute(sender: ActorRef, c: CreatePayment, attempts: Int) extends Data
case class WaitingForComplete(sender: ActorRef, c: CreatePayment, cmd: CMD_ADD_HTLC, attempts: Int, sharedSecrets: Seq[(BinaryData, PublicKey)], ignoreNodes: Set[PublicKey], ignoreChannels: Set[Long], hops: Seq[Hop]) extends Data

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
      goto(WAITING_FOR_ROUTE) using WaitingForRoute(sender, c, attempts = 0)
  }

  when(WAITING_FOR_ROUTE) {
    case Event(RouteResponse(hops, ignoreNodes, ignoreChannels), WaitingForRoute(s, c, attempts)) =>
      val firstHop = hops.head
      val (cmd, sharedSecrets) = buildCommand(c.amountMsat, c.paymentHash, hops, Globals.blockCount.get().toInt)
      register ! Register.ForwardShortId(firstHop.lastUpdate.shortChannelId, cmd)
      goto(WAITING_FOR_PAYMENT_COMPLETE) using WaitingForComplete(s, c, cmd, attempts + 1, sharedSecrets, ignoreNodes, ignoreChannels, hops)

    case Event(f@Failure(t), WaitingForRoute(s, c, _)) =>
      s ! PaymentFailed(c.paymentHash, error = None)
      stop(FSM.Normal)
  }

  when(WAITING_FOR_PAYMENT_COMPLETE) {
    case Event("ok", _) => stay()

    case Event(reason: String, w: WaitingForComplete) =>
      w.sender ! Status.Failure(new RuntimeException(reason))
      stop(FSM.Failure(reason))

    case Event(fulfill: UpdateFulfillHtlc, w: WaitingForComplete) =>
      w.sender ! PaymentSucceeded(fulfill.paymentPreimage)
      context.system.eventStream.publish(PaymentSent(MilliSatoshi(w.c.amountMsat), MilliSatoshi(w.cmd.amountMsat - w.c.amountMsat), w.cmd.paymentHash))
      stop(FSM.Normal)

    case Event(fail: UpdateFailHtlc, WaitingForComplete(s, c, _, attempts, sharedSecrets, ignoreNodes, ignoreChannels, hops)) =>
      Sphinx.parseErrorPacket(fail.reason, sharedSecrets) match {
        case e@Some(ErrorPacket(nodeId, failureMessage)) if nodeId == c.targetNodeId =>
          // TODO: spec says: that MAY retry the payment in certain conditions, see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#receiving-failure-codes
          s ! PaymentFailed(c.paymentHash, error = e)
          stop(FSM.Normal)
        case Some(ErrorPacket(nodeId, failureMessage: Node)) =>
          // TODO: spec says: If the PERM bit is not set, the origin node SHOULD restore the channels as it sees new channel_updates.
          log.info(s"received an error message from nodeId=$nodeId, trying to route around it (failure=$failureMessage)")
          // let's try to route around this node
          router ! RouteRequest(sourceNodeId, c.targetNodeId, ignoreNodes + nodeId, ignoreChannels)
          goto(WAITING_FOR_ROUTE) using WaitingForRoute(s, c, attempts)
        case e@Some(ErrorPacket(nodeId, failureMessage: Update)) =>
          // TODO: spec says: if UPDATE is set, and the channel_update is valid *and more recent* than the channel_update used to send the payment
          log.info(s"received 'Update' type error message from nodeId=$nodeId, retrying payment (failure=$failureMessage)")
          // TODO: should check that signature of the update is valid? Router will currently ignore it and send us an Error message
          router ! failureMessage.update
          // let's try again, router will have updated its state
          router ! RouteRequest(sourceNodeId, c.targetNodeId, ignoreNodes, ignoreChannels)
          goto(WAITING_FOR_ROUTE) using WaitingForRoute(s, c, attempts)
        case e@Some(ErrorPacket(nodeId, failureMessage)) if attempts < MAX_ATTEMPTS =>
          // TODO: If the PERM bit is not set, the origin node SHOULD restore the channel as it sees a new channel_update.
          log.info(s"received an error message from nodeId=$nodeId, trying to use a different channel (failure=$failureMessage)")
          // let's try again without the channel outgoing from nodeId
          router ! RouteRequest(sourceNodeId, c.targetNodeId, ignoreNodes, ignoreChannels ++ hops.find(hop => hop.nodeId == nodeId.toBin).map(_.lastUpdate.shortChannelId).toSet)
          goto(WAITING_FOR_ROUTE) using WaitingForRoute(s, c, attempts)
        case e@Some(ErrorPacket(nodeId, failureMessage)) =>
          log.warning(s"too many failed attempts, failing the payment (attempts=$attempts)")
          s ! PaymentFailed(c.paymentHash, error = e)
          stop(FSM.Normal)
        case None =>
          log.warning(s"cannot parse returned error ${fail.reason}")
          s ! PaymentFailed(c.paymentHash, error = None)
          stop(FSM.Normal)
      }

    case Event(failure: Failure, w: WaitingForComplete) => {
      w.sender ! failure
      stop(FSM.Failure(failure.cause))
    }
  }
}

object PaymentLifecycle {

  def props(sourceNodeId: PublicKey, router: ActorRef, register: ActorRef) = Props(classOf[PaymentLifecycle], sourceNodeId, router, register)

  val MAX_ATTEMPTS = 5

  /**
    *
    * @param baseMsat     fixed fee
    * @param proportional proportional fee
    * @param msat         amount in millisatoshi
    * @return the fee (in msat) that a node should be paid to forward an HTLC of 'amount' millisatoshis
    */
  def nodeFee(baseMsat: Long, proportional: Long, msat: Long): Long = baseMsat + (proportional * msat) / 1000000

  def buildOnion(nodes: Seq[BinaryData], payloads: Seq[PerHopPayload], associatedData: BinaryData): Sphinx.OnionPacket = {
    require(nodes.size == payloads.size + 1, s"count mismatch: there should be one less payload than nodes (nodes=${nodes.size} payloads=${payloads.size})")

    val pubkeys = nodes.map(PublicKey(_))

    val sessionKey = randomKey

    val payloadsbin: Seq[BinaryData] = payloads
      .map(LightningMessageCodecs.perHopPayloadCodec.encode(_))
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

  def buildCommand(finalAmountMsat: Long, paymentHash: BinaryData, hops: Seq[Hop], currentBlockCount: Int): (CMD_ADD_HTLC, Seq[(BinaryData, PublicKey)]) = {
    val (firstAmountMsat, firstExpiry, payloads) = buildRoute(finalAmountMsat, hops.drop(1), currentBlockCount)
    val nodes = hops.map(_.nextNodeId)
    // BOLT 2 requires that associatedData == paymentHash
    val onion = buildOnion(nodes, payloads, paymentHash)
    CMD_ADD_HTLC(firstAmountMsat, paymentHash, firstExpiry, onion.onionPacket, upstream_opt = None, commit = true) -> onion.sharedSecrets
  }

}
