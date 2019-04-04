package fr.acinq.eclair

import akka.actor.ActorRef
import akka.pattern._
import akka.util.Timeout
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, MilliSatoshi, Satoshi}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.{NetworkFee, Stats}
import fr.acinq.eclair.io.Peer.{GetPeerInfo, PeerInfo}
import fr.acinq.eclair.io.{NodeURI, Peer}
import fr.acinq.eclair.payment.PaymentLifecycle._
import fr.acinq.eclair.payment.{PaymentLifecycle, PaymentReceived, PaymentRelayed, PaymentRequest, PaymentSent}
import fr.acinq.eclair.router.{ChannelDesc, RouteRequest, RouteResponse}
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, NodeAddress, NodeAnnouncement}
import scodec.bits.ByteVector

import scala.concurrent.Future
import scala.concurrent.duration._

case class GetInfoResponse(nodeId: PublicKey, alias: String, chainHash: ByteVector32, blockHeight: Int, publicAddresses: Seq[NodeAddress])

case class AuditResponse(sent: Seq[PaymentSent], received: Seq[PaymentReceived], relayed: Seq[PaymentRelayed])

trait Eclair {

  def connect(uri: String)(implicit timeout: Timeout): Future[String]

  def open(nodeId: PublicKey, fundingSatoshis: Long, pushMsat: Option[Long], fundingFeerateSatByte: Option[Long], flags: Option[Int], timeout_opt: Option[Timeout])(implicit timeout: Timeout): Future[String]

  def close(channelIdentifier: Either[ByteVector32, ShortChannelId], scriptPubKey: Option[ByteVector])(implicit timeout: Timeout): Future[String]

  def forceClose(channelIdentifier: Either[ByteVector32, ShortChannelId])(implicit timeout: Timeout): Future[String]

  def updateRelayFee(channelId: String, feeBaseMsat: Long, feeProportionalMillionths: Long)(implicit timeout: Timeout): Future[String]

  def peersInfo()(implicit timeout: Timeout): Future[Iterable[PeerInfo]]

  def channelsInfo(toRemoteNode: Option[PublicKey])(implicit timeout: Timeout): Future[Iterable[RES_GETINFO]]

  def channelInfo(channelId: ByteVector32)(implicit timeout: Timeout): Future[RES_GETINFO]

  def allnodes()(implicit timeout: Timeout): Future[Iterable[NodeAnnouncement]]

  def allchannels()(implicit timeout: Timeout): Future[Iterable[ChannelDesc]]

  def allupdates(nodeId: Option[PublicKey])(implicit timeout: Timeout): Future[Iterable[ChannelUpdate]]

  def receive(description: String, amountMsat: Option[Long], expire: Option[Long])(implicit timeout: Timeout): Future[String]

  def findRoute(targetNodeId: PublicKey, amountMsat: Long, assistedRoutes: Seq[Seq[PaymentRequest.ExtraHop]] = Seq.empty)(implicit timeout: Timeout): Future[RouteResponse]

  def send(recipientNodeId: PublicKey, amountMsat: Long, paymentHash: ByteVector32, assistedRoutes: Seq[Seq[PaymentRequest.ExtraHop]] = Seq.empty, minFinalCltvExpiry: Option[Long] = None)(implicit timeout: Timeout): Future[PaymentResult]

  def checkpayment(paymentHash: ByteVector32)(implicit timeout: Timeout): Future[Boolean]

