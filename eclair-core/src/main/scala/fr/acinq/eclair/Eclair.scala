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

import akka.actor.ActorRef
import akka.pattern._
import akka.util.Timeout
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto, Satoshi}
import fr.acinq.eclair.TimestampQueryFilters._
import fr.acinq.eclair.blockchain.OnChainBalance
import fr.acinq.eclair.blockchain.bitcoind.BitcoinCoreWallet
import fr.acinq.eclair.blockchain.bitcoind.BitcoinCoreWallet.WalletTransaction
import fr.acinq.eclair.blockchain.fee.{FeeratePerByte, FeeratePerKw}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.AuditDb.{NetworkFee, Stats}
import fr.acinq.eclair.db.{IncomingPayment, OutgoingPayment}
import fr.acinq.eclair.io.Peer.{GetPeerInfo, PeerInfo}
import fr.acinq.eclair.io.{NodeURI, Peer, PeerConnection}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.receive.MultiPartHandler.ReceivePayment
import fr.acinq.eclair.payment.relay.Relayer.{GetOutgoingChannels, OutgoingChannels, UsableBalance}
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle.PreimageReceived
import fr.acinq.eclair.payment.send.PaymentInitiator.{SendPayment, SendPaymentToRoute, SendPaymentToRouteResponse, SendSpontaneousPayment}
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.router.{NetworkStats, RouteCalculation, Router}
import fr.acinq.eclair.wire.protocol._
import scodec.bits.ByteVector

import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

case class GetInfoResponse(version: String, nodeId: PublicKey, alias: String, color: String, features: Features, chainHash: ByteVector32, network: String, blockHeight: Int, publicAddresses: Seq[NodeAddress], instanceId: String)

case class AuditResponse(sent: Seq[PaymentSent], received: Seq[PaymentReceived], relayed: Seq[PaymentRelayed])

case class TimestampQueryFilters(from: Long, to: Long)

case class SignedMessage(nodeId: PublicKey, message: String, signature: ByteVector)

case class VerifiedMessage(valid: Boolean, publicKey: PublicKey)

object TimestampQueryFilters {
  /** We use this in the context of timestamp filtering, when we don't need an upper bound. */
  val MaxEpochMilliseconds = Duration.fromNanos(Long.MaxValue).toMillis

  def getDefaultTimestampFilters(from_opt: Option[Long], to_opt: Option[Long]) = {
    // NB: we expect callers to use seconds, but internally we use milli-seconds everywhere.
    val from = from_opt.getOrElse(0L).seconds.toMillis
    val to = to_opt.map(_.seconds.toMillis).getOrElse(MaxEpochMilliseconds)

    TimestampQueryFilters(from, to)
  }
}

object SignedMessage {
  def signedBytes(message: ByteVector): ByteVector32 =
    Crypto.hash256(ByteVector("Lightning Signed Message:".getBytes(StandardCharsets.UTF_8)) ++ message)
}

object ApiTypes {
  type ChannelIdentifier = Either[ByteVector32, ShortChannelId]
}

trait Eclair {

  def connect(target: Either[NodeURI, PublicKey])(implicit timeout: Timeout): Future[String]

  def disconnect(nodeId: PublicKey)(implicit timeout: Timeout): Future[String]

  def open(nodeId: PublicKey, fundingAmount: Satoshi, pushAmount_opt: Option[MilliSatoshi], channelType_opt: Option[ChannelType], fundingFeeratePerByte_opt: Option[FeeratePerByte], initialRelayFees_opt: Option[(MilliSatoshi, Int)], flags_opt: Option[Int], openTimeout_opt: Option[Timeout])(implicit timeout: Timeout): Future[ChannelOpenResponse]

  def close(channels: List[ApiTypes.ChannelIdentifier], scriptPubKey_opt: Option[ByteVector])(implicit timeout: Timeout): Future[Map[ApiTypes.ChannelIdentifier, Either[Throwable, CommandResponse[CMD_CLOSE]]]]

  def forceClose(channels: List[ApiTypes.ChannelIdentifier])(implicit timeout: Timeout): Future[Map[ApiTypes.ChannelIdentifier, Either[Throwable, CommandResponse[CMD_FORCECLOSE]]]]

