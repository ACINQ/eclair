package fr.acinq.eclair.payment

import akka.actor.{ActorRef, FSM, LoggingFSM, Props, Status}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi}
import fr.acinq.eclair._
import fr.acinq.eclair.channel.{CMD_ADD_HTLC, Register}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.crypto.Sphinx.{ErrorPacket, Packet}
import fr.acinq.eclair.router._
import fr.acinq.eclair.wire._
import scodec.Attempt

// @formatter:off
case class ReceivePayment(amountMsat: MilliSatoshi, description: String)
case class SendPayment(amountMsat: Long, paymentHash: BinaryData, targetNodeId: PublicKey, maxAttempts: Int = 5)

sealed trait PaymentResult
case class PaymentSucceeded(route: Seq[Hop], paymentPreimage: BinaryData) extends PaymentResult
sealed trait PaymentFailure
case class LocalFailure(t: Throwable) extends PaymentFailure
case class RemoteFailure(route: Seq[Hop], e: ErrorPacket) extends PaymentFailure
case class UnreadableRemoteFailure(route: Seq[Hop]) extends PaymentFailure
case class PaymentFailed(paymentHash: BinaryData, failures: Seq[PaymentFailure]) extends PaymentResult

sealed trait Data
case object WaitingForRequest extends Data
case class WaitingForRoute(sender: ActorRef, c: SendPayment, failures: Seq[PaymentFailure]) extends Data
case class WaitingForComplete(sender: ActorRef, c: SendPayment, cmd: CMD_ADD_HTLC, failures: Seq[PaymentFailure], sharedSecrets: Seq[(BinaryData, PublicKey)], ignoreNodes: Set[PublicKey], ignoreChannels: Set[Long], hops: Seq[Hop]) extends Data

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
    case Event(c: SendPayment, WaitingForRequest) =>
      router ! RouteRequest(sourceNodeId, c.targetNodeId)
      goto(WAITING_FOR_ROUTE) using WaitingForRoute(sender, c, failures = Nil)
  }

  when(WAITING_FOR_ROUTE) {
    case Event(RouteResponse(hops, ignoreNodes, ignoreChannels), WaitingForRoute(s, c, failures)) =>
      log.info(s"route found: attempt=${failures.size + 1}/${c.maxAttempts} route=${hops.map(_.nextNodeId).mkString("->")}")
      val firstHop = hops.head
      val finalExpiry = Globals.blockCount.get().toInt + defaultHtlcExpiry
      val (cmd, sharedSecrets) = buildCommand(c.amountMsat, finalExpiry, c.paymentHash, hops)
      // TODO: HACK!!!! see Router.scala
      if (firstHop.lastUpdate.signature.size == 32) {
        register ! Register.Forward(firstHop.lastUpdate.signature, cmd)
      } else {
        register ! Register.ForwardShortId(firstHop.lastUpdate.shortChannelId, cmd)
      }
      goto(WAITING_FOR_PAYMENT_COMPLETE) using WaitingForComplete(s, c, cmd, failures, sharedSecrets, ignoreNodes, ignoreChannels, hops)

    case Event(Status.Failure(t), WaitingForRoute(s, c, failures)) =>
      s ! PaymentFailed(c.paymentHash, failures = failures :+ LocalFailure(t))
      stop(FSM.Normal)
  }

  when(WAITING_FOR_PAYMENT_COMPLETE) {
    case Event("ok", _) => stay()

    case Event(fulfill: UpdateFulfillHtlc, w: WaitingForComplete) =>
      w.sender ! PaymentSucceeded(w.hops, fulfill.paymentPreimage)
      context.system.eventStream.publish(PaymentSent(MilliSatoshi(w.c.amountMsat), MilliSatoshi(w.cmd.amountMsat - w.c.amountMsat), w.cmd.paymentHash))
      stop(FSM.Normal)

    case Event(fail: UpdateFailHtlc, WaitingForComplete(s, c, _, failures, sharedSecrets, ignoreNodes, ignoreChannels, hops)) =>
      Sphinx.parseErrorPacket(fail.reason, sharedSecrets) match {
        case None =>
          log.warning(s"cannot parse returned error ${fail.reason}")
          s ! PaymentFailed(c.paymentHash, failures = failures :+ UnreadableRemoteFailure(hops))
          stop(FSM.Normal)
        case Some(e@ErrorPacket(nodeId, failureMessage)) if nodeId == c.targetNodeId =>
          // TODO: spec says: that MAY retry the payment in certain conditions, see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#receiving-failure-codes
          log.warning(s"received an error message from target nodeId=$nodeId, failing the payment (failure=$failureMessage)")
          s ! PaymentFailed(c.paymentHash, failures = failures :+ RemoteFailure(hops, e))
          stop(FSM.Normal)
        case Some(e@ErrorPacket(nodeId, failureMessage)) if failures.size + 1 >= c.maxAttempts =>
          log.info(s"received an error message from nodeId=$nodeId (failure=$failureMessage)")
          log.warning(s"too many failed attempts, failing the payment")
          s ! PaymentFailed(c.paymentHash, failures = failures :+ RemoteFailure(hops, e))
          stop(FSM.Normal)
        case Some(e@ErrorPacket(nodeId, failureMessage: Node)) =>
          // TODO: spec says: If the PERM bit is not set, the origin node SHOULD restore the channels as it sees new channel_updates.
          log.info(s"received an error message from nodeId=$nodeId, trying to route around it (failure=$failureMessage)")
          // let's try to route around this node
          router ! RouteRequest(sourceNodeId, c.targetNodeId, ignoreNodes + nodeId, ignoreChannels)
          goto(WAITING_FOR_ROUTE) using WaitingForRoute(s, c, failures :+ RemoteFailure(hops, e))
        case Some(e@ErrorPacket(nodeId, failureMessage: Update)) =>
          // TODO: spec says: if UPDATE is set, and the channel_update is valid *and more recent* than the channel_update used to send the payment
          log.info(s"received 'Update' type error message from nodeId=$nodeId, retrying payment (failure=$failureMessage)")
          if (Announcements.checkSig(failureMessage.update, nodeId)) {
            // note that we check the sig, but we don't make sure that this update was related to the channel we used
            // the reason is that we don't to prevent relaying nodes to use another channel to the same N+1 node if they deem necessary
            router ! failureMessage.update
            // let's try again, router will have updated its state
            router ! RouteRequest(sourceNodeId, c.targetNodeId, ignoreNodes, ignoreChannels)
          } else {
            // this node is fishy!! let's filter it out
            router ! RouteRequest(sourceNodeId, c.targetNodeId, ignoreNodes + nodeId, ignoreChannels)
          }
//          val faultyChannel = hops.find(_.nodeId == nodeId).map(_.lastUpdate) match {
//            case Some(u) if u.copy(signature = BinaryData.empty, timestamp = 0) != failureMessage.update.copy(signature = BinaryData.empty, timestamp = 0) =>
//              // the channelUpdate they provided is different from the one we last had, we won't filter out this channel for the next try
//              Set.empty
//            case _ =>
//              // the channelUpdate they provided is the same we used last time: let's filter out this channel for the last try
//              Set(faultyChannel)
//          }
          goto(WAITING_FOR_ROUTE) using WaitingForRoute(s, c, failures :+ RemoteFailure(hops, e))
        case Some(e@ErrorPacket(nodeId, failureMessage)) =>
          // TODO: If the PERM bit is not set, the origin node SHOULD restore the channel as it sees a new channel_update.
          log.info(s"received an error message from nodeId=$nodeId, trying to use a different channel (failure=$failureMessage)")
          // let's try again without the channel outgoing from nodeId
          val faultyChannel = hops.find(_.nodeId == nodeId).map(_.lastUpdate.shortChannelId)
          router ! RouteRequest(sourceNodeId, c.targetNodeId, ignoreNodes, ignoreChannels ++ faultyChannel.toSet)
          goto(WAITING_FOR_ROUTE) using WaitingForRoute(s, c, failures :+ RemoteFailure(hops, e))
      }

    case Event(Status.Failure(t), WaitingForComplete(s, c, _, failures, _, ignoreNodes, ignoreChannels, hops)) =>
      if (failures.size + 1 >= c.maxAttempts) {
        s ! PaymentFailed(c.paymentHash, failures :+ LocalFailure(t))
        stop(FSM.Normal)
      } else {
        log.info(s"received an error message from local, trying to use a different channel (failure=${t.getMessage})")
        router ! RouteRequest(sourceNodeId, c.targetNodeId, ignoreNodes, ignoreChannels + hops.head.lastUpdate.shortChannelId)
        goto(WAITING_FOR_ROUTE) using WaitingForRoute(s, c, failures :+ LocalFailure(t))
      }

  }

  initialize()
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

  def buildOnion(nodes: Seq[PublicKey], payloads: Seq[PerHopPayload], associatedData: BinaryData): Sphinx.PacketAndSecrets = {
    require(nodes.size == payloads.size)
    val sessionKey = randomKey
    val payloadsbin: Seq[BinaryData] = payloads
      .map(LightningMessageCodecs.perHopPayloadCodec.encode(_))
      .map {
        case Attempt.Successful(bitVector) => BinaryData(bitVector.toByteArray)
        case Attempt.Failure(cause) => throw new RuntimeException(s"serialization error: $cause")
      }
    Sphinx.makePacket(sessionKey, nodes, payloadsbin, associatedData)
  }

  /**
    *
    * @param finalAmountMsat the final htlc amount in millisatoshis
    * @param finalExpiry the final htlc expiry in number of blocks
    * @param hops            the hops as computed by the router
    * @return a (firstAmountMsat, firstExpiry, payloads) tuple where:
    *         - firstAmountMsat is the amount for the first htlc in the route
    *         - firstExpiry is the cltv expiry for the first htlc in the route
    *         - a sequence of payloads that will be used to build the onion
    */
  def buildPayloads(finalAmountMsat: Long, finalExpiry: Int, hops: Seq[Hop]): (Long, Int, Seq[PerHopPayload]) =
    hops.reverse.foldLeft((finalAmountMsat, finalExpiry, PerHopPayload(0L, finalAmountMsat, finalExpiry) :: Nil)) {
      case ((msat, expiry, payloads), hop) =>
        val feeMsat = nodeFee(hop.lastUpdate.feeBaseMsat, hop.lastUpdate.feeProportionalMillionths, msat)
        val expiryDelta = hop.lastUpdate.cltvExpiryDelta
        (msat + feeMsat, expiry + expiryDelta, PerHopPayload(hop.lastUpdate.shortChannelId, msat, expiry) +: payloads)
    }

  // TODO: set correct initial expiry
  val defaultHtlcExpiry = 10

  def buildCommand(finalAmountMsat: Long, finalExpiry: Int, paymentHash: BinaryData, hops: Seq[Hop]): (CMD_ADD_HTLC, Seq[(BinaryData, PublicKey)]) = {
    val (firstAmountMsat, firstExpiry, payloads) = buildPayloads(finalAmountMsat, finalExpiry, hops.drop(1))
    val nodes = hops.map(_.nextNodeId)
    // BOLT 2 requires that associatedData == paymentHash
    val onion = buildOnion(nodes, payloads, paymentHash)
    CMD_ADD_HTLC(firstAmountMsat, paymentHash, firstExpiry, Packet.write(onion.packet), upstream_opt = None, commit = true) -> onion.sharedSecrets
  }

}
