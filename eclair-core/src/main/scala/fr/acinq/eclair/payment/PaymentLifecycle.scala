/*
 * Copyright 2018 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.payment

import java.text.NumberFormat
import java.util.Locale

import akka.actor.{ActorRef, FSM, Props, Status}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi}
import fr.acinq.eclair._
import fr.acinq.eclair.channel.{AddHtlcFailed, CMD_ADD_HTLC, Channel, Register}
import fr.acinq.eclair.crypto.Sphinx.{ErrorPacket, Packet}
import fr.acinq.eclair.crypto.{Sphinx, TransportHandler}
import fr.acinq.eclair.payment.PaymentLifecycle._
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.router._
import fr.acinq.eclair.wire._
import scodec.Attempt

import scala.util.{Failure, Success}

/**
  * Created by PM on 26/08/2016.
  */
class PaymentLifecycle(sourceNodeId: PublicKey, router: ActorRef, register: ActorRef) extends FSM[PaymentLifecycle.State, PaymentLifecycle.Data] {

  startWith(WAITING_FOR_REQUEST, WaitingForRequest)

  when(WAITING_FOR_REQUEST) {
    case Event(c: SendPayment, WaitingForRequest) =>
      router ! RouteRequest(sourceNodeId, c.targetNodeId, c.amountMsat, c.assistedRoutes, randomize = c.randomize, routeParams = c.routeParams)
      goto(WAITING_FOR_ROUTE) using WaitingForRoute(sender, c, failures = Nil)
  }

  when(WAITING_FOR_ROUTE) {
    case Event(RouteResponse(hops, ignoreNodes, ignoreChannels), WaitingForRoute(s, c, failures)) =>
      log.info(s"route found: attempt=${failures.size + 1}/${c.maxAttempts} route=${hops.map(_.nextNodeId).mkString("->")} channels=${hops.map(_.lastUpdate.shortChannelId).mkString("->")}")
      val firstHop = hops.head
      // we add one block in order to not have our htlc fail when a new block has just been found
      val finalExpiry = Globals.blockCount.get().toInt + c.finalCltvExpiry.toInt + 1

      val (cmd, sharedSecrets) = buildCommand(c.amountMsat, finalExpiry, c.paymentHash, hops)
      register ! Register.ForwardShortId(firstHop.lastUpdate.shortChannelId, cmd)
      goto(WAITING_FOR_PAYMENT_COMPLETE) using WaitingForComplete(s, c, cmd, failures, sharedSecrets, ignoreNodes, ignoreChannels, hops)

    case Event(Status.Failure(t), WaitingForRoute(s, c, failures)) =>
      reply(s, PaymentFailed(c.paymentHash, failures = failures :+ LocalFailure(t)))
      stop(FSM.Normal)
  }