  def updateRelayFee(channels: List[ApiTypes.ChannelIdentifier], feeBase: MilliSatoshi, feeProportionalMillionths: Long)(implicit timeout: Timeout): Future[Map[ApiTypes.ChannelIdentifier, Either[Throwable, CommandResponse[CMD_UPDATE_RELAY_FEE]]]]

  def channelsInfo(toRemoteNode_opt: Option[PublicKey])(implicit timeout: Timeout): Future[Iterable[RES_GETINFO]]

  def channelInfo(channel: ApiTypes.ChannelIdentifier)(implicit timeout: Timeout): Future[RES_GETINFO]

  def peers()(implicit timeout: Timeout): Future[Iterable[PeerInfo]]

  def nodes(nodeIds_opt: Option[Set[PublicKey]] = None)(implicit timeout: Timeout): Future[Iterable[NodeAnnouncement]]

  def receive(description: String, amount_opt: Option[MilliSatoshi], expire_opt: Option[Long], fallbackAddress_opt: Option[String], paymentPreimage_opt: Option[ByteVector32])(implicit timeout: Timeout): Future[PaymentRequest]

  def newAddress(): Future[String]

  def receivedInfo(paymentHash: ByteVector32)(implicit timeout: Timeout): Future[Option[IncomingPayment]]

  def send(externalId_opt: Option[String], amount: MilliSatoshi, invoice: PaymentRequest, maxAttempts_opt: Option[Int] = None, feeThresholdSat_opt: Option[Satoshi] = None, maxFeePct_opt: Option[Double] = None)(implicit timeout: Timeout): Future[UUID]

  def sendBlocking(externalId_opt: Option[String], amount: MilliSatoshi, invoice: PaymentRequest, maxAttempts_opt: Option[Int] = None, feeThresholdSat_opt: Option[Satoshi] = None, maxFeePct_opt: Option[Double] = None)(implicit timeout: Timeout): Future[Either[PreimageReceived, PaymentEvent]]

  def sendWithPreimage(externalId_opt: Option[String], recipientNodeId: PublicKey, amount: MilliSatoshi, paymentPreimage: ByteVector32 = randomBytes32(), maxAttempts_opt: Option[Int] = None, feeThresholdSat_opt: Option[Satoshi] = None, maxFeePct_opt: Option[Double] = None)(implicit timeout: Timeout): Future[UUID]

  def sentInfo(id: Either[UUID, ByteVector32])(implicit timeout: Timeout): Future[Seq[OutgoingPayment]]

  def sendOnChain(address: String, amount: Satoshi, confirmationTarget: Long): Future[ByteVector32]

  def findRoute(targetNodeId: PublicKey, amount: MilliSatoshi, assistedRoutes: Seq[Seq[PaymentRequest.ExtraHop]] = Seq.empty)(implicit timeout: Timeout): Future[RouteResponse]

  def findRouteBetween(sourceNodeId: PublicKey, targetNodeId: PublicKey, amount: MilliSatoshi, assistedRoutes: Seq[Seq[PaymentRequest.ExtraHop]] = Seq.empty)(implicit timeout: Timeout): Future[RouteResponse]

  def sendToRoute(amount: MilliSatoshi, recipientAmount_opt: Option[MilliSatoshi], externalId_opt: Option[String], parentId_opt: Option[UUID], invoice: PaymentRequest, finalCltvExpiryDelta: CltvExpiryDelta, route: PredefinedRoute, trampolineSecret_opt: Option[ByteVector32] = None, trampolineFees_opt: Option[MilliSatoshi] = None, trampolineExpiryDelta_opt: Option[CltvExpiryDelta] = None, trampolineNodes_opt: Seq[PublicKey] = Nil)(implicit timeout: Timeout): Future[SendPaymentToRouteResponse]

