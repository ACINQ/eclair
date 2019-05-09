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

import java.util.UUID
import akka.actor.ActorRef
import akka.pattern._
import akka.util.Timeout
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, MilliSatoshi, Satoshi}
import fr.acinq.eclair.channel.Register.{Forward, ForwardShortId}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.{IncomingPayment, NetworkFee, OutgoingPayment, Stats}
import fr.acinq.eclair.io.Peer.{GetPeerInfo, PeerInfo}
import fr.acinq.eclair.io.{NodeURI, Peer}
import fr.acinq.eclair.payment.PaymentLifecycle._
import fr.acinq.eclair.router.{ChannelDesc, RouteRequest, RouteResponse}
import scodec.bits.ByteVector
import scala.concurrent.Future
import scala.concurrent.duration._
import fr.acinq.eclair.payment.{PaymentReceived, PaymentRelayed, PaymentRequest, PaymentSent}
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, NodeAddress, NodeAnnouncement}

case class GetInfoResponse(nodeId: PublicKey, alias: String, chainHash: ByteVector32, blockHeight: Int, publicAddresses: Seq[NodeAddress])

case class AuditResponse(sent: Seq[PaymentSent], received: Seq[PaymentReceived], relayed: Seq[PaymentRelayed])

trait Eclair {

  def connect(uri: String)(implicit timeout: Timeout): Future[String]

  def open(nodeId: PublicKey, fundingSatoshis: Long, pushMsat: Option[Long], fundingFeerateSatByte: Option[Long], flags: Option[Int], openTimeout_opt: Option[Timeout])(implicit timeout: Timeout): Future[String]

  def close(channelIdentifier: Either[ByteVector32, ShortChannelId], scriptPubKey: Option[ByteVector])(implicit timeout: Timeout): Future[String]

  def forceClose(channelIdentifier: Either[ByteVector32, ShortChannelId])(implicit timeout: Timeout): Future[String]

  def updateRelayFee(channelIdentifier: Either[ByteVector32, ShortChannelId], feeBaseMsat: Long, feeProportionalMillionths: Long)(implicit timeout: Timeout): Future[String]

  def channelsInfo(toRemoteNode: Option[PublicKey])(implicit timeout: Timeout): Future[Iterable[RES_GETINFO]]

  def channelInfo(channelIdentifier: Either[ByteVector32, ShortChannelId])(implicit timeout: Timeout): Future[RES_GETINFO]

  def peersInfo()(implicit timeout: Timeout): Future[Iterable[PeerInfo]]

  def receive(description: String, amountMsat: Option[Long], expire: Option[Long], fallbackAddress: Option[String])(implicit timeout: Timeout): Future[PaymentRequest]

  def receivedInfo(paymentHash: ByteVector32)(implicit timeout: Timeout): Future[Option[IncomingPayment]]

  def send(recipientNodeId: PublicKey, amountMsat: Long, paymentHash: ByteVector32, assistedRoutes: Seq[Seq[PaymentRequest.ExtraHop]] = Seq.empty, minFinalCltvExpiry: Option[Long] = None, maxAttempts: Option[Int] = None)(implicit timeout: Timeout): Future[UUID]

  def sentInfo(id: Either[UUID, ByteVector32])(implicit timeout: Timeout): Future[Seq[OutgoingPayment]]

  def findRoute(targetNodeId: PublicKey, amountMsat: Long, assistedRoutes: Seq[Seq[PaymentRequest.ExtraHop]] = Seq.empty)(implicit timeout: Timeout): Future[RouteResponse]

