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

package fr.acinq.eclair

import java.util.UUID
import akka.actor.ActorRef
import akka.pattern._
import akka.util.Timeout
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, MilliSatoshi, Satoshi}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.{NetworkFee, SentPayment, Stats}
import fr.acinq.eclair.io.Peer.{GetPeerInfo, PeerInfo}
import fr.acinq.eclair.io.{NodeURI, Peer}
import fr.acinq.eclair.payment.PaymentLifecycle._
import fr.acinq.eclair.router.{ChannelDesc, RouteRequest, RouteResponse}
import scodec.bits.ByteVector
import scala.concurrent.Future
import scala.concurrent.duration._
import fr.acinq.eclair.payment.{PaymentLifecycle, PaymentReceived, PaymentRelayed, PaymentRequest, PaymentSent}
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, NodeAddress, NodeAnnouncement}

case class GetInfoResponse(nodeId: PublicKey, alias: String, chainHash: ByteVector32, blockHeight: Int, publicAddresses: Seq[NodeAddress])

case class AuditResponse(sent: Seq[PaymentSent], received: Seq[PaymentReceived], relayed: Seq[PaymentRelayed])

trait Eclair {

  def connect(uri: String): Future[String]

  def open(nodeId: PublicKey, fundingSatoshis: Long, pushMsat: Option[Long], fundingFeerateSatByte: Option[Long], flags: Option[Int]): Future[String]

  def close(channelIdentifier: Either[ByteVector32, ShortChannelId], scriptPubKey: Option[ByteVector]): Future[String]

  def forceClose(channelIdentifier: Either[ByteVector32, ShortChannelId]): Future[String]

  def updateRelayFee(channelId: String, feeBaseMsat: Long, feeProportionalMillionths: Long): Future[String]

  def peersInfo(): Future[Iterable[PeerInfo]]

  def channelsInfo(toRemoteNode: Option[PublicKey]): Future[Iterable[RES_GETINFO]]

  def channelInfo(channelId: ByteVector32): Future[RES_GETINFO]

  def allnodes(): Future[Iterable[NodeAnnouncement]]

  def allchannels(): Future[Iterable[ChannelDesc]]

  def allupdates(nodeId: Option[PublicKey]): Future[Iterable[ChannelUpdate]]

  def receive(description: String, amountMsat: Option[Long], expire: Option[Long], fallbackAddress: Option[String]): Future[String]

  def findRoute(targetNodeId: PublicKey, amountMsat: Long, assistedRoutes: Seq[Seq[PaymentRequest.ExtraHop]] = Seq.empty): Future[RouteResponse]

  def send(recipientNodeId: PublicKey, amountMsat: Long, paymentHash: ByteVector32, assistedRoutes: Seq[Seq[PaymentRequest.ExtraHop]] = Seq.empty, minFinalCltvExpiry: Option[Long] = None): Future[UUID]

  def sentInfo(id: Either[UUID, ByteVector32]): Future[Option[SentPayment]]

  def receivedInfo(paymentHash: ByteVector32): Future[Option[ReceivePayment]]

  def audit(from_opt: Option[Long], to_opt: Option[Long]): Future[AuditResponse]

  def networkFees(from_opt: Option[Long], to_opt: Option[Long]): Future[Seq[NetworkFee]]

  def channelStats(): Future[Seq[Stats]]

  def getInfoResponse(): Future[GetInfoResponse]

  def pendingInvoices(): Future[Seq[PaymentRequest]]

  def getInvoice(paymentHash: ByteVector32): Future[Option[PaymentRequest]]

}

class EclairImpl(appKit: Kit) extends Eclair {

  implicit val ec = appKit.system.dispatcher
  implicit val timeout = Timeout(60 seconds) // used by akka ask

  override def connect(uri: String): Future[String] = {
    (appKit.switchboard ? Peer.Connect(NodeURI.parse(uri))).mapTo[String]
  }