  def audit(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[AuditResponse]

  def networkFees(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[Seq[NetworkFee]]

  def channelStats()(implicit timeout: Timeout): Future[Seq[Stats]]

  def getInfoResponse()(implicit timeout: Timeout): Future[GetInfoResponse]

}

class EclairImpl(appKit: Kit) extends Eclair {

  implicit val ec = appKit.system.dispatcher

  override def connect(uri: String)(implicit timeout: Timeout): Future[String] = {
    (appKit.switchboard ? Peer.Connect(NodeURI.parse(uri))).mapTo[String]
  }

  override def open(nodeId: PublicKey, fundingSatoshis: Long, pushMsat: Option[Long], fundingFeerateSatByte: Option[Long], flags: Option[Int], timeout_opt: Option[Timeout])(implicit timeout: Timeout): Future[String] = {
    // we want the default timeout to expire *before* the default ask times out, otherwise user won't get a usable response
    val openTimeout = timeout_opt.getOrElse(Timeout(10 seconds))
    (appKit.switchboard ? Peer.OpenChannel(
      remoteNodeId = nodeId,
      fundingSatoshis = Satoshi(fundingSatoshis),
      pushMsat = pushMsat.map(MilliSatoshi).getOrElse(MilliSatoshi(0)),
      fundingTxFeeratePerKw_opt = fundingFeerateSatByte,
      channelFlags = flags.map(_.toByte),
      timeout_opt = Some(openTimeout))).mapTo[String]
  }

  override def close(channelIdentifier: Either[ByteVector32, ShortChannelId], scriptPubKey: Option[ByteVector])(implicit timeout: Timeout): Future[String] = {
    sendToChannel(channelIdentifier.fold[String](_.toString(), _.toString()), CMD_CLOSE(scriptPubKey)).mapTo[String]
  }

  override def forceClose(channelIdentifier: Either[ByteVector32, ShortChannelId])(implicit timeout: Timeout): Future[String] = {
    sendToChannel(channelIdentifier.fold[String](_.toString(), _.toString()), CMD_FORCECLOSE).mapTo[String]
  }

  override def updateRelayFee(channelId: String, feeBaseMsat: Long, feeProportionalMillionths: Long)(implicit timeout: Timeout): Future[String] = {
    sendToChannel(channelId, CMD_UPDATE_RELAY_FEE(feeBaseMsat, feeProportionalMillionths)).mapTo[String]
  }

  override def peersInfo()(implicit timeout: Timeout): Future[Iterable[PeerInfo]] = for {
    peers <- (appKit.switchboard ? 'peers).mapTo[Iterable[ActorRef]]
    peerinfos <- Future.sequence(peers.map(peer => (peer ? GetPeerInfo).mapTo[PeerInfo]))
  } yield peerinfos

  override def channelsInfo(toRemoteNode: Option[PublicKey])(implicit timeout: Timeout): Future[Iterable[RES_GETINFO]] = toRemoteNode match {
    case Some(pk) => for {
      channelsId <- (appKit.register ? 'channelsTo).mapTo[Map[ByteVector32, PublicKey]].map(_.filter(_._2 == pk).keys)
      channels <- Future.sequence(channelsId.map(channelId => sendToChannel(channelId.toString(), CMD_GETINFO).mapTo[RES_GETINFO]))
    } yield channels
    case None => for {
      channels_id <- (appKit.register ? 'channels).mapTo[Map[ByteVector32, ActorRef]].map(_.keys)
      channels <- Future.sequence(channels_id.map(channel_id => sendToChannel(channel_id.toHex, CMD_GETINFO).mapTo[RES_GETINFO]))
    } yield channels
  }

  override def channelInfo(channelId: ByteVector32)(implicit timeout: Timeout): Future[RES_GETINFO] = {
    sendToChannel(channelId.toString(), CMD_GETINFO).mapTo[RES_GETINFO]
  }

  override def allnodes()(implicit timeout: Timeout): Future[Iterable[NodeAnnouncement]] = (appKit.router ? 'nodes).mapTo[Iterable[NodeAnnouncement]]

  override def allchannels()(implicit timeout: Timeout): Future[Iterable[ChannelDesc]] = {
    (appKit.router ? 'channels).mapTo[Iterable[ChannelAnnouncement]].map(_.map(c => ChannelDesc(c.shortChannelId, c.nodeId1, c.nodeId2)))
  }

  override def allupdates(nodeId: Option[PublicKey])(implicit timeout: Timeout): Future[Iterable[ChannelUpdate]] = nodeId match {
    case None => (appKit.router ? 'updates).mapTo[Iterable[ChannelUpdate]]
    case Some(pk) => (appKit.router ? 'updatesMap).mapTo[Map[ChannelDesc, ChannelUpdate]].map(_.filter(e => e._1.a == pk || e._1.b == pk).values)
  }

  override def receive(description: String, amountMsat: Option[Long], expire: Option[Long])(implicit timeout: Timeout): Future[String] = {
    (appKit.paymentHandler ? ReceivePayment(description = description, amountMsat_opt = amountMsat.map(MilliSatoshi), expirySeconds_opt = expire)).mapTo[PaymentRequest].map { pr =>
      PaymentRequest.write(pr)
    }
  }

  override def findRoute(targetNodeId: PublicKey, amountMsat: Long, assistedRoutes: Seq[Seq[PaymentRequest.ExtraHop]] = Seq.empty)(implicit timeout: Timeout): Future[RouteResponse] = {
    (appKit.router ? RouteRequest(appKit.nodeParams.nodeId, targetNodeId, amountMsat, assistedRoutes)).mapTo[RouteResponse]
  }

  override def send(recipientNodeId: PublicKey, amountMsat: Long, paymentHash: ByteVector32, assistedRoutes: Seq[Seq[PaymentRequest.ExtraHop]] = Seq.empty, minFinalCltvExpiry: Option[Long] = None)(implicit timeout: Timeout): Future[PaymentResult] = {
    val sendPayment = minFinalCltvExpiry match {
      case Some(minCltv) => SendPayment(amountMsat, paymentHash, recipientNodeId, assistedRoutes, finalCltvExpiry = minCltv)
      case None  => SendPayment(amountMsat, paymentHash, recipientNodeId, assistedRoutes)
    }
    (appKit.paymentInitiator ? sendPayment).mapTo[PaymentResult].map {
      case s: PaymentSucceeded => s
      case f: PaymentFailed => f.copy(failures = PaymentLifecycle.transformForUser(f.failures))
    }
  }

  override def checkpayment(paymentHash: ByteVector32)(implicit timeout: Timeout): Future[Boolean] = {
    (appKit.paymentHandler ? CheckPayment(paymentHash)).mapTo[Boolean]
  }

  override def audit(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[AuditResponse] = {
    val from = from_opt.getOrElse(0L)
    val to = to_opt.getOrElse(Long.MaxValue)

    Future(AuditResponse(
      sent = appKit.nodeParams.db.audit.listSent(from, to),
      received = appKit.nodeParams.db.audit.listReceived(from, to),
      relayed = appKit.nodeParams.db.audit.listRelayed(from, to)
    ))
  }

  override def networkFees(from_opt: Option[Long], to_opt: Option[Long])(implicit timeout: Timeout): Future[Seq[NetworkFee]] = {
    val from = from_opt.getOrElse(0L)
    val to = to_opt.getOrElse(Long.MaxValue)

    Future(appKit.nodeParams.db.audit.listNetworkFees(from, to))
  }

  override def channelStats()(implicit timeout: Timeout): Future[Seq[Stats]] = Future(appKit.nodeParams.db.audit.stats)

  /**
    * Sends a request to a channel and expects a response
    *
    * @param channelIdentifier can be a shortChannelId (BOLT encoded) or a channelId (32-byte hex encoded)
    * @param request
    * @return
    */
  def sendToChannel(channelIdentifier: String, request: Any)(implicit timeout: Timeout): Future[Any] =
    for {
      fwdReq <- Future(Register.ForwardShortId(ShortChannelId(channelIdentifier), request))
        .recoverWith { case _ => Future(Register.Forward(ByteVector32.fromValidHex(channelIdentifier), request)) }
        .recoverWith { case _ => Future.failed(new RuntimeException(s"invalid channel identifier '$channelIdentifier'")) }
      res <- appKit.register ? fwdReq
    } yield res

  override def getInfoResponse()(implicit timeout: Timeout): Future[GetInfoResponse] = Future.successful(
    GetInfoResponse(nodeId = appKit.nodeParams.nodeId,
    alias = appKit.nodeParams.alias,
    chainHash = appKit.nodeParams.chainHash,
    blockHeight = Globals.blockCount.intValue(),
    publicAddresses = appKit.nodeParams.publicAddresses)
  )

}