  when(WAITING_FOR_PAYMENT_COMPLETE) {
    case Event("ok", _) => stay()

    case Event(fulfill: UpdateFulfillHtlc, WaitingForComplete(s, c, cmd, _, _, _, _, hops)) =>
      reply(s, PaymentSucceeded(cmd.amountMsat, c.paymentHash, fulfill.paymentPreimage, hops))
      context.system.eventStream.publish(PaymentSent(MilliSatoshi(c.amountMsat), MilliSatoshi(cmd.amountMsat - c.amountMsat), cmd.paymentHash, fulfill.paymentPreimage, fulfill.channelId))
      stop(FSM.Normal)

    case Event(fail: UpdateFailHtlc, WaitingForComplete(s, c, _, failures, sharedSecrets, ignoreNodes, ignoreChannels, hops)) =>
      Sphinx.parseErrorPacket(fail.reason, sharedSecrets) match {
        case Success(e@ErrorPacket(nodeId, failureMessage)) if nodeId == c.targetNodeId =>
          // if destination node returns an error, we fail the payment immediately
          log.warning(s"received an error message from target nodeId=$nodeId, failing the payment (failure=$failureMessage)")
          reply(s, PaymentFailed(c.paymentHash, failures = failures :+ RemoteFailure(hops, e)))
          stop(FSM.Normal)
        case res if failures.size + 1 >= c.maxAttempts =>
          // otherwise we never try more than maxAttempts, no matter the kind of error returned
          val failure = res match {
            case Success(e@ErrorPacket(nodeId, failureMessage)) =>
              log.info(s"received an error message from nodeId=$nodeId (failure=$failureMessage)")
              RemoteFailure(hops, e)
            case Failure(t) =>
              log.warning(s"cannot parse returned error: ${t.getMessage}")
              UnreadableRemoteFailure(hops)
          }
          log.warning(s"too many failed attempts, failing the payment")
          reply(s, PaymentFailed(c.paymentHash, failures = failures :+ failure))
          stop(FSM.Normal)
        case Failure(t) =>
          log.warning(s"cannot parse returned error: ${t.getMessage}")
          // in that case we don't know which node is sending garbage, let's try to blacklist all nodes except the one we are directly connected to and the destination node
          val blacklist = hops.map(_.nextNodeId).drop(1).dropRight(1)
          log.warning(s"blacklisting intermediate nodes=${blacklist.mkString(",")}")
          router ! RouteRequest(sourceNodeId, c.targetNodeId, c.amountMsat, c.assistedRoutes, ignoreNodes ++ blacklist, ignoreChannels, c.randomize, c.routeParams)
          goto(WAITING_FOR_ROUTE) using WaitingForRoute(s, c, failures :+ UnreadableRemoteFailure(hops))
        case Success(e@ErrorPacket(nodeId, failureMessage: Node)) =>
          log.info(s"received 'Node' type error message from nodeId=$nodeId, trying to route around it (failure=$failureMessage)")
          // let's try to route around this node
          router ! RouteRequest(sourceNodeId, c.targetNodeId, c.amountMsat, c.assistedRoutes, ignoreNodes + nodeId, ignoreChannels, c.randomize, c.routeParams)
          goto(WAITING_FOR_ROUTE) using WaitingForRoute(s, c, failures :+ RemoteFailure(hops, e))
        case Success(e@ErrorPacket(nodeId, failureMessage: Update)) =>
          log.info(s"received 'Update' type error message from nodeId=$nodeId, retrying payment (failure=$failureMessage)")
          if (Announcements.checkSig(failureMessage.update, nodeId)) {
            getChannelUpdateForNode(nodeId, hops) match {
              case Some(u) if u.shortChannelId != failureMessage.update.shortChannelId =>
                // it is possible that nodes in the route prefer using a different channel (to the same N+1 node) than the one we requested, that's fine
                log.info(s"received an update for a different channel than the one we asked: requested=${u.shortChannelId} actual=${failureMessage.update.shortChannelId} update=${failureMessage.update}")
              case Some(u) if areSame(u, failureMessage.update) =>
                // node returned the exact same update we used, this can happen e.g. if the channel is imbalanced
                // in that case, let's temporarily exclude the channel from future routes, giving it time to recover
                log.info(s"received exact same update from nodeId=$nodeId, excluding the channel from futures routes")
                val nextNodeId = hops.find(_.nodeId == nodeId).get.nextNodeId
                router ! ExcludeChannel(ChannelDesc(u.shortChannelId, nodeId, nextNodeId))
              case Some(u) if hasAlreadyFailedOnce(nodeId, failures) =>
                // this node had already given us a new channel update and is still unhappy, it is probably messing with us, let's exclude it
                log.warning(s"it is the second time nodeId=$nodeId answers with a new update, excluding it: old=$u new=${failureMessage.update}")
                val nextNodeId = hops.find(_.nodeId == nodeId).get.nextNodeId
                router ! ExcludeChannel(ChannelDesc(u.shortChannelId, nodeId, nextNodeId))
              case Some(u) =>
                log.info(s"got a new update for shortChannelId=${u.shortChannelId}: old=$u new=${failureMessage.update}")
              case None =>
                log.error(s"couldn't find a channel update for node=$nodeId, this should never happen")
            }
            // in any case, we forward the update to the router
            router ! failureMessage.update
            // let's try again, router will have updated its state
            router ! RouteRequest(sourceNodeId, c.targetNodeId, c.amountMsat, c.assistedRoutes, ignoreNodes, ignoreChannels, c.randomize, c.routeParams)
          } else {
            // this node is fishy, it gave us a bad sig!! let's filter it out
            log.warning(s"got bad signature from node=$nodeId update=${failureMessage.update}")
            router ! RouteRequest(sourceNodeId, c.targetNodeId, c.amountMsat, c.assistedRoutes, ignoreNodes + nodeId, ignoreChannels, c.randomize, c.routeParams)
          }
          goto(WAITING_FOR_ROUTE) using WaitingForRoute(s, c, failures :+ RemoteFailure(hops, e))
        case Success(e@ErrorPacket(nodeId, failureMessage)) =>
          log.info(s"received an error message from nodeId=$nodeId, trying to use a different channel (failure=$failureMessage)")
          // let's try again without the channel outgoing from nodeId
          val faultyChannel = hops.find(_.nodeId == nodeId).map(hop => ChannelDesc(hop.lastUpdate.shortChannelId, hop.nodeId, hop.nextNodeId))
          router ! RouteRequest(sourceNodeId, c.targetNodeId, c.amountMsat, c.assistedRoutes, ignoreNodes, ignoreChannels ++ faultyChannel.toSet, c.randomize, c.routeParams)
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
        reply(s, PaymentFailed(c.paymentHash, failures :+ LocalFailure(t)))
        stop(FSM.Normal)
      } else {
        log.info(s"received an error message from local, trying to use a different channel (failure=${t.getMessage})")
        val faultyChannel = ChannelDesc(hops.head.lastUpdate.shortChannelId, hops.head.nodeId, hops.head.nextNodeId)
        router ! RouteRequest(sourceNodeId, c.targetNodeId, c.amountMsat, c.assistedRoutes, ignoreNodes, ignoreChannels + faultyChannel, c.randomize, c.routeParams)
        goto(WAITING_FOR_ROUTE) using WaitingForRoute(s, c, failures :+ LocalFailure(t))
      }

  }