  override def open(nodeId: PublicKey, fundingSatoshis: Long, pushMsat: Option[Long], fundingFeerateSatByte: Option[Long], flags: Option[Int]): Future[String] = {
    (appKit.switchboard ? Peer.OpenChannel(
      remoteNodeId = nodeId,
      fundingSatoshis = Satoshi(fundingSatoshis),
      pushMsat = pushMsat.map(MilliSatoshi).getOrElse(MilliSatoshi(0)),
      fundingTxFeeratePerKw_opt = fundingFeerateSatByte.map(feerateByte2Kw),
      channelFlags = flags.map(_.toByte))).mapTo[String]
  }

  override def close(channelIdentifier: Either[ByteVector32, ShortChannelId], scriptPubKey: Option[ByteVector]): Future[String] = {
    sendToChannel(channelIdentifier.fold[String](_.toString(), _.toString()), CMD_CLOSE(scriptPubKey)).mapTo[String]
  }

  override def forceClose(channelIdentifier: Either[ByteVector32, ShortChannelId]): Future[String] = {
    sendToChannel(channelIdentifier.fold[String](_.toString(), _.toString()), CMD_FORCECLOSE).mapTo[String]
  }

  override def updateRelayFee(channelId: String, feeBaseMsat: Long, feeProportionalMillionths: Long): Future[String] = {
    sendToChannel(channelId, CMD_UPDATE_RELAY_FEE(feeBaseMsat, feeProportionalMillionths)).mapTo[String]
  }

