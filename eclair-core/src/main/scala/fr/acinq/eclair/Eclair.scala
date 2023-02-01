/*
 * Copyright 2019 ACINQ SAS
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

package fr.acinq.eclair

import akka.actor.typed.Scheduler
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.adapter.{ClassicActorRefOps, ClassicActorSystemOps, ClassicSchedulerOps, TypedActorRefOps}
import akka.actor.{ActorRef, typed}
import akka.pattern._
import akka.util.Timeout
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, Crypto, Satoshi}
import fr.acinq.eclair.ApiTypes.ChannelNotFound
import fr.acinq.eclair.balance.CheckBalance.GlobalBalance
import fr.acinq.eclair.balance.{BalanceActor, ChannelsListener}
import fr.acinq.eclair.blockchain.OnChainWallet.OnChainBalance
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.WalletTx
import fr.acinq.eclair.blockchain.fee.{FeeratePerByte, FeeratePerKw}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.db.AuditDb.{NetworkFee, Stats}
import fr.acinq.eclair.db.{IncomingPayment, OutgoingPayment, OutgoingPaymentStatus}
import fr.acinq.eclair.io.Peer.{GetPeerInfo, PeerInfo}
import fr.acinq.eclair.io._
import fr.acinq.eclair.message.{OnionMessages, Postman}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.receive.MultiPartHandler.ReceiveStandardPayment
import fr.acinq.eclair.payment.relay.Relayer.{ChannelBalance, GetOutgoingChannels, OutgoingChannels, RelayFees}
import fr.acinq.eclair.payment.send.PaymentInitiator._
import fr.acinq.eclair.payment.send.{ClearRecipient, OfferPayment, PaymentIdentifier}
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.wire.protocol.MessageOnionCodecs.blindedRouteCodec
import fr.acinq.eclair.wire.protocol.OfferTypes.Offer
import fr.acinq.eclair.wire.protocol._
import grizzled.slf4j.Logging
import scodec.bits.ByteVector
import scodec.{Attempt, DecodeResult}

import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}

case class GetInfoResponse(version: String, nodeId: PublicKey, alias: String, color: String, features: Features[Feature], chainHash: ByteVector32, network: String, blockHeight: Int, publicAddresses: Seq[NodeAddress], onionAddress: Option[NodeAddress], instanceId: String)

case class AuditResponse(sent: Seq[PaymentSent], received: Seq[PaymentReceived], relayed: Seq[PaymentRelayed])

// @formatter:off
case class SignedMessage(nodeId: PublicKey, message: String, signature: ByteVector)
case class VerifiedMessage(valid: Boolean, publicKey: PublicKey)

case class SendOnionMessageResponsePayload(encodedReplyPath: Option[String], replyPath: Option[Sphinx.RouteBlinding.BlindedRoute], unknownTlvs: Map[String, ByteVector])
case class SendOnionMessageResponse(sent: Boolean, failureMessage: Option[String], response: Option[SendOnionMessageResponsePayload])
// @formatter:on

object SignedMessage {
  def signedBytes(message: ByteVector): ByteVector32 =
    Crypto.hash256(ByteVector("Lightning Signed Message:".getBytes(StandardCharsets.UTF_8)) ++ message)
}

object ApiTypes {
  type ChannelIdentifier = Either[ByteVector32, ShortChannelId]

  case class ChannelNotFound(identifier: ChannelIdentifier) extends IllegalArgumentException(s"channel ${identifier.fold(_.toString(), _.toString)} not found")
}

trait Eclair {

  def connect(target: Either[NodeURI, PublicKey])(implicit timeout: Timeout): Future[String]

  def disconnect(nodeId: PublicKey)(implicit timeout: Timeout): Future[String]

  def open(nodeId: PublicKey, fundingAmount: Satoshi, pushAmount_opt: Option[MilliSatoshi], channelType_opt: Option[SupportedChannelType], fundingFeeratePerByte_opt: Option[FeeratePerByte], announceChannel_opt: Option[Boolean], openTimeout_opt: Option[Timeout])(implicit timeout: Timeout): Future[ChannelOpenResponse]

  def rbfOpen(channelId: ByteVector32, targetFeerate: FeeratePerKw, lockTime_opt: Option[Long])(implicit timeout: Timeout): Future[CommandResponse[CMD_BUMP_FUNDING_FEE]]

  def close(channels: List[ApiTypes.ChannelIdentifier], scriptPubKey_opt: Option[ByteVector], closingFeerates_opt: Option[ClosingFeerates])(implicit timeout: Timeout): Future[Map[ApiTypes.ChannelIdentifier, Either[Throwable, CommandResponse[CMD_CLOSE]]]]

  def forceClose(channels: List[ApiTypes.ChannelIdentifier])(implicit timeout: Timeout): Future[Map[ApiTypes.ChannelIdentifier, Either[Throwable, CommandResponse[CMD_FORCECLOSE]]]]

  def updateRelayFee(nodes: List[PublicKey], feeBase: MilliSatoshi, feeProportionalMillionths: Long)(implicit timeout: Timeout): Future[Map[ApiTypes.ChannelIdentifier, Either[Throwable, CommandResponse[CMD_UPDATE_RELAY_FEE]]]]

  def channelsInfo(toRemoteNode_opt: Option[PublicKey])(implicit timeout: Timeout): Future[Iterable[RES_GET_CHANNEL_INFO]]

  def channelInfo(channel: ApiTypes.ChannelIdentifier)(implicit timeout: Timeout): Future[CommandResponse[CMD_GET_CHANNEL_INFO]]

  def peers()(implicit timeout: Timeout): Future[Iterable[PeerInfo]]

  def node(nodeId: PublicKey)(implicit timeout: Timeout): Future[Option[Router.PublicNode]]

  def nodes(nodeIds_opt: Option[Set[PublicKey]] = None)(implicit timeout: Timeout): Future[Iterable[NodeAnnouncement]]

  def receive(description: Either[String, ByteVector32], amount_opt: Option[MilliSatoshi], expire_opt: Option[Long], fallbackAddress_opt: Option[String], paymentPreimage_opt: Option[ByteVector32])(implicit timeout: Timeout): Future[Bolt11Invoice]

  def newAddress(): Future[String]

  def receivedInfo(paymentHash: ByteVector32)(implicit timeout: Timeout): Future[Option[IncomingPayment]]

  def send(externalId_opt: Option[String], amount: MilliSatoshi, invoice: Bolt11Invoice, maxAttempts_opt: Option[Int] = None, maxFeeFlat_opt: Option[Satoshi] = None, maxFeePct_opt: Option[Double] = None, pathFindingExperimentName_opt: Option[String] = None)(implicit timeout: Timeout): Future[UUID]

  def sendBlocking(externalId_opt: Option[String], amount: MilliSatoshi, invoice: Bolt11Invoice, maxAttempts_opt: Option[Int] = None, maxFeeFlat_opt: Option[Satoshi] = None, maxFeePct_opt: Option[Double] = None, pathFindingExperimentName_opt: Option[String] = None)(implicit timeout: Timeout): Future[PaymentEvent]

  def sendWithPreimage(externalId_opt: Option[String], recipientNodeId: PublicKey, amount: MilliSatoshi, paymentPreimage: ByteVector32 = randomBytes32(), maxAttempts_opt: Option[Int] = None, maxFeeFlat_opt: Option[Satoshi] = None, maxFeePct_opt: Option[Double] = None, pathFindingExperimentName_opt: Option[String] = None)(implicit timeout: Timeout): Future[UUID]

  def sentInfo(id: PaymentIdentifier)(implicit timeout: Timeout): Future[Seq[OutgoingPayment]]

  def sendOnChain(address: String, amount: Satoshi, confirmationTarget: Long): Future[ByteVector32]

  def findRoute(targetNodeId: PublicKey, amount: MilliSatoshi, pathFindingExperimentName_opt: Option[String], extraEdges: Seq[Invoice.ExtraEdge] = Seq.empty, includeLocalChannelCost: Boolean = false, ignoreNodeIds: Seq[PublicKey] = Seq.empty, ignoreShortChannelIds: Seq[ShortChannelId] = Seq.empty, maxFee_opt: Option[MilliSatoshi] = None)(implicit timeout: Timeout): Future[RouteResponse]

  def findRouteBetween(sourceNodeId: PublicKey, targetNodeId: PublicKey, amount: MilliSatoshi, pathFindingExperimentName_opt: Option[String], extraEdges: Seq[Invoice.ExtraEdge] = Seq.empty, includeLocalChannelCost: Boolean = false, ignoreNodeIds: Seq[PublicKey] = Seq.empty, ignoreShortChannelIds: Seq[ShortChannelId] = Seq.empty, maxFee_opt: Option[MilliSatoshi] = None)(implicit timeout: Timeout): Future[RouteResponse]

  def sendToRoute(recipientAmount_opt: Option[MilliSatoshi], externalId_opt: Option[String], parentId_opt: Option[UUID], invoice: Bolt11Invoice, route: PredefinedRoute, trampolineSecret_opt: Option[ByteVector32] = None, trampolineFees_opt: Option[MilliSatoshi] = None, trampolineExpiryDelta_opt: Option[CltvExpiryDelta] = None)(implicit timeout: Timeout): Future[SendPaymentToRouteResponse]

  def audit(from: TimestampSecond, to: TimestampSecond, paginated_opt: Option[Paginated])(implicit timeout: Timeout): Future[AuditResponse]

  def networkFees(from: TimestampSecond, to: TimestampSecond)(implicit timeout: Timeout): Future[Seq[NetworkFee]]

  def channelStats(from: TimestampSecond, to: TimestampSecond)(implicit timeout: Timeout): Future[Seq[Stats]]

  def getInvoice(paymentHash: ByteVector32)(implicit timeout: Timeout): Future[Option[Invoice]]

  def pendingInvoices(from: TimestampSecond, to: TimestampSecond, paginated_opt: Option[Paginated])(implicit timeout: Timeout): Future[Seq[Invoice]]

  def allInvoices(from: TimestampSecond, to: TimestampSecond, paginated_opt: Option[Paginated])(implicit timeout: Timeout): Future[Seq[Invoice]]

  def deleteInvoice(paymentHash: ByteVector32): Future[String]

  def allChannels()(implicit timeout: Timeout): Future[Iterable[ChannelDesc]]

  def allUpdates(nodeId_opt: Option[PublicKey])(implicit timeout: Timeout): Future[Iterable[ChannelUpdate]]

  def getInfo()(implicit timeout: Timeout): Future[GetInfoResponse]

  def usableBalances()(implicit timeout: Timeout): Future[Iterable[ChannelBalance]]

  def channelBalances()(implicit timeout: Timeout): Future[Iterable[ChannelBalance]]

  def onChainBalance(): Future[OnChainBalance]

  def onChainTransactions(count: Int, skip: Int): Future[Iterable[WalletTx]]

  def globalBalance()(implicit timeout: Timeout): Future[GlobalBalance]

  def signMessage(message: ByteVector): SignedMessage

  def verifyMessage(message: ByteVector, recoverableSignature: ByteVector): VerifiedMessage

  def sendOnionMessage(intermediateNodes: Seq[PublicKey], destination: Either[PublicKey, Sphinx.RouteBlinding.BlindedRoute], replyPath: Option[Seq[PublicKey]], userCustomContent: ByteVector)(implicit timeout: Timeout): Future[SendOnionMessageResponse]

  def payOffer(offer: Offer, amount: MilliSatoshi, quantity: Long, externalId_opt: Option[String] = None, maxAttempts_opt: Option[Int] = None, maxFeeFlat_opt: Option[Satoshi] = None, maxFeePct_opt: Option[Double] = None, pathFindingExperimentName_opt: Option[String] = None)(implicit timeout: Timeout): Future[UUID]

  def payOfferBlocking(offer: Offer, amount: MilliSatoshi, quantity: Long, externalId_opt: Option[String] = None, maxAttempts_opt: Option[Int] = None, maxFeeFlat_opt: Option[Satoshi] = None, maxFeePct_opt: Option[Double] = None, pathFindingExperimentName_opt: Option[String] = None)(implicit timeout: Timeout): Future[PaymentEvent]

  def stop(): Future[Unit]
}

class EclairImpl(appKit: Kit) extends Eclair with Logging {

  implicit val ec: ExecutionContext = appKit.system.dispatcher
  implicit val scheduler: Scheduler = appKit.system.scheduler.toTyped

  // We constrain external identifiers. This allows uuid, long and pubkey to be used.
  private val externalIdMaxLength = 66

  override def connect(target: Either[NodeURI, PublicKey])(implicit timeout: Timeout): Future[String] = target match {
    case Left(uri) => (appKit.switchboard ? Peer.Connect(uri, ActorRef.noSender, isPersistent = true)).mapTo[PeerConnection.ConnectionResult].map(_.toString)
    case Right(pubKey) => (appKit.switchboard ? Peer.Connect(pubKey, None, ActorRef.noSender, isPersistent = true)).mapTo[PeerConnection.ConnectionResult].map(_.toString)
  }

  override def disconnect(nodeId: PublicKey)(implicit timeout: Timeout): Future[String] = {
    (appKit.switchboard ? Peer.Disconnect(nodeId)).mapTo[String]
  }

  override def open(nodeId: PublicKey, fundingAmount: Satoshi, pushAmount_opt: Option[MilliSatoshi], channelType_opt: Option[SupportedChannelType], fundingFeeratePerByte_opt: Option[FeeratePerByte], announceChannel_opt: Option[Boolean], openTimeout_opt: Option[Timeout])(implicit timeout: Timeout): Future[ChannelOpenResponse] = {
    // we want the open timeout to expire *before* the default ask timeout, otherwise user will get a generic response
    val openTimeout = openTimeout_opt.getOrElse(Timeout(20 seconds))
    for {
      _ <- Future.successful(0)
      open = Peer.OpenChannel(
        remoteNodeId = nodeId,
        fundingAmount = fundingAmount,
        channelType_opt = channelType_opt,
        pushAmount_opt = pushAmount_opt,
        fundingTxFeerate_opt = fundingFeeratePerByte_opt.map(FeeratePerKw(_)),
        channelFlags_opt = announceChannel_opt.map(announceChannel => ChannelFlags(announceChannel = announceChannel)),
        timeout_opt = Some(openTimeout))
      res <- (appKit.switchboard ? open).mapTo[ChannelOpenResponse]
    } yield res
  }

  override def rbfOpen(channelId: ByteVector32, targetFeerate: FeeratePerKw, lockTime_opt: Option[Long])(implicit timeout: Timeout): Future[CommandResponse[CMD_BUMP_FUNDING_FEE]] = {
    val cmd = CMD_BUMP_FUNDING_FEE(ActorRef.noSender, targetFeerate, lockTime_opt.getOrElse(appKit.nodeParams.currentBlockHeight.toLong))
    sendToChannel(Left(channelId), cmd)
  }

  override def close(channels: List[ApiTypes.ChannelIdentifier], scriptPubKey_opt: Option[ByteVector], closingFeerates_opt: Option[ClosingFeerates])(implicit timeout: Timeout): Future[Map[ApiTypes.ChannelIdentifier, Either[Throwable, CommandResponse[CMD_CLOSE]]]] = {
    sendToChannels(channels, CMD_CLOSE(ActorRef.noSender, scriptPubKey_opt, closingFeerates_opt))
  }

  override def forceClose(channels: List[ApiTypes.ChannelIdentifier])(implicit timeout: Timeout): Future[Map[ApiTypes.ChannelIdentifier, Either[Throwable, CommandResponse[CMD_FORCECLOSE]]]] = {
    sendToChannels(channels, CMD_FORCECLOSE(ActorRef.noSender))
  }

  override def updateRelayFee(nodes: List[PublicKey], feeBaseMsat: MilliSatoshi, feeProportionalMillionths: Long)(implicit timeout: Timeout): Future[Map[ApiTypes.ChannelIdentifier, Either[Throwable, CommandResponse[CMD_UPDATE_RELAY_FEE]]]] = {
    for (nodeId <- nodes) {
      appKit.nodeParams.db.peers.addOrUpdateRelayFees(nodeId, RelayFees(feeBaseMsat, feeProportionalMillionths))
    }
    sendToNodes(nodes, CMD_UPDATE_RELAY_FEE(ActorRef.noSender, feeBaseMsat, feeProportionalMillionths, cltvExpiryDelta_opt = None))
  }

  override def peers()(implicit timeout: Timeout): Future[Iterable[PeerInfo]] = for {
    peers <- (appKit.switchboard ? Switchboard.GetPeers).mapTo[Iterable[ActorRef]]
    peerinfos <- Future.sequence(peers.map(peer => (peer ? GetPeerInfo(None)).mapTo[PeerInfo]))
  } yield peerinfos

  override def node(nodeId: PublicKey)(implicit timeout: Timeout): Future[Option[Router.PublicNode]] = {
    appKit.router.toTyped.ask(ref => Router.GetNode(ref, nodeId)).map {
      case n: PublicNode => Some(n)
      case _: UnknownNode => None
    }
  }

  override def nodes(nodeIds_opt: Option[Set[PublicKey]])(implicit timeout: Timeout): Future[Iterable[NodeAnnouncement]] = {
    (appKit.router ? Router.GetNodes)
      .mapTo[Iterable[NodeAnnouncement]]
      .map(_.filter(n => nodeIds_opt.forall(_.contains(n.nodeId))))
  }

  override def channelsInfo(toRemoteNode_opt: Option[PublicKey])(implicit timeout: Timeout): Future[Iterable[RES_GET_CHANNEL_INFO]] = {
    val futureResponse = toRemoteNode_opt match {
      case Some(pk) => (appKit.register ? Symbol("channelsTo")).mapTo[Map[ByteVector32, PublicKey]].map(_.filter(_._2 == pk).keys)
      case None => (appKit.register ? Symbol("channels")).mapTo[Map[ByteVector32, ActorRef]].map(_.keys)
    }

    for {
      channelIds <- futureResponse
      channels <- Future.sequence(channelIds.map(channelId => sendToChannel[CMD_GET_CHANNEL_INFO, CommandResponse[CMD_GET_CHANNEL_INFO]](Left(channelId), CMD_GET_CHANNEL_INFO(ActorRef.noSender))))
    } yield channels.collect {
      case properResponse: RES_GET_CHANNEL_INFO => properResponse
    }
  }

  override def channelInfo(channel: ApiTypes.ChannelIdentifier)(implicit timeout: Timeout): Future[CommandResponse[CMD_GET_CHANNEL_INFO]] = {
    sendToChannel[CMD_GET_CHANNEL_INFO, CommandResponse[CMD_GET_CHANNEL_INFO]](channel, CMD_GET_CHANNEL_INFO(ActorRef.noSender))
  }

  override def allChannels()(implicit timeout: Timeout): Future[Iterable[ChannelDesc]] = {
    (appKit.router ? Router.GetChannels).mapTo[Iterable[ChannelAnnouncement]].map(_.map(c => ChannelDesc(c.shortChannelId, c.nodeId1, c.nodeId2)))
  }

  override def allUpdates(nodeId_opt: Option[PublicKey])(implicit timeout: Timeout): Future[Iterable[ChannelUpdate]] = nodeId_opt match {
    case None => (appKit.router ? Router.GetChannelUpdates).mapTo[Iterable[ChannelUpdate]]
    case Some(pk) => (appKit.router ? Router.GetChannelsMap).mapTo[Map[ShortChannelId, PublicChannel]].map { channels =>
      channels.values.flatMap {
        case PublicChannel(ann, _, _, Some(u1), _, _) if ann.nodeId1 == pk && u1.channelFlags.isNode1 => List(u1)
        case PublicChannel(ann, _, _, _, Some(u2), _) if ann.nodeId2 == pk && !u2.channelFlags.isNode1 => List(u2)
        case _: PublicChannel => List.empty
      }
    }
  }

  override def receive(description: Either[String, ByteVector32], amount_opt: Option[MilliSatoshi], expire_opt: Option[Long], fallbackAddress_opt: Option[String], paymentPreimage_opt: Option[ByteVector32])(implicit timeout: Timeout): Future[Bolt11Invoice] = {
    fallbackAddress_opt.map { fa => fr.acinq.eclair.addressToPublicKeyScript(fa, appKit.nodeParams.chainHash) } // if it's not a bitcoin address throws an exception
    (appKit.paymentHandler ? ReceiveStandardPayment(amount_opt, description, expire_opt, fallbackAddress_opt = fallbackAddress_opt, paymentPreimage_opt = paymentPreimage_opt)).mapTo[Bolt11Invoice]
  }

  override def newAddress(): Future[String] = {
    appKit.wallet match {
      case w: BitcoinCoreClient => w.getReceiveAddress()
      case _ => Future.failed(new IllegalArgumentException("this call is only available with a bitcoin core backend"))
    }
  }

  override def onChainBalance(): Future[OnChainBalance] = {
    appKit.wallet match {
      case w: BitcoinCoreClient => w.onChainBalance()
      case _ => Future.failed(new IllegalArgumentException("this call is only available with a bitcoin core backend"))
    }
  }

  override def onChainTransactions(count: Int, skip: Int): Future[Iterable[WalletTx]] = {
    appKit.wallet match {
      case w: BitcoinCoreClient => w.listTransactions(count, skip)
      case _ => Future.failed(new IllegalArgumentException("this call is only available with a bitcoin core backend"))
    }
  }

  override def sendOnChain(address: String, amount: Satoshi, confirmationTarget: Long): Future[ByteVector32] = {
    appKit.wallet match {
      case w: BitcoinCoreClient => w.sendToAddress(address, amount, confirmationTarget)
      case _ => Future.failed(new IllegalArgumentException("this call is only available with a bitcoin core backend"))
    }
  }

  override def findRoute(targetNodeId: PublicKey, amount: MilliSatoshi, pathFindingExperimentName_opt: Option[String], extraEdges: Seq[Invoice.ExtraEdge] = Seq.empty, includeLocalChannelCost: Boolean = false, ignoreNodeIds: Seq[PublicKey] = Seq.empty, ignoreShortChannelIds: Seq[ShortChannelId] = Seq.empty, maxFee_opt: Option[MilliSatoshi] = None)(implicit timeout: Timeout): Future[RouteResponse] =
    findRouteBetween(appKit.nodeParams.nodeId, targetNodeId, amount, pathFindingExperimentName_opt, extraEdges, includeLocalChannelCost, ignoreNodeIds, ignoreShortChannelIds, maxFee_opt)

  private def getRouteParams(pathFindingExperimentName_opt: Option[String]): Either[IllegalArgumentException, RouteParams] = {
    pathFindingExperimentName_opt match {
      case None => Right(appKit.nodeParams.routerConf.pathFindingExperimentConf.getRandomConf().getDefaultRouteParams)
      case Some(name) => appKit.nodeParams.routerConf.pathFindingExperimentConf.getByName(name) match {
        case Some(conf) => Right(conf.getDefaultRouteParams)
        case None => Left(new IllegalArgumentException(s"Path-finding experiment ${pathFindingExperimentName_opt.get} does not exist."))
      }
    }
  }

  override def findRouteBetween(sourceNodeId: PublicKey, targetNodeId: PublicKey, amount: MilliSatoshi, pathFindingExperimentName_opt: Option[String], extraEdges: Seq[Invoice.ExtraEdge] = Seq.empty, includeLocalChannelCost: Boolean = false, ignoreNodeIds: Seq[PublicKey] = Seq.empty, ignoreShortChannelIds: Seq[ShortChannelId] = Seq.empty, maxFee_opt: Option[MilliSatoshi] = None)(implicit timeout: Timeout): Future[RouteResponse] = {
    getRouteParams(pathFindingExperimentName_opt) match {
      case Right(routeParams) =>
        val target = ClearRecipient(targetNodeId, Features.empty, amount, CltvExpiry(appKit.nodeParams.currentBlockHeight), ByteVector32.Zeroes, extraEdges)
        val routeParams1 = routeParams.copy(
          includeLocalChannelCost = includeLocalChannelCost,
          boundaries = routeParams.boundaries.copy(
            maxFeeFlat = maxFee_opt.getOrElse(routeParams.boundaries.maxFeeFlat),
            maxFeeProportional = maxFee_opt.map(_ => 0.0).getOrElse(routeParams.boundaries.maxFeeProportional)
          )
        )
        for {
          ignoredChannels <- getChannelDescs(ignoreShortChannelIds.toSet)
          ignore = Ignore(ignoreNodeIds.toSet, ignoredChannels)
          response <- (appKit.router ? RouteRequest(sourceNodeId, target, routeParams1, ignore)).mapTo[RouteResponse]
        } yield response
      case Left(t) => Future.failed(t)
    }
  }

  override def sendToRoute(recipientAmount_opt: Option[MilliSatoshi], externalId_opt: Option[String], parentId_opt: Option[UUID], invoice: Bolt11Invoice, route: PredefinedRoute, trampolineSecret_opt: Option[ByteVector32], trampolineFees_opt: Option[MilliSatoshi], trampolineExpiryDelta_opt: Option[CltvExpiryDelta])(implicit timeout: Timeout): Future[SendPaymentToRouteResponse] = {
    if (invoice.isExpired()) {
      Future.failed(new IllegalArgumentException("invoice has expired"))
    } else if (route.isEmpty) {
      Future.failed(new IllegalArgumentException("missing payment route"))
    } else if (externalId_opt.exists(_.length > externalIdMaxLength)) {
      Future.failed(new IllegalArgumentException(s"externalId is too long: cannot exceed $externalIdMaxLength characters"))
    } else if (trampolineFees_opt.nonEmpty && trampolineExpiryDelta_opt.isEmpty) {
      Future.failed(new IllegalArgumentException("trampoline payments must specify a trampoline fee and cltv delta"))
    } else {
      val recipientAmount = recipientAmount_opt.getOrElse(invoice.amount_opt.getOrElse(route.amount))
      val trampoline_opt = trampolineFees_opt.map(fees => TrampolineAttempt(trampolineSecret_opt.getOrElse(randomBytes32()), fees, trampolineExpiryDelta_opt.get))
      val sendPayment = SendPaymentToRoute(recipientAmount, invoice, route, externalId_opt, parentId_opt, trampoline_opt)
      (appKit.paymentInitiator ? sendPayment).mapTo[SendPaymentToRouteResponse]
    }
  }

  private def createSendPaymentRequest(externalId_opt: Option[String], amount: MilliSatoshi, invoice: Bolt11Invoice, maxAttempts_opt: Option[Int], maxFeeFlat_opt: Option[Satoshi], maxFeePct_opt: Option[Double], pathFindingExperimentName_opt: Option[String]): Either[IllegalArgumentException, SendPaymentToNode] = {
    val maxAttempts = maxAttempts_opt.getOrElse(appKit.nodeParams.maxPaymentAttempts)
    getRouteParams(pathFindingExperimentName_opt) match {
      case Right(defaultRouteParams) =>
        val routeParams = defaultRouteParams
          .modify(_.boundaries.maxFeeProportional).setToIfDefined(maxFeePct_opt.map(_ / 100))
          .modify(_.boundaries.maxFeeFlat).setToIfDefined(maxFeeFlat_opt.map(_.toMilliSatoshi))
        externalId_opt match {
          case Some(externalId) if externalId.length > externalIdMaxLength => Left(new IllegalArgumentException(s"externalId is too long: cannot exceed $externalIdMaxLength characters"))
          case _ if invoice.isExpired() => Left(new IllegalArgumentException("invoice has expired"))
          case _ => Right(SendPaymentToNode(ActorRef.noSender, amount, invoice, maxAttempts, externalId_opt, routeParams = routeParams))
        }
      case Left(t) => Left(t)
    }
  }

  override def send(externalId_opt: Option[String], amount: MilliSatoshi, invoice: Bolt11Invoice, maxAttempts_opt: Option[Int], maxFeeFlat_opt: Option[Satoshi], maxFeePct_opt: Option[Double], pathFindingExperimentName_opt: Option[String])(implicit timeout: Timeout): Future[UUID] = {
    createSendPaymentRequest(externalId_opt, amount, invoice, maxAttempts_opt, maxFeeFlat_opt, maxFeePct_opt, pathFindingExperimentName_opt) match {
      case Left(ex) => Future.failed(ex)
      case Right(req) => (appKit.paymentInitiator ? req).mapTo[UUID]
    }
  }

  override def sendBlocking(externalId_opt: Option[String], amount: MilliSatoshi, invoice: Bolt11Invoice, maxAttempts_opt: Option[Int], maxFeeFlat_opt: Option[Satoshi], maxFeePct_opt: Option[Double], pathFindingExperimentName_opt: Option[String])(implicit timeout: Timeout): Future[PaymentEvent] = {
    createSendPaymentRequest(externalId_opt, amount, invoice, maxAttempts_opt, maxFeeFlat_opt, maxFeePct_opt, pathFindingExperimentName_opt) match {
      case Left(ex) => Future.failed(ex)
      case Right(req) => (appKit.paymentInitiator ? req.copy(blockUntilComplete = true)).mapTo[PaymentEvent]
    }
  }

  override def sendWithPreimage(externalId_opt: Option[String], recipientNodeId: PublicKey, amount: MilliSatoshi, paymentPreimage: ByteVector32, maxAttempts_opt: Option[Int], maxFeeFlat_opt: Option[Satoshi], maxFeePct_opt: Option[Double], pathFindingExperimentName_opt: Option[String])(implicit timeout: Timeout): Future[UUID] = {
    val maxAttempts = maxAttempts_opt.getOrElse(appKit.nodeParams.maxPaymentAttempts)
    getRouteParams(pathFindingExperimentName_opt) match {
      case Right(defaultRouteParams) =>
        val routeParams = defaultRouteParams
          .modify(_.boundaries.maxFeeProportional).setToIfDefined(maxFeePct_opt.map(_ / 100))
          .modify(_.boundaries.maxFeeFlat).setToIfDefined(maxFeeFlat_opt.map(_.toMilliSatoshi))
        val sendPayment = SendSpontaneousPayment(amount, recipientNodeId, paymentPreimage, maxAttempts, externalId_opt, routeParams)
        (appKit.paymentInitiator ? sendPayment).mapTo[UUID]
      case Left(t) => Future.failed(t)
    }
  }

  override def sentInfo(id: PaymentIdentifier)(implicit timeout: Timeout): Future[Seq[OutgoingPayment]] = {
    Future {
      id match {
        case PaymentIdentifier.PaymentUUID(uuid) => appKit.nodeParams.db.payments.listOutgoingPayments(uuid)
        case PaymentIdentifier.PaymentHash(paymentHash) => appKit.nodeParams.db.payments.listOutgoingPayments(paymentHash)
        case PaymentIdentifier.OfferId(offerId) => appKit.nodeParams.db.payments.listOutgoingPaymentsToOffer(offerId)
      }
    }.flatMap(outgoingDbPayments => {
      if (!outgoingDbPayments.exists(_.status == OutgoingPaymentStatus.Pending)) {
        // We don't have any pending payment in the DB, but we may have just started a payment that hasn't written to the DB yet.
        // We ask the payment initiator and if that's the case, we build a dummy payment placeholder to let the caller know
        // that a payment attempt is in progress (even though we don't have information yet about the actual HTLCs).
        (appKit.paymentInitiator ? GetPayment(id)).mapTo[GetPaymentResponse].map {
          case NoPendingPayment(_) => outgoingDbPayments
          case PaymentIsPending(paymentId, paymentHash, pending) =>
            val paymentType = "placeholder"
            val dummyOutgoingPayment = pending match {
              case PendingSpontaneousPayment(_, r) => OutgoingPayment(paymentId, paymentId, r.externalId, paymentHash, paymentType, r.recipientAmount, r.recipientAmount, r.recipientNodeId, TimestampMilli.now(), None, None, OutgoingPaymentStatus.Pending)
              case PendingPaymentToNode(_, r) => OutgoingPayment(paymentId, paymentId, r.externalId, paymentHash, paymentType, r.recipientAmount, r.recipientAmount, r.recipientNodeId, TimestampMilli.now(), Some(r.invoice), r.payerKey_opt, OutgoingPaymentStatus.Pending)
              case PendingPaymentToRoute(_, r) => OutgoingPayment(paymentId, paymentId, r.externalId, paymentHash, paymentType, r.recipientAmount, r.recipientAmount, r.recipientNodeId, TimestampMilli.now(), Some(r.invoice), None, OutgoingPaymentStatus.Pending)
              case PendingTrampolinePayment(_, _, r) => OutgoingPayment(paymentId, paymentId, None, paymentHash, paymentType, r.recipientAmount, r.recipientAmount, r.recipientNodeId, TimestampMilli.now(), Some(r.invoice), None, OutgoingPaymentStatus.Pending)
            }
            dummyOutgoingPayment +: outgoingDbPayments
        }
      } else {
        Future.successful(outgoingDbPayments)
      }
    })
  }

  override def receivedInfo(paymentHash: ByteVector32)(implicit timeout: Timeout): Future[Option[IncomingPayment]] = Future {
    appKit.nodeParams.db.payments.getIncomingPayment(paymentHash)
  }

  override def audit(from: TimestampSecond, to: TimestampSecond, paginated_opt: Option[Paginated])(implicit timeout: Timeout): Future[AuditResponse] = {
    Future(AuditResponse(
      sent = appKit.nodeParams.db.audit.listSent(from.toTimestampMilli, to.toTimestampMilli, paginated_opt),
      received = appKit.nodeParams.db.audit.listReceived(from.toTimestampMilli, to.toTimestampMilli, paginated_opt),
      relayed = appKit.nodeParams.db.audit.listRelayed(from.toTimestampMilli, to.toTimestampMilli, paginated_opt)
    ))
  }

  override def networkFees(from: TimestampSecond, to: TimestampSecond)(implicit timeout: Timeout): Future[Seq[NetworkFee]] = {
    Future(appKit.nodeParams.db.audit.listNetworkFees(from.toTimestampMilli, to.toTimestampMilli))
  }

  override def channelStats(from: TimestampSecond, to: TimestampSecond)(implicit timeout: Timeout): Future[Seq[Stats]] = {
    Future(appKit.nodeParams.db.audit.stats(from.toTimestampMilli, to.toTimestampMilli))
  }

  override def allInvoices(from: TimestampSecond, to: TimestampSecond, paginated_opt: Option[Paginated])(implicit timeout: Timeout): Future[Seq[Invoice]] = Future {
    appKit.nodeParams.db.payments.listIncomingPayments(from.toTimestampMilli, to.toTimestampMilli, paginated_opt).map(_.invoice)
  }

  override def pendingInvoices(from: TimestampSecond, to: TimestampSecond, paginated_opt: Option[Paginated])(implicit timeout: Timeout): Future[Seq[Invoice]] = Future {
    appKit.nodeParams.db.payments.listPendingIncomingPayments(from.toTimestampMilli, to.toTimestampMilli, paginated_opt).map(_.invoice)
  }

  override def getInvoice(paymentHash: ByteVector32)(implicit timeout: Timeout): Future[Option[Invoice]] = Future {
    appKit.nodeParams.db.payments.getIncomingPayment(paymentHash).map(_.invoice)
  }

  override def deleteInvoice(paymentHash: ByteVector32): Future[String] = {
    Future.fromTry(appKit.nodeParams.db.payments.removeIncomingPayment(paymentHash).map(_ => s"deleted invoice $paymentHash"))
  }

  /**
   * Send a request to a channel and expect a response.
   *
   * @param channel either a shortChannelId (BOLT encoded) or a channelId (32-byte hex encoded).
   */
  private def sendToChannel[C <: Command, R <: CommandResponse[C]](channel: ApiTypes.ChannelIdentifier, request: C)(implicit timeout: Timeout): Future[R] = (channel match {
    case Left(channelId) => appKit.register ? Register.Forward(null, channelId, request)
    case Right(shortChannelId) => appKit.register ? Register.ForwardShortId(null, shortChannelId, request)
  }).map {
    case t: R@unchecked => t
    case t: Register.ForwardFailure[C]@unchecked => throw ChannelNotFound(Left(t.fwd.channelId))
    case t: Register.ForwardShortIdFailure[C]@unchecked => throw ChannelNotFound(Right(t.fwd.shortChannelId))
  }

  /**
   * Send a request to multiple channels and expect responses.
   *
   * @param channels either shortChannelIds (BOLT encoded) or channelIds (32-byte hex encoded).
   */
  private def sendToChannels[C <: Command, R <: CommandResponse[C]](channels: List[ApiTypes.ChannelIdentifier], request: C)(implicit timeout: Timeout): Future[Map[ApiTypes.ChannelIdentifier, Either[Throwable, R]]] = {
    val commands = channels.map(c => sendToChannel[C, R](c, request).map(r => Right(r)).recover(t => Left(t)).map(r => c -> r))
    Future.foldLeft(commands)(Map.empty[ApiTypes.ChannelIdentifier, Either[Throwable, R]])(_ + _)
  }

  /** Send a request to multiple channels using node ids */
  private def sendToNodes[C <: Command, R <: CommandResponse[C]](nodeids: List[PublicKey], request: C)(implicit timeout: Timeout): Future[Map[ApiTypes.ChannelIdentifier, Either[Throwable, R]]] = {
    for {
      channelIds <- (appKit.register ? Symbol("channelsTo")).mapTo[Map[ByteVector32, PublicKey]].map(_.filter(kv => nodeids.contains(kv._2)).keys)
      res <- sendToChannels[C, R](channelIds.map(Left(_)).toList, request)
    } yield res
  }

  override def getInfo()(implicit timeout: Timeout): Future[GetInfoResponse] = Future.successful(
    GetInfoResponse(
      version = Kit.getVersionLong,
      color = appKit.nodeParams.color.toString,
      features = appKit.nodeParams.features,
      nodeId = appKit.nodeParams.nodeId,
      alias = appKit.nodeParams.alias,
      chainHash = appKit.nodeParams.chainHash,
      network = NodeParams.chainFromHash(appKit.nodeParams.chainHash),
      blockHeight = appKit.nodeParams.currentBlockHeight.toInt,
      publicAddresses = appKit.nodeParams.publicAddresses,
      onionAddress = appKit.nodeParams.torAddress_opt,
      instanceId = appKit.nodeParams.instanceId.toString)
  )

  override def usableBalances()(implicit timeout: Timeout): Future[Iterable[ChannelBalance]] =
    (appKit.relayer ? GetOutgoingChannels()).mapTo[OutgoingChannels].map(_.channels.map(_.toChannelBalance))

  override def channelBalances()(implicit timeout: Timeout): Future[Iterable[ChannelBalance]] =
    (appKit.relayer ? GetOutgoingChannels(enabledOnly = false)).mapTo[OutgoingChannels].map(_.channels.map(_.toChannelBalance))

  override def globalBalance()(implicit timeout: Timeout): Future[GlobalBalance] = {
    for {
      ChannelsListener.GetChannelsResponse(channels) <- appKit.channelsListener.ask(ref => ChannelsListener.GetChannels(ref))
      globalBalance_try <- appKit.balanceActor.ask(res => BalanceActor.GetGlobalBalance(res, channels))
      globalBalance <- Promise[GlobalBalance]().complete(globalBalance_try).future
    } yield globalBalance
  }

  override def signMessage(message: ByteVector): SignedMessage = {
    val bytesToSign = SignedMessage.signedBytes(message)
    val (signature, recoveryId) = appKit.nodeParams.nodeKeyManager.signDigest(bytesToSign)
    SignedMessage(appKit.nodeParams.nodeId, message.toBase64, (recoveryId + 31).toByte +: signature)
  }

  override def verifyMessage(message: ByteVector, recoverableSignature: ByteVector): VerifiedMessage = {
    val signedBytes = SignedMessage.signedBytes(message)
    val signature = ByteVector64(recoverableSignature.tail)
    val recoveryId = recoverableSignature.head.toInt match {
      case lndFormat if (lndFormat - 31) >= 0 && (lndFormat - 31) <= 3 => lndFormat - 31
      case normalFormat if normalFormat >= 0 && normalFormat <= 3 => normalFormat
      case invalidFormat => throw new RuntimeException(s"invalid recid prefix $invalidFormat")
    }
    val pubKeyFromSignature = Crypto.recoverPublicKey(signature, signedBytes, recoveryId)
    VerifiedMessage(valid = true, pubKeyFromSignature)
  }

  private def getChannelDescs(shortChannelIds: Set[ShortChannelId])(implicit timeout: Timeout): Future[Set[ChannelDesc]] = {
    if (shortChannelIds.isEmpty) {
      Future.successful(Set.empty)
    } else {
      for {
        routerData <- (appKit.router ? GetRouterData).mapTo[Router.Data]
      } yield {
        shortChannelIds.flatMap(scid => routerData.resolve(scid) match {
          case Some(c) => Set(ChannelDesc(scid, c.nodeId1, c.nodeId2), ChannelDesc(scid, c.nodeId2, c.nodeId1))
          case None => Set.empty
        })
      }
    }
  }

  override def sendOnionMessage(intermediateNodes: Seq[PublicKey],
                                recipient: Either[PublicKey, Sphinx.RouteBlinding.BlindedRoute],
                                replyPath: Option[Seq[PublicKey]],
                                userCustomContent: ByteVector)(implicit timeout: Timeout): Future[SendOnionMessageResponse] = {
    if (replyPath.nonEmpty && (replyPath.get.isEmpty || replyPath.get.last != appKit.nodeParams.nodeId)) {
      return Future.failed(new Exception("Reply path must end at our node."))
    }
    TlvCodecs.tlvStream(MessageOnionCodecs.onionTlvCodec).decode(userCustomContent.bits) match {
      case Attempt.Successful(DecodeResult(userTlvs, _)) =>
        val destination = recipient match {
          case Left(key) => OnionMessages.Recipient(key, None)
          case Right(route) => OnionMessages.BlindedPath(route)
        }
        appKit.postman.ask(ref => Postman.SendMessage(intermediateNodes, destination, replyPath, userTlvs, ref, appKit.nodeParams.onionMessageConfig.timeout)).map {
          case Postman.Response(payload) =>
            val encodedReplyPath = payload.replyPath_opt.map(route => blindedRouteCodec.encode(route).require.bytes.toHex)
            SendOnionMessageResponse(sent = true, None, Some(SendOnionMessageResponsePayload(encodedReplyPath, payload.replyPath_opt, payload.records.unknown.map(tlv => tlv.tag.toString -> tlv.value).toMap)))
          case Postman.NoReply => SendOnionMessageResponse(sent = true, Some("No response"), None)
          case Postman.MessageSent => SendOnionMessageResponse(sent = true, None, None)
          case Postman.MessageFailed(failure: String) => SendOnionMessageResponse(sent = false, Some(failure), None)
        }
      case Attempt.Failure(cause) => Future.successful(SendOnionMessageResponse(sent = false, failureMessage = Some(s"the `content` field is invalid, it must contain encoded tlvs: ${cause.message}"), response = None))
    }
  }

  def payOfferInternal(offer: Offer,
                       amount: MilliSatoshi,
                       quantity: Long,
                       externalId_opt: Option[String],
                       maxAttempts_opt: Option[Int],
                       maxFeeFlat_opt: Option[Satoshi],
                       maxFeePct_opt: Option[Double],
                       pathFindingExperimentName_opt: Option[String],
                       blocking: Boolean)(implicit timeout: Timeout): Future[Any] = {
    if (externalId_opt.exists(_.length > externalIdMaxLength)) {
      return Future.failed(new IllegalArgumentException(s"externalId is too long: cannot exceed $externalIdMaxLength characters"))
    }
    val routeParams = getRouteParams(pathFindingExperimentName_opt) match {
      case Right(defaultRouteParams) =>
        defaultRouteParams
          .modify(_.boundaries.maxFeeProportional).setToIfDefined(maxFeePct_opt.map(_ / 100))
          .modify(_.boundaries.maxFeeFlat).setToIfDefined(maxFeeFlat_opt.map(_.toMilliSatoshi))
      case Left(t) => return Future.failed(t)
    }
    val sendPaymentConfig = OfferPayment.SendPaymentConfig(externalId_opt, maxAttempts_opt.getOrElse(appKit.nodeParams.maxPaymentAttempts), routeParams, blocking)
    val offerPayment = appKit.system.spawnAnonymous(OfferPayment(appKit.nodeParams, appKit.postman, appKit.paymentInitiator))
    offerPayment.ask((ref: typed.ActorRef[Any]) => OfferPayment.PayOffer(ref.toClassic, offer, amount, quantity, sendPaymentConfig)).flatMap {
      case f: OfferPayment.Failure => Future.failed(new Exception(f.toString))
      case x => Future.successful(x)
    }
  }

  override def payOffer(offer: Offer,
                        amount: MilliSatoshi,
                        quantity: Long,
                        externalId_opt: Option[String],
                        maxAttempts_opt: Option[Int],
                        maxFeeFlat_opt: Option[Satoshi],
                        maxFeePct_opt: Option[Double],
                        pathFindingExperimentName_opt: Option[String])(implicit timeout: Timeout): Future[UUID] = {
    payOfferInternal(offer, amount, quantity, externalId_opt, maxAttempts_opt, maxFeeFlat_opt, maxFeePct_opt, pathFindingExperimentName_opt, blocking = false).mapTo[UUID]
  }

  override def payOfferBlocking(offer: Offer,
                                amount: MilliSatoshi,
                                quantity: Long,
                                externalId_opt: Option[String],
                                maxAttempts_opt: Option[Int],
                                maxFeeFlat_opt: Option[Satoshi],
                                maxFeePct_opt: Option[Double],
                                pathFindingExperimentName_opt: Option[String])(implicit timeout: Timeout): Future[PaymentEvent] = {
    payOfferInternal(offer, amount, quantity, externalId_opt, maxAttempts_opt, maxFeeFlat_opt, maxFeePct_opt, pathFindingExperimentName_opt, blocking = true).mapTo[PaymentEvent]
  }

  override def stop(): Future[Unit] = {
    // README: do not make this smarter or more complex !
    // eclair can simply and cleanly be stopped by killing its process without fear of losing data, payments, ... and it should remain this way.
    logger.info("stopping eclair")
    sys.exit(0)
    Future.successful(())
  }
}