  def audit(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[AuditResponse]

  def networkFees(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[Seq[NetworkFee]]

  def channelStats(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[Seq[Stats]]

  def networkStats()(implicit timeout: Timeout): Future[Option[NetworkStats]]

  def getInvoice(paymentHash: ByteVector32)(implicit timeout: Timeout): Future[Option[PaymentRequest]]

  def pendingInvoices(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[Seq[PaymentRequest]]

  def allInvoices(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[Seq[PaymentRequest]]

  def allChannels()(implicit timeout: Timeout): Future[Iterable[ChannelDesc]]

  def allUpdates(nodeId_opt: Option[PublicKey])(implicit timeout: Timeout): Future[Iterable[ChannelUpdate]]

  def getInfo()(implicit timeout: Timeout): Future[GetInfoResponse]

  def usableBalances()(implicit timeout: Timeout): Future[Iterable[UsableBalance]]

  def onChainBalance(): Future[OnChainBalance]

  def onChainTransactions(count: Int, skip: Int): Future[Iterable[WalletTransaction]]

  def signMessage(message: ByteVector): SignedMessage

  def verifyMessage(message: ByteVector, recoverableSignature: ByteVector): VerifiedMessage
}

class EclairImpl(appKit: Kit) extends Eclair {

  implicit val ec: ExecutionContext = appKit.system.dispatcher

  // We constrain external identifiers. This allows uuid, long and pubkey to be used.
  private val externalIdMaxLength = 66

  override def connect(target: Either[NodeURI, PublicKey])(implicit timeout: Timeout): Future[String] = target match {
    case Left(uri) => (appKit.switchboard ? Peer.Connect(uri)).mapTo[PeerConnection.ConnectionResult].map(_.toString)
    case Right(pubKey) => (appKit.switchboard ? Peer.Connect(pubKey, None)).mapTo[PeerConnection.ConnectionResult].map(_.toString)
  }

  override def disconnect(nodeId: PublicKey)(implicit timeout: Timeout): Future[String] = {
    (appKit.switchboard ? Peer.Disconnect(nodeId)).mapTo[String]
  }

  override def open(nodeId: PublicKey, fundingAmount: Satoshi, pushAmount_opt: Option[MilliSatoshi], channelType_opt: Option[ChannelType], fundingFeeratePerByte_opt: Option[FeeratePerByte], initialRelayFees_opt: Option[(MilliSatoshi, Int)], flags_opt: Option[Int], openTimeout_opt: Option[Timeout])(implicit timeout: Timeout): Future[ChannelOpenResponse] = {
    // we want the open timeout to expire *before* the default ask timeout, otherwise user won't get a generic response
    val openTimeout = openTimeout_opt.getOrElse(Timeout(10 seconds))
    (appKit.switchboard ? Peer.OpenChannel(
      remoteNodeId = nodeId,
      fundingSatoshis = fundingAmount,
      pushMsat = pushAmount_opt.getOrElse(0 msat),
      channelType_opt = channelType_opt,
      fundingTxFeeratePerKw_opt = fundingFeeratePerByte_opt.map(FeeratePerKw(_)),
      initialRelayFees_opt = initialRelayFees_opt,
      channelFlags = flags_opt.map(_.toByte),
      timeout_opt = Some(openTimeout))).mapTo[ChannelOpenResponse]
  }

  override def close(channels: List[ApiTypes.ChannelIdentifier], scriptPubKey_opt: Option[ByteVector])(implicit timeout: Timeout): Future[Map[ApiTypes.ChannelIdentifier, Either[Throwable, CommandResponse[CMD_CLOSE]]]] = {
    sendToChannels[CommandResponse[CMD_CLOSE]](channels, CMD_CLOSE(ActorRef.noSender, scriptPubKey_opt))
  }

  override def forceClose(channels: List[ApiTypes.ChannelIdentifier])(implicit timeout: Timeout): Future[Map[ApiTypes.ChannelIdentifier, Either[Throwable, CommandResponse[CMD_FORCECLOSE]]]] = {
    sendToChannels[CommandResponse[CMD_FORCECLOSE]](channels, CMD_FORCECLOSE(ActorRef.noSender))
  }

  override def updateRelayFee(channels: List[ApiTypes.ChannelIdentifier], feeBaseMsat: MilliSatoshi, feeProportionalMillionths: Long)(implicit timeout: Timeout): Future[Map[ApiTypes.ChannelIdentifier, Either[Throwable, CommandResponse[CMD_UPDATE_RELAY_FEE]]]] = {
    sendToChannels[CommandResponse[CMD_UPDATE_RELAY_FEE]](channels, CMD_UPDATE_RELAY_FEE(ActorRef.noSender, feeBaseMsat, feeProportionalMillionths))
  }

  override def peers()(implicit timeout: Timeout): Future[Iterable[PeerInfo]] = for {
    peers <- (appKit.switchboard ? Symbol("peers")).mapTo[Iterable[ActorRef]]
    peerinfos <- Future.sequence(peers.map(peer => (peer ? GetPeerInfo).mapTo[PeerInfo]))
  } yield peerinfos

  override def nodes(nodeIds_opt: Option[Set[PublicKey]])(implicit timeout: Timeout): Future[Iterable[NodeAnnouncement]] = {
    (appKit.router ? Router.GetNodes)
      .mapTo[Iterable[NodeAnnouncement]]
      .map(_.filter(n => nodeIds_opt.forall(_.contains(n.nodeId))))
  }

  override def channelsInfo(toRemoteNode_opt: Option[PublicKey])(implicit timeout: Timeout): Future[Iterable[RES_GETINFO]] = toRemoteNode_opt match {
    case Some(pk) => for {
      channelIds <- (appKit.register ? Symbol("channelsTo")).mapTo[Map[ByteVector32, PublicKey]].map(_.filter(_._2 == pk).keys)
      channels <- Future.sequence(channelIds.map(channelId => sendToChannel[RES_GETINFO](Left(channelId), CMD_GETINFO(ActorRef.noSender))))
    } yield channels
    case None => for {
      channelIds <- (appKit.register ? Symbol("channels")).mapTo[Map[ByteVector32, ActorRef]].map(_.keys)
      channels <- Future.sequence(channelIds.map(channelId => sendToChannel[RES_GETINFO](Left(channelId), CMD_GETINFO(ActorRef.noSender))))
    } yield channels
  }

  override def channelInfo(channel: ApiTypes.ChannelIdentifier)(implicit timeout: Timeout): Future[RES_GETINFO] = {
    sendToChannel[RES_GETINFO](channel, CMD_GETINFO(ActorRef.noSender))
  }

  override def allChannels()(implicit timeout: Timeout): Future[Iterable[ChannelDesc]] = {
    (appKit.router ? Router.GetChannels).mapTo[Iterable[ChannelAnnouncement]].map(_.map(c => ChannelDesc(c.shortChannelId, c.nodeId1, c.nodeId2)))
  }

  override def allUpdates(nodeId_opt: Option[PublicKey])(implicit timeout: Timeout): Future[Iterable[ChannelUpdate]] = nodeId_opt match {
    case None => (appKit.router ? Router.GetChannelUpdates).mapTo[Iterable[ChannelUpdate]]
    case Some(pk) => (appKit.router ? Router.GetChannelsMap).mapTo[Map[ShortChannelId, PublicChannel]].map { channels =>
      channels.values.flatMap {
        case PublicChannel(ann, _, _, Some(u1), _, _) if ann.nodeId1 == pk && u1.isNode1 => List(u1)
        case PublicChannel(ann, _, _, _, Some(u2), _) if ann.nodeId2 == pk && !u2.isNode1 => List(u2)
        case _: PublicChannel => List.empty
      }
    }
  }

  override def receive(description: String, amount_opt: Option[MilliSatoshi], expire_opt: Option[Long], fallbackAddress_opt: Option[String], paymentPreimage_opt: Option[ByteVector32])(implicit timeout: Timeout): Future[PaymentRequest] = {
    fallbackAddress_opt.map { fa => fr.acinq.eclair.addressToPublicKeyScript(fa, appKit.nodeParams.chainHash) } // if it's not a bitcoin address throws an exception
    (appKit.paymentHandler ? ReceivePayment(amount_opt, description, expire_opt, fallbackAddress = fallbackAddress_opt, paymentPreimage = paymentPreimage_opt)).mapTo[PaymentRequest]
  }

  override def newAddress(): Future[String] = {
    appKit.wallet match {
      case w: BitcoinCoreWallet => w.getReceiveAddress()
      case _ => Future.failed(new IllegalArgumentException("this call is only available with a bitcoin core backend"))
    }
  }

  override def onChainBalance(): Future[OnChainBalance] = {
    appKit.wallet match {
      case w: BitcoinCoreWallet => w.getBalance
      case _ => Future.failed(new IllegalArgumentException("this call is only available with a bitcoin core backend"))
    }
  }

  override def onChainTransactions(count: Int, skip: Int): Future[Iterable[WalletTransaction]] = {
    appKit.wallet match {
      case w: BitcoinCoreWallet => w.listTransactions(count, skip)
      case _ => Future.failed(new IllegalArgumentException("this call is only available with a bitcoin core backend"))
    }
  }

  override def sendOnChain(address: String, amount: Satoshi, confirmationTarget: Long): Future[ByteVector32] = {
    appKit.wallet match {
      case w: BitcoinCoreWallet => w.sendToAddress(address, amount, confirmationTarget)
      case _ => Future.failed(new IllegalArgumentException("this call is only available with a bitcoin core backend"))
    }
  }

  override def findRoute(targetNodeId: PublicKey, amount: MilliSatoshi, assistedRoutes: Seq[Seq[PaymentRequest.ExtraHop]] = Seq.empty)(implicit timeout: Timeout): Future[RouteResponse] =
    findRouteBetween(appKit.nodeParams.nodeId, targetNodeId, amount, assistedRoutes)

  override def findRouteBetween(sourceNodeId: PublicKey, targetNodeId: PublicKey, amount: MilliSatoshi, assistedRoutes: Seq[Seq[PaymentRequest.ExtraHop]] = Seq.empty)(implicit timeout: Timeout): Future[RouteResponse] = {
    val maxFee = RouteCalculation.getDefaultRouteParams(appKit.nodeParams.routerConf).getMaxFee(amount)
    (appKit.router ? RouteRequest(sourceNodeId, targetNodeId, amount, maxFee, assistedRoutes)).mapTo[RouteResponse]
  }

  override def sendToRoute(amount: MilliSatoshi, recipientAmount_opt: Option[MilliSatoshi], externalId_opt: Option[String], parentId_opt: Option[UUID], invoice: PaymentRequest, finalCltvExpiryDelta: CltvExpiryDelta, route: PredefinedRoute, trampolineSecret_opt: Option[ByteVector32], trampolineFees_opt: Option[MilliSatoshi], trampolineExpiryDelta_opt: Option[CltvExpiryDelta], trampolineNodes_opt: Seq[PublicKey])(implicit timeout: Timeout): Future[SendPaymentToRouteResponse] = {
    val recipientAmount = recipientAmount_opt.getOrElse(invoice.amount.getOrElse(amount))
    val sendPayment = SendPaymentToRoute(amount, recipientAmount, invoice, finalCltvExpiryDelta, route, externalId_opt, parentId_opt, trampolineSecret_opt, trampolineFees_opt.getOrElse(0 msat), trampolineExpiryDelta_opt.getOrElse(CltvExpiryDelta(0)), trampolineNodes_opt)
    if (invoice.isExpired) {
      Future.failed(new IllegalArgumentException("invoice has expired"))
    } else if (route.isEmpty) {
      Future.failed(new IllegalArgumentException("missing payment route"))
    } else if (externalId_opt.exists(_.length > externalIdMaxLength)) {
      Future.failed(new IllegalArgumentException(s"externalId is too long: cannot exceed $externalIdMaxLength characters"))
    } else if (trampolineNodes_opt.nonEmpty && (trampolineFees_opt.isEmpty || trampolineExpiryDelta_opt.isEmpty)) {
      Future.failed(new IllegalArgumentException("trampoline payments must specify a trampoline fee and cltv delta"))
    } else if (trampolineNodes_opt.nonEmpty && trampolineNodes_opt.length != 2) {
      Future.failed(new IllegalArgumentException("trampoline payments currently only support paying a trampoline node via a single other trampoline node"))
    } else {
      (appKit.paymentInitiator ? sendPayment).mapTo[SendPaymentToRouteResponse]
    }
  }

  private def createPaymentRequest(externalId_opt: Option[String], amount: MilliSatoshi, invoice: PaymentRequest, maxAttempts_opt: Option[Int], feeThreshold_opt: Option[Satoshi], maxFeePct_opt: Option[Double]): Either[IllegalArgumentException, SendPayment] = {
    val maxAttempts = maxAttempts_opt.getOrElse(appKit.nodeParams.maxPaymentAttempts)
    val defaultRouteParams = RouteCalculation.getDefaultRouteParams(appKit.nodeParams.routerConf)
    val routeParams = defaultRouteParams.copy(
      maxFeePct = maxFeePct_opt.getOrElse(defaultRouteParams.maxFeePct),
      maxFeeBase = feeThreshold_opt.map(_.toMilliSatoshi).getOrElse(defaultRouteParams.maxFeeBase)
    )

    externalId_opt match {
      case Some(externalId) if externalId.length > externalIdMaxLength => Left(new IllegalArgumentException(s"externalId is too long: cannot exceed $externalIdMaxLength characters"))
      case _ if invoice.isExpired => Left(new IllegalArgumentException("invoice has expired"))
      case _ => invoice.minFinalCltvExpiryDelta match {
        case Some(minFinalCltvExpiryDelta) => Right(SendPayment(amount, invoice, maxAttempts, minFinalCltvExpiryDelta, externalId_opt, assistedRoutes = invoice.routingInfo, routeParams = Some(routeParams)))
        case None => Right(SendPayment(amount, invoice, maxAttempts, externalId = externalId_opt, assistedRoutes = invoice.routingInfo, routeParams = Some(routeParams)))
      }
    }
  }

  override def send(externalId_opt: Option[String], amount: MilliSatoshi, invoice: PaymentRequest, maxAttempts_opt: Option[Int], feeThreshold_opt: Option[Satoshi], maxFeePct_opt: Option[Double])(implicit timeout: Timeout): Future[UUID] = {
    createPaymentRequest(externalId_opt, amount, invoice, maxAttempts_opt, feeThreshold_opt, maxFeePct_opt) match {
      case Left(ex) => Future.failed(ex)
      case Right(req) => (appKit.paymentInitiator ? req).mapTo[UUID]
    }
  }

  override def sendBlocking(externalId_opt: Option[String], amount: MilliSatoshi, invoice: PaymentRequest, maxAttempts_opt: Option[Int], feeThreshold_opt: Option[Satoshi], maxFeePct_opt: Option[Double])(implicit timeout: Timeout): Future[Either[PreimageReceived, PaymentEvent]] = {
    createPaymentRequest(externalId_opt, amount, invoice, maxAttempts_opt, feeThreshold_opt, maxFeePct_opt) match {
      case Left(ex) => Future.failed(ex)
      case Right(req) => (appKit.paymentInitiator ? req.copy(blockUntilComplete = true)).map {
        case e: PreimageReceived => Left(e)
        case e: PaymentEvent => Right(e)
      }
    }
  }

  override def sendWithPreimage(externalId_opt: Option[String], recipientNodeId: PublicKey, amount: MilliSatoshi, paymentPreimage: ByteVector32, maxAttempts_opt: Option[Int], feeThreshold_opt: Option[Satoshi], maxFeePct_opt: Option[Double])(implicit timeout: Timeout): Future[UUID] = {
    val maxAttempts = maxAttempts_opt.getOrElse(appKit.nodeParams.maxPaymentAttempts)
    val defaultRouteParams = RouteCalculation.getDefaultRouteParams(appKit.nodeParams.routerConf)
    val routeParams = defaultRouteParams.copy(
      maxFeePct = maxFeePct_opt.getOrElse(defaultRouteParams.maxFeePct),
      maxFeeBase = feeThreshold_opt.map(_.toMilliSatoshi).getOrElse(defaultRouteParams.maxFeeBase)
    )
    val sendPayment = SendSpontaneousPayment(amount, recipientNodeId, paymentPreimage, maxAttempts, externalId_opt, Some(routeParams))
    (appKit.paymentInitiator ? sendPayment).mapTo[UUID]
  }

  override def sentInfo(id: Either[UUID, ByteVector32])(implicit timeout: Timeout): Future[Seq[OutgoingPayment]] = Future {
    id match {
      case Left(uuid) => appKit.nodeParams.db.payments.listOutgoingPayments(uuid)
      case Right(paymentHash) => appKit.nodeParams.db.payments.listOutgoingPayments(paymentHash)
    }
  }

  override def receivedInfo(paymentHash: ByteVector32)(implicit timeout: Timeout): Future[Option[IncomingPayment]] = Future {
    appKit.nodeParams.db.payments.getIncomingPayment(paymentHash)
  }

  override def audit(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[AuditResponse] = {
    val filter = getDefaultTimestampFilters(from_opt, to_opt)
    Future(AuditResponse(
      sent = appKit.nodeParams.db.audit.listSent(filter.from, filter.to),
      received = appKit.nodeParams.db.audit.listReceived(filter.from, filter.to),
      relayed = appKit.nodeParams.db.audit.listRelayed(filter.from, filter.to)
    ))
  }

  override def networkFees(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[Seq[NetworkFee]] = {
    val filter = getDefaultTimestampFilters(from_opt, to_opt)
    Future(appKit.nodeParams.db.audit.listNetworkFees(filter.from, filter.to))
  }

  override def channelStats(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[Seq[Stats]] = {
    val filter = getDefaultTimestampFilters(from_opt, to_opt)
    Future(appKit.nodeParams.db.audit.stats(filter.from, filter.to))
  }

  override def networkStats()(implicit timeout: Timeout): Future[Option[NetworkStats]] = (appKit.router ? GetNetworkStats).mapTo[GetNetworkStatsResponse].map(_.stats)

  override def allInvoices(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[Seq[PaymentRequest]] = Future {
    val filter = getDefaultTimestampFilters(from_opt, to_opt)
    appKit.nodeParams.db.payments.listIncomingPayments(filter.from, filter.to).map(_.paymentRequest)
  }

  override def pendingInvoices(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[Seq[PaymentRequest]] = Future {
    val filter = getDefaultTimestampFilters(from_opt, to_opt)
    appKit.nodeParams.db.payments.listPendingIncomingPayments(filter.from, filter.to).map(_.paymentRequest)
  }

  override def getInvoice(paymentHash: ByteVector32)(implicit timeout: Timeout): Future[Option[PaymentRequest]] = Future {
    appKit.nodeParams.db.payments.getIncomingPayment(paymentHash).map(_.paymentRequest)
  }

  /**
   * Send a request to a channel and expect a response.
   *
   * @param channel either a shortChannelId (BOLT encoded) or a channelId (32-byte hex encoded).
   */
  private def sendToChannel[T: ClassTag](channel: ApiTypes.ChannelIdentifier, request: Any)(implicit timeout: Timeout): Future[T] = (channel match {
    case Left(channelId) => appKit.register ? Register.Forward(ActorRef.noSender, channelId, request)
    case Right(shortChannelId) => appKit.register ? Register.ForwardShortId(ActorRef.noSender, shortChannelId, request)
  }).map {
    case t: T => t
    case t: Register.ForwardFailure[T]@unchecked => throw new RuntimeException(s"channel ${t.fwd.channelId} not found")
    case t: Register.ForwardShortIdFailure[T]@unchecked => throw new RuntimeException(s"channel ${t.fwd.shortChannelId} not found")
  }

  /**
   * Send a request to multiple channels and expect responses.
   *
   * @param channels either shortChannelIds (BOLT encoded) or channelIds (32-byte hex encoded).
   */
  private def sendToChannels[T: ClassTag](channels: List[ApiTypes.ChannelIdentifier], request: Any)(implicit timeout: Timeout): Future[Map[ApiTypes.ChannelIdentifier, Either[Throwable, T]]] = {
    val commands = channels.map(c => sendToChannel[T](c, request).map(r => Right(r)).recover(t => Left(t)).map(r => c -> r))
    Future.foldLeft(commands)(Map.empty[ApiTypes.ChannelIdentifier, Either[Throwable, T]])(_ + _)
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
      instanceId = appKit.nodeParams.instanceId.toString)
  )

  override def usableBalances()(implicit timeout: Timeout): Future[Iterable[UsableBalance]] =
    (appKit.relayer ? GetOutgoingChannels()).mapTo[OutgoingChannels].map(_.channels.map(_.toUsableBalance))

  override def signMessage(message: ByteVector): SignedMessage = {
    val bytesToSign = SignedMessage.signedBytes(message)
    val (signature, recoveryId) = appKit.nodeParams.nodeKeyManager.signDigest(bytesToSign)
    SignedMessage(appKit.nodeParams.nodeId, message.toBase64, (recoveryId + 31).toByte +: signature)
  }

  override def verifyMessage(message: ByteVector, recoverableSignature: ByteVector): VerifiedMessage = {
    val signedBytes = SignedMessage.signedBytes(message)
    val signature = ByteVector64(recoverableSignature.tail)
    val recoveryId = recoverableSignature.head.toInt - 31
    val pubKeyFromSignature = Crypto.recoverPublicKey(signature, signedBytes, recoveryId)
    VerifiedMessage(valid = true, pubKeyFromSignature)
  }
}