  override def peersInfo(): Future[Iterable[PeerInfo]] = for {
    peers <- (appKit.switchboard ? 'peers).mapTo[Iterable[ActorRef]]
    peerinfos <- Future.sequence(peers.map(peer => (peer ? GetPeerInfo).mapTo[PeerInfo]))
  } yield peerinfos

  override def channelsInfo(toRemoteNode: Option[PublicKey]): Future[Iterable[RES_GETINFO]] = toRemoteNode match {
    case Some(pk) => for {
      channelsId <- (appKit.register ? 'channelsTo).mapTo[Map[ByteVector32, PublicKey]].map(_.filter(_._2 == pk).keys)
      channels <- Future.sequence(channelsId.map(channelId => sendToChannel(channelId.toString(), CMD_GETINFO).mapTo[RES_GETINFO]))
    } yield channels
    case None => for {
      channels_id <- (appKit.register ? 'channels).mapTo[Map[ByteVector32, ActorRef]].map(_.keys)
      channels <- Future.sequence(channels_id.map(channel_id => sendToChannel(channel_id.toHex, CMD_GETINFO).mapTo[RES_GETINFO]))
    } yield channels
  }

  override def channelInfo(channelId: ByteVector32): Future[RES_GETINFO] = {
    sendToChannel(channelId.toString(), CMD_GETINFO).mapTo[RES_GETINFO]
  }

  override def allnodes(): Future[Iterable[NodeAnnouncement]] = (appKit.router ? 'nodes).mapTo[Iterable[NodeAnnouncement]]

  override def allchannels(): Future[Iterable[ChannelDesc]] = {
    (appKit.router ? 'channels).mapTo[Iterable[ChannelAnnouncement]].map(_.map(c => ChannelDesc(c.shortChannelId, c.nodeId1, c.nodeId2)))
  }

  override def allupdates(nodeId: Option[PublicKey]): Future[Iterable[ChannelUpdate]] = nodeId match {
    case None => (appKit.router ? 'updates).mapTo[Iterable[ChannelUpdate]]
    case Some(pk) => (appKit.router ? 'updatesMap).mapTo[Map[ChannelDesc, ChannelUpdate]].map(_.filter(e => e._1.a == pk || e._1.b == pk).values)
  }

  override def receive(description: String, amountMsat: Option[Long], expire: Option[Long], fallbackAddress: Option[String]): Future[String] = {
    fallbackAddress.map { fa => fr.acinq.eclair.addressToPublicKeyScript(fa, appKit.nodeParams.chainHash) } // if it's not a bitcoin address throws an exception
    (appKit.paymentHandler ? ReceivePayment(description = description, amountMsat_opt = amountMsat.map(MilliSatoshi), expirySeconds_opt = expire, fallbackAddress = fallbackAddress)).mapTo[PaymentRequest].map { pr =>
      PaymentRequest.write(pr)
    }
  }

  override def findRoute(targetNodeId: PublicKey, amountMsat: Long, assistedRoutes: Seq[Seq[PaymentRequest.ExtraHop]] = Seq.empty): Future[RouteResponse] = {
    (appKit.router ? RouteRequest(appKit.nodeParams.nodeId, targetNodeId, amountMsat, assistedRoutes)).mapTo[RouteResponse]
  }

  override def send(recipientNodeId: PublicKey, amountMsat: Long, paymentHash: ByteVector32, assistedRoutes: Seq[Seq[PaymentRequest.ExtraHop]] = Seq.empty, minFinalCltvExpiry: Option[Long] = None): Future[UUID] = {
    val sendPayment = minFinalCltvExpiry match {
      case Some(minCltv) => SendPayment(amountMsat, paymentHash, recipientNodeId, assistedRoutes, finalCltvExpiry = minCltv)
      case None  => SendPayment(amountMsat, paymentHash, recipientNodeId, assistedRoutes)
    }
    (appKit.paymentInitiator ? sendPayment).mapTo[UUID]
  }

  override def sentInfo(id: Either[UUID, ByteVector32]): Future[Option[SentPayment ]] = Future {
    id match {
      case Left(uuid) => appKit.nodeParams.db.payments.getSent(uuid)
      case Right(paymentHash) => appKit.nodeParams.db.payments.getSent(paymentHash)
    }
  }

  override def receivedInfo(paymentHash: ByteVector32): Future[Option[ReceivePayment]] = {
    (appKit.paymentHandler ? CheckPayment(paymentHash)).mapTo[Option[ReceivePayment]]
  }

  override def audit(from_opt: Option[Long], to_opt: Option[Long]): Future[AuditResponse] = {
    val from = from_opt.getOrElse(0L)
    val to = to_opt.getOrElse(Long.MaxValue)

    Future(AuditResponse(
      sent = appKit.nodeParams.db.audit.listSent(from, to),
      received = appKit.nodeParams.db.audit.listReceived(from, to),
      relayed = appKit.nodeParams.db.audit.listRelayed(from, to)
    ))
  }

  override def networkFees(from_opt: Option[Long], to_opt: Option[Long]): Future[Seq[NetworkFee]] = {
    val from = from_opt.getOrElse(0L)
    val to = to_opt.getOrElse(Long.MaxValue)

    Future(appKit.nodeParams.db.audit.listNetworkFees(from, to))
  }

  override def channelStats(): Future[Seq[Stats]] = Future(appKit.nodeParams.db.audit.stats)

  override def pendingInvoices(): Future[Seq[PaymentRequest]] = Future {
    appKit.nodeParams.db.payments.listPendingPaymentRequests()
  }

  override def getInvoice(paymentHash: ByteVector32): Future[Option[PaymentRequest]] = Future {
    appKit.nodeParams.db.payments.getPaymentRequest(paymentHash)
  }

  /**
    * Sends a request to a channel and expects a response
    *
    * @param channelIdentifier can be a shortChannelId (BOLT encoded) or a channelId (32-byte hex encoded)
    * @param request
    * @return
    */
  def sendToChannel(channelIdentifier: String, request: Any): Future[Any] =
    for {
      fwdReq <- Future(Register.ForwardShortId(ShortChannelId(channelIdentifier), request))
        .recoverWith { case _ => Future(Register.Forward(ByteVector32.fromValidHex(channelIdentifier), request)) }
        .recoverWith { case _ => Future.failed(new RuntimeException(s"invalid channel identifier '$channelIdentifier'")) }
      res <- appKit.register ? fwdReq
    } yield res

  override def getInfoResponse: Future[GetInfoResponse] = Future.successful(
    GetInfoResponse(nodeId = appKit.nodeParams.nodeId,
    alias = appKit.nodeParams.alias,
    chainHash = appKit.nodeParams.chainHash,
    blockHeight = Globals.blockCount.intValue(),
    publicAddresses = appKit.nodeParams.publicAddresses)
  )

}
