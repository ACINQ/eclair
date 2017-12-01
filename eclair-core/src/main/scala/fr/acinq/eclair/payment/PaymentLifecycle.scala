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
case class ReceivePayment(amountMsat_opt: Option[MilliSatoshi], description: String)
case class SendPayment(amountMsat: Long, paymentHash: BinaryData, targetNodeId: PublicKey, minFinalCltvExpiry: Long = PaymentLifecycle.defaultMinFinalCltvExpiry, maxAttempts: Int = 5)

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
      log.info(s"route found: attempt=${failures.size + 1}/${c.maxAttempts} route=${hops.map(_.nextNodeId).mkString("->")} channels=${hops.map(_.lastUpdate.shortChannelId.toHexString).mkString("->")}")
      val firstHop = hops.head
      val finalExpiry = Globals.blockCount.get().toInt + c.minFinalCltvExpiry.toInt
      val (cmd, sharedSecrets) = buildCommand(c.amountMsat, finalExpiry, c.paymentHash, hops)
      register ! Register.ForwardShortId(firstHop.lastUpdate.shortChannelId, cmd)
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
          log.warning(s"received an error message from target nodeId=$nodeId, failing the payment (failure=$failureMessage)")
          s ! PaymentFailed(c.paymentHash, failures = failures :+ RemoteFailure(hops, e))
          stop(FSM.Normal)
        case Some(e@ErrorPacket(nodeId, failureMessage)) if failures.size + 1 >= c.maxAttempts =>
          log.info(s"received an error message from nodeId=$nodeId (failure=$failureMessage)")
          log.warning(s"too many failed attempts, failing the payment")
          s ! PaymentFailed(c.paymentHash, failures = failures :+ RemoteFailure(hops, e))
          stop(FSM.Normal)
        case Some(e@ErrorPacket(nodeId, failureMessage: Node)) =>
          log.info(s"received an error message from nodeId=$nodeId, trying to route around it (failure=$failureMessage)")
          // let's try to route around this node
          router ! RouteRequest(sourceNodeId, c.targetNodeId, ignoreNodes + nodeId, ignoreChannels)
          goto(WAITING_FOR_ROUTE) using WaitingForRoute(s, c, failures :+ RemoteFailure(hops, e))
        case Some(e@ErrorPacket(nodeId, failureMessage: Update)) =>
          log.info(s"received 'Update' type error message from nodeId=$nodeId, retrying payment (failure=$failureMessage)")
          if (Announcements.checkSig(failureMessage.update, nodeId)) {
            // note that we check the sig, but we don't make sure that this update was for the exact channel we required
            // the reason is that we don't want to prevent relaying nodes to use another channel to the same N+1 node if they deem necessary
            failureMessage match {
              case _: TemporaryChannelFailure =>
                // node indicates that its outgoing channel is experiencing a transient issue (eg. channel capacity reached, too many in-flight htlc)
                hops.find(_.nodeId == nodeId).map(_.lastUpdate) match {
                  case Some(u) if u.copy(signature = BinaryData.empty, timestamp = 0) == failureMessage.update.copy(signature = BinaryData.empty, timestamp = 0) =>
                    // node returned the exact same update we used: in that case, let's temporarily exclude the channel from future routes, giving it time to recover
                    val nextNodeId = hops.find(_.nodeId == nodeId).get.nextNodeId
                    router ! ExcludeChannel(ChannelDesc(failureMessage.update.shortChannelId, nodeId, nextNodeId))
                  case _ => // node returned a different update, maybe the payment will go through next time...
                }
              case _ => {}
            }
            // in any case, we forward the update to the router
            router ! failureMessage.update
            // let's try again, router will have updated its state
            router ! RouteRequest(sourceNodeId, c.targetNodeId, ignoreNodes, ignoreChannels)
          } else {
            // this node is fishy, it gave us a bad sig!! let's filter it out
            log.warning(s"got bad signature from node=$nodeId update=${failureMessage.update}")
            router ! RouteRequest(sourceNodeId, c.targetNodeId, ignoreNodes + nodeId, ignoreChannels)
          }
          goto(WAITING_FOR_ROUTE) using WaitingForRoute(s, c, failures :+ RemoteFailure(hops, e))
        case Some(e@ErrorPacket(nodeId, failureMessage)) =>
          log.info(s"received an error message from nodeId=$nodeId, trying to use a different channel (failure=$failureMessage)")
          // let's try again without the channel outgoing from nodeId
          val faultyChannel = hops.find(_.nodeId == nodeId).map(_.lastUpdate.shortChannelId)
          router ! RouteRequest(sourceNodeId, c.targetNodeId, ignoreNodes, ignoreChannels ++ faultyChannel.toSet)
          goto(WAITING_FOR_ROUTE) using WaitingForRoute(s, c, failures :+ RemoteFailure(hops, e))
      }

    case Event(fail: UpdateFailMalformedHtlc, _) =>
      log.info(s"first node in the route couldn't parse our htlc: fail=$fail")
      // this is a corner case, that can only happen when the *first* node in the route cannot parse the onion
      // (if this happens higher up in the route, the error would be wrapped in an UpdateFailHtlc and handled above)
      // let's consider it a local error and treat is as such
      self ! Status.Failure(new RuntimeException("first hop returned an UpdateFailMalformedHtlc message"))
      stay

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
    * @param finalExpiry     the final htlc expiry in number of blocks
    * @param hops            the hops as computed by the router + extra routes from payment request
    * @return a (firstAmountMsat, firstExpiry, payloads) tuple where:
    *         - firstAmountMsat is the amount for the first htlc in the route
    *         - firstExpiry is the cltv expiry for the first htlc in the route
    *         - a sequence of payloads that will be used to build the onion
    */
  def buildPayloads(finalAmountMsat: Long, finalExpiry: Int, hops: Seq[PaymentHop]): (Long, Int, Seq[PerHopPayload]) =
    hops.reverse.foldLeft((finalAmountMsat, finalExpiry, PerHopPayload(0L, finalAmountMsat, finalExpiry) :: Nil)) {
      case ((msat, expiry, payloads), hop) =>
        (msat + hop.nextFee(msat), expiry + hop.cltvExpiryDelta, PerHopPayload(hop.shortChannelId, msat, expiry) +: payloads)
    }

  // this is defined in BOLT 11
  val defaultMinFinalCltvExpiry = 9

  def buildCommand(finalAmountMsat: Long, finalExpiry: Int, paymentHash: BinaryData, hops: Seq[Hop]): (CMD_ADD_HTLC, Seq[(BinaryData, PublicKey)]) = {
    val (firstAmountMsat, firstExpiry, payloads) = buildPayloads(finalAmountMsat, finalExpiry, hops.drop(1))
    val nodes = hops.map(_.nextNodeId)
    // BOLT 2 requires that associatedData == paymentHash
    val onion = buildOnion(nodes, payloads, paymentHash)
    CMD_ADD_HTLC(firstAmountMsat, paymentHash, firstExpiry, Packet.write(onion.packet), upstream_opt = None, commit = true) -> onion.sharedSecrets
  }

}