  whenUnhandled {
    case Event(_: TransportHandler.ReadAck, _) => stay // ignored, router replies with this when we forward a channel_update
  }

  def reply(to: ActorRef, e: PaymentResult) = {
    to ! e
    context.system.eventStream.publish(e)
  }

  initialize()
}

object PaymentLifecycle {

  def props(sourceNodeId: PublicKey, router: ActorRef, register: ActorRef) = Props(classOf[PaymentLifecycle], sourceNodeId, router, register)

  // @formatter:off
  case class ReceivePayment(amountMsat_opt: Option[MilliSatoshi], description: String, expirySeconds_opt: Option[Long] = None, extraHops: List[List[ExtraHop]] = Nil)
  /**
    * @param maxFeePct set by default to 3% as a safety measure (even if a route is found, if fee is higher than that payment won't be attempted)
    */
  case class SendPayment(amountMsat: Long,
                         paymentHash: BinaryData,
                         targetNodeId: PublicKey,
                         assistedRoutes: Seq[Seq[ExtraHop]] = Nil,
                         finalCltvExpiry: Long = Channel.MIN_CLTV_EXPIRY,
                         maxAttempts: Int = 5,
                         randomize: Option[Boolean] = None,
                         routeParams: Option[RouteParams] = None) {
    require(amountMsat > 0, s"amountMsat must be > 0")
  }
  case class CheckPayment(paymentHash: BinaryData)

  sealed trait PaymentResult
  case class PaymentSucceeded(amountMsat: Long, paymentHash: BinaryData, paymentPreimage: BinaryData, route: Seq[Hop]) extends PaymentResult // note: the amount includes fees
  sealed trait PaymentFailure
  case class LocalFailure(t: Throwable) extends PaymentFailure
  case class RemoteFailure(route: Seq[Hop], e: ErrorPacket) extends PaymentFailure
  case class UnreadableRemoteFailure(route: Seq[Hop]) extends PaymentFailure
  case class PaymentFailed(paymentHash: BinaryData, failures: Seq[PaymentFailure]) extends PaymentResult

  sealed trait Data
  case object WaitingForRequest extends Data
  case class WaitingForRoute(sender: ActorRef, c: SendPayment, failures: Seq[PaymentFailure]) extends Data
  case class WaitingForComplete(sender: ActorRef, c: SendPayment, cmd: CMD_ADD_HTLC, failures: Seq[PaymentFailure], sharedSecrets: Seq[(BinaryData, PublicKey)], ignoreNodes: Set[PublicKey], ignoreChannels: Set[ChannelDesc], hops: Seq[Hop]) extends Data