  def audit(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[AuditResponse]

  def networkFees(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[Seq[NetworkFee]]

  def channelStats()(implicit timeout: Timeout): Future[Seq[Stats]]

  def getInvoice(paymentHash: ByteVector32)(implicit timeout: Timeout): Future[Option[PaymentRequest]]

  def pendingInvoices(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[Seq[PaymentRequest]]

  def allInvoices(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[Seq[PaymentRequest]]

  def allNodes()(implicit timeout: Timeout): Future[Iterable[NodeAnnouncement]]

  def allChannels()(implicit timeout: Timeout): Future[Iterable[ChannelDesc]]

  def allUpdates(nodeId: Option[PublicKey])(implicit timeout: Timeout): Future[Iterable[ChannelUpdate]]

  def getInfoResponse()(implicit timeout: Timeout): Future[GetInfoResponse]

}

class EclairImpl(appKit: Kit) extends Eclair {

  implicit val ec = appKit.system.dispatcher

  override def connect(uri: String)(implicit timeout: Timeout): Future[String] = {
    (appKit.switchboard ? Peer.Connect(NodeURI.parse(uri))).mapTo[String]
  }

  override def open(nodeId: PublicKey, fundingSatoshis: Long, pushMsat: Option[Long], fundingFeerateSatByte: Option[Long], flags: Option[Int], openTimeout_opt: Option[Timeout])(implicit timeout: Timeout): Future[String] = {
    // we want the open timeout to expire *before* the default ask timeout, otherwise user won't get a generic response
    val openTimeout = openTimeout_opt.getOrElse(Timeout(10 seconds))
    (appKit.switchboard ? Peer.OpenChannel(
      remoteNodeId = nodeId,
      fundingSatoshis = Satoshi(fundingSatoshis),
      pushMsat = pushMsat.map(MilliSatoshi).getOrElse(MilliSatoshi(0)),
      fundingTxFeeratePerKw_opt = fundingFeerateSatByte.map(feerateByte2Kw),
      channelFlags = flags.map(_.toByte),
      timeout_opt = Some(openTimeout))).mapTo[String]
  }

  override def close(channelIdentifier: Either[ByteVector32, ShortChannelId], scriptPubKey: Option[ByteVector])(implicit timeout: Timeout): Future[String] = {
    sendToChannel(channelIdentifier, CMD_CLOSE(scriptPubKey)).mapTo[String]
  }

  override def forceClose(channelIdentifier: Either[ByteVector32, ShortChannelId])(implicit timeout: Timeout): Future[String] = {
    sendToChannel(channelIdentifier, CMD_FORCECLOSE).mapTo[String]
  }

  override def updateRelayFee(channelIdentifier: Either[ByteVector32, ShortChannelId], feeBaseMsat: Long, feeProportionalMillionths: Long)(implicit timeout: Timeout): Future[String] = {
    sendToChannel(channelIdentifier, CMD_UPDATE_RELAY_FEE(feeBaseMsat, feeProportionalMillionths)).mapTo[String]
  }

  override def peersInfo()(implicit timeout: Timeout): Future[Iterable[PeerInfo]] = for {
    peers <- (appKit.switchboard ? 'peers).mapTo[Iterable[ActorRef]]
    peerinfos <- Future.sequence(peers.map(peer => (peer ? GetPeerInfo).mapTo[PeerInfo]))
  } yield peerinfos

  override def channelsInfo(toRemoteNode: Option[PublicKey])(implicit timeout: Timeout): Future[Iterable[RES_GETINFO]] = toRemoteNode match {
    case Some(pk) => for {
      channelIds <- (appKit.register ? 'channelsTo).mapTo[Map[ByteVector32, PublicKey]].map(_.filter(_._2 == pk).keys)
      channels <- Future.sequence(channelIds.map(channelId => sendToChannel(Left(channelId), CMD_GETINFO).mapTo[RES_GETINFO]))
    } yield channels
    case None => for {
      channelIds <- (appKit.register ? 'channels).mapTo[Map[ByteVector32, ActorRef]].map(_.keys)
      channels <- Future.sequence(channelIds.map(channelId => sendToChannel(Left(channelId), CMD_GETINFO).mapTo[RES_GETINFO]))
    } yield channels
  }

  override def channelInfo(channelIdentifier: Either[ByteVector32, ShortChannelId])(implicit timeout: Timeout): Future[RES_GETINFO] = {
    sendToChannel(channelIdentifier, CMD_GETINFO).mapTo[RES_GETINFO]
  }

  override def allNodes()(implicit timeout: Timeout): Future[Iterable[NodeAnnouncement]] = (appKit.router ? 'nodes).mapTo[Iterable[NodeAnnouncement]]

  override def allChannels()(implicit timeout: Timeout): Future[Iterable[ChannelDesc]] = {
    (appKit.router ? 'channels).mapTo[Iterable[ChannelAnnouncement]].map(_.map(c => ChannelDesc(c.shortChannelId, c.nodeId1, c.nodeId2)))
  }

  override def allUpdates(nodeId: Option[PublicKey])(implicit timeout: Timeout): Future[Iterable[ChannelUpdate]] = nodeId match {
    case None => (appKit.router ? 'updates).mapTo[Iterable[ChannelUpdate]]
    case Some(pk) => (appKit.router ? 'updatesMap).mapTo[Map[ChannelDesc, ChannelUpdate]].map(_.filter(e => e._1.a == pk || e._1.b == pk).values)
  }

  override def receive(description: String, amountMsat: Option[Long], expire: Option[Long], fallbackAddress: Option[String])(implicit timeout: Timeout): Future[PaymentRequest] = {
    fallbackAddress.map { fa => fr.acinq.eclair.addressToPublicKeyScript(fa, appKit.nodeParams.chainHash) } // if it's not a bitcoin address throws an exception
    (appKit.paymentHandler ? ReceivePayment(description = description, amountMsat_opt = amountMsat.map(MilliSatoshi), expirySeconds_opt = expire, fallbackAddress = fallbackAddress)).mapTo[PaymentRequest]
  }

  override def findRoute(targetNodeId: PublicKey, amountMsat: Long, assistedRoutes: Seq[Seq[PaymentRequest.ExtraHop]] = Seq.empty)(implicit timeout: Timeout): Future[RouteResponse] = {
    (appKit.router ? RouteRequest(appKit.nodeParams.nodeId, targetNodeId, amountMsat, assistedRoutes)).mapTo[RouteResponse]
  }

  override def send(recipientNodeId: PublicKey, amountMsat: Long, paymentHash: ByteVector32, assistedRoutes: Seq[Seq[PaymentRequest.ExtraHop]] = Seq.empty, minFinalCltvExpiry_opt: Option[Long] = None, maxAttempts_opt: Option[Int] = None)(implicit timeout: Timeout): Future[UUID] = {
    val maxAttempts = maxAttempts_opt.getOrElse(appKit.nodeParams.maxPaymentAttempts)
    val sendPayment = minFinalCltvExpiry_opt match {
      case Some(minCltv) => SendPayment(amountMsat, paymentHash, recipientNodeId, assistedRoutes, finalCltvExpiry = minCltv, maxAttempts = maxAttempts)
      case None => SendPayment(amountMsat, paymentHash, recipientNodeId, assistedRoutes, maxAttempts = maxAttempts)
    }
    (appKit.paymentInitiator ? sendPayment).mapTo[UUID]
  }

  override def sentInfo(id: Either[UUID, ByteVector32])(implicit timeout: Timeout): Future[Seq[OutgoingPayment]] = Future {
    id match {
      case Left(uuid) => appKit.nodeParams.db.payments.getOutgoingPayment(uuid).toSeq
      case Right(paymentHash) => appKit.nodeParams.db.payments.getOutgoingPayments(paymentHash)
    }
  }

  override def receivedInfo(paymentHash: ByteVector32)(implicit timeout: Timeout): Future[Option[IncomingPayment]] = Future {
    appKit.nodeParams.db.payments.getIncomingPayment(paymentHash)
  }

  override def audit(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[AuditResponse] = {
    val from = from_opt.getOrElse(0L)
    val to = to_opt.getOrElse(MaxEpochSeconds)

    Future(AuditResponse(
      sent = appKit.nodeParams.db.audit.listSent(from, to),
      received = appKit.nodeParams.db.audit.listReceived(from, to),
      relayed = appKit.nodeParams.db.audit.listRelayed(from, to)
    ))
  }

  override def networkFees(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[Seq[NetworkFee]] = {
    val from = from_opt.getOrElse(0L)
    val to = to_opt.getOrElse(MaxEpochSeconds)

    Future(appKit.nodeParams.db.audit.listNetworkFees(from, to))
  }

  override def channelStats()(implicit timeout: Timeout): Future[Seq[Stats]] = Future(appKit.nodeParams.db.audit.stats)

  override def allInvoices(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[Seq[PaymentRequest]] = Future {
    val from = from_opt.getOrElse(0L)
    val to = to_opt.getOrElse(MaxEpochSeconds)

    appKit.nodeParams.db.payments.listPaymentRequests(from, to)
  }

  override def pendingInvoices(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[Seq[PaymentRequest]] = Future {
    val from = from_opt.getOrElse(0L)
    val to = to_opt.getOrElse(MaxEpochSeconds)

    appKit.nodeParams.db.payments.listPendingPaymentRequests(from, to)
  }

  override def getInvoice(paymentHash: ByteVector32)(implicit timeout: Timeout): Future[Option[PaymentRequest]] = Future {
    appKit.nodeParams.db.payments.getPaymentRequest(paymentHash)
  }

  /**
    * Sends a request to a channel and expects a response
    *
    * @param channelIdentifier either a shortChannelId (BOLT encoded) or a channelId (32-byte hex encoded)
    * @param request
    * @return
    */
  def sendToChannel(channelIdentifier: Either[ByteVector32, ShortChannelId], request: Any)(implicit timeout: Timeout): Future[Any] = channelIdentifier match {
    case Left(channelId) => appKit.register ? Forward(channelId, request)
    case Right(shortChannelId) => appKit.register ? ForwardShortId(shortChannelId, request)
  }

  override def getInfoResponse()(implicit timeout: Timeout): Future[GetInfoResponse] = Future.successful(
    GetInfoResponse(nodeId = appKit.nodeParams.nodeId,
      alias = appKit.nodeParams.alias,
      chainHash = appKit.nodeParams.chainHash,
      blockHeight = Globals.blockCount.intValue(),
      publicAddresses = appKit.nodeParams.publicAddresses)
  )

}