  sealed trait State
  case object WAITING_FOR_REQUEST extends State
  case object WAITING_FOR_ROUTE extends State
  case object WAITING_FOR_PAYMENT_COMPLETE extends State

  // @formatter:on


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
  def buildPayloads(finalAmountMsat: Long, finalExpiry: Long, hops: Seq[Hop]): (Long, Long, Seq[PerHopPayload]) =
    hops.reverse.foldLeft((finalAmountMsat, finalExpiry, PerHopPayload(ShortChannelId(0L), finalAmountMsat, finalExpiry) :: Nil)) {
      case ((msat, expiry, payloads), hop) =>
        val nextFee = nodeFee(hop.lastUpdate.feeBaseMsat, hop.lastUpdate.feeProportionalMillionths, msat)
        (msat + nextFee, expiry + hop.lastUpdate.cltvExpiryDelta, PerHopPayload(hop.lastUpdate.shortChannelId, msat, expiry) +: payloads)
    }

  def buildCommand(finalAmountMsat: Long, finalExpiry: Long, paymentHash: BinaryData, hops: Seq[Hop]): (CMD_ADD_HTLC, Seq[(BinaryData, PublicKey)]) = {
    val (firstAmountMsat, firstExpiry, payloads) = buildPayloads(finalAmountMsat, finalExpiry, hops.drop(1))
    val nodes = hops.map(_.nextNodeId)
    // BOLT 2 requires that associatedData == paymentHash
    val onion = buildOnion(nodes, payloads, paymentHash)
    CMD_ADD_HTLC(firstAmountMsat, paymentHash, firstExpiry, Packet.write(onion.packet), upstream_opt = None, commit = true) -> onion.sharedSecrets
  }

  /**
    * Rewrites a list of failures to retrieve the meaningful part.
    * <p>
    * If a list of failures with many elements ends up with a LocalFailure RouteNotFound, this RouteNotFound failure
    * should be removed. This last failure is irrelevant information. In such a case only the n-1 attempts were rejected
    * with a **significant reason** ; the final RouteNotFound error provides no meaningful insight.
    * <p>
    * This method should be used by the user interface to provide a non-exhaustive but more useful feedback.
    *
    * @param failures a list of payment failures for a payment
    */
  def transformForUser(failures: Seq[PaymentFailure]): Seq[PaymentFailure] = {
    failures.map {
      case LocalFailure(AddHtlcFailed(_, _, t, _, _, _)) => LocalFailure(t) // we're interested in the error which caused the add-htlc to fail
      case other => other
    } match {
      case previousFailures :+ LocalFailure(RouteNotFound) if previousFailures.nonEmpty => previousFailures
      case other => other
    }
  }

  /**
    * This method retrieves the channel update that we used when we built a route.
    *
    * It just iterates over the hops, but there are at most 20 of them.
    *
    * @param nodeId
    * @param hops
    * @return the channel update if found
    */
  def getChannelUpdateForNode(nodeId: PublicKey, hops: Seq[Hop]): Option[ChannelUpdate] = hops.find(_.nodeId == nodeId).map(_.lastUpdate)

  /**
    * This method compares channel updates, ignoring fields that don't matter, like signature or timestamp
    *
    * @param u1
    * @param u2
    * @return true if channel updates are "equal"
    */
  def areSame(u1: ChannelUpdate, u2: ChannelUpdate): Boolean =
    u1.copy(signature = BinaryData.empty, timestamp = 0) == u2.copy(signature = BinaryData.empty, timestamp = 0)

  /**
    * This allows us to detect if a bad node always answers with a new update (e.g. with a slightly different expiry or fee)
    * in order to mess with us.
    *
    * @param nodeId
    * @param failures
    * @return
    */
  def hasAlreadyFailedOnce(nodeId: PublicKey, failures: Seq[PaymentFailure]): Boolean =
    failures
      .collectFirst { case RemoteFailure(_, ErrorPacket(origin, u: Update)) if origin == nodeId => u.update }
      .isDefined
}
