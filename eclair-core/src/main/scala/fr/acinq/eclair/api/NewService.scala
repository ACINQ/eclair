package fr.acinq.eclair.api

import akka.util.Timeout
import akka.pattern._
import akka.http.scaladsl.server._
import de.heikoseeberger.akkahttpjson4s.Json4sSupport.{ShouldWritePretty, marshaller, unmarshaller}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi, Satoshi}
import fr.acinq.eclair.{Kit, ShortChannelId}
import fr.acinq.eclair.io.{NodeURI, Peer}
import Marshallers._
import akka.actor.ActorRef
import fr.acinq.eclair.channel._
import fr.acinq.eclair.io.Peer.{GetPeerInfo, PeerInfo}
import fr.acinq.eclair.wire.NodeAddress
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

trait NewService extends WithJsonSerializers {

  def appKit: Kit

  def getInfoResponse: Future[GetInfoResponse]

  implicit val ec = appKit.system.dispatcher
  implicit val timeout = Timeout(60 seconds)
  implicit val shouldWritePretty: ShouldWritePretty = ShouldWritePretty.True

  val channelIdNamedParameter = "channelId".as[PublicKey]

  val route: Route = {
    get {
      path("getinfo") {
        complete(getInfoResponse)
      } ~
      path("help") {
        complete(help.mkString)
      }
      path("connect") {
        parameters("nodeId".as[PublicKey], "address".as[NodeAddress]) { (nodeId, addr) =>
          complete(connect(s"$nodeId@$addr"))
        } ~ parameters("uri") { uri =>
          complete(connect(uri))
        }
      } ~
      path("open") {
        parameters("nodeId".as[PublicKey], "fundingSatoshis".as[Long], "pushMsat".as[Long].?, "fundingFeerateSatByte".as[Long].?, "channelFlags".as[Int].?) {
          (nodeId, fundingSatoshis, pushMsat, fundingFeerateSatByte, channelFlags) =>
            complete(open(nodeId, fundingSatoshis, pushMsat, fundingFeerateSatByte, channelFlags))
        }
      } ~
      path("close") {
        parameters(channelIdNamedParameter, "scriptPubKey".as[BinaryData](binaryDataUnmarshaller).?) { (channelId, scriptPubKey_opt) =>
          complete(close(channelId.toString(), scriptPubKey_opt))
        }
      } ~
      path("forceclose") {
        parameters(channelIdNamedParameter) { channelId =>
          complete(forceClose(channelId.toString))
        }
      } ~
      path("updaterelayfee") {
        parameters(channelIdNamedParameter, "feeBaseMsat".as[Long], "feeProportionalMillionths".as[Long]) { (channelId, feeBase, feeProportional) =>
          complete(updateRelayFee(channelId.toString, feeBase, feeProportional))
        }
      } ~
      path("peers") {
        complete(peersInfo())
      } ~
      path("channels") {
        parameters(channelIdNamedParameter.?) { channelId_opt =>
          complete(channels(channelId_opt))
        }
      }
    }
  }

  def connect(uri: String): Future[String] = {
    (appKit.switchboard ? Peer.Connect(NodeURI.parse(uri))).mapTo[String]
  }

  def open(nodeId: PublicKey, fundingSatoshis: Long, pushMsat: Option[Long], fundingFeerateSatByte: Option[Long], flags: Option[Int]): Future[String] = {
    (appKit.switchboard ? Peer.OpenChannel(
      remoteNodeId = nodeId,
      fundingSatoshis = Satoshi(fundingSatoshis),
      pushMsat = pushMsat.map(MilliSatoshi).getOrElse(MilliSatoshi(0)),
      fundingTxFeeratePerKw_opt = fundingFeerateSatByte,
      channelFlags = flags.map(_.toByte))).mapTo[String]
  }

  def close(channelId: String, scriptPubKey: Option[BinaryData]): Future[String] = {
    sendToChannel(channelId, CMD_CLOSE(scriptPubKey)).mapTo[String]
  }

  def forceClose(channelId: String): Future[String] = {
    sendToChannel(channelId, CMD_FORCECLOSE).mapTo[String]
  }

  def updateRelayFee(channelId: String, feeBaseMsat: Long, feeProportionalMillionths: Long): Future[String] = {
    sendToChannel(channelId, CMD_UPDATE_RELAY_FEE(feeBaseMsat, feeProportionalMillionths)).mapTo[String]
  }

  def peersInfo(): Future[Iterable[PeerInfo]] = for {
    peers <- (appKit.switchboard ? 'peers).mapTo[Iterable[ActorRef]]
    peerinfos <- Future.sequence(peers.map(peer => (peer ? GetPeerInfo).mapTo[PeerInfo]))
  } yield peerinfos

  def channels(channelIdFilter: Option[PublicKey]): Future[Iterable[RES_GETINFO]] = channelIdFilter match {
    case Some(pk) => for {
      channelsId <- (appKit.register ? 'channelsTo).mapTo[Map[BinaryData, PublicKey]].map(_.filter(_._2 == pk).keys)
      channels <- Future.sequence(channelsId.map(channelId => sendToChannel(channelId.toString(), CMD_GETINFO).mapTo[RES_GETINFO]))
    } yield channels
    case None => for {
      channels_id <- (appKit.register ? 'channels).mapTo[Map[BinaryData, ActorRef]].map(_.keys)
      channels <- Future.sequence(channels_id.map(channel_id => sendToChannel(channel_id.toString(), CMD_GETINFO).mapTo[RES_GETINFO]))
    } yield channels
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
        .recoverWith { case _ => Future(Register.Forward(BinaryData(channelIdentifier), request)) }
        .recoverWith { case _ => Future.failed(new RuntimeException(s"invalid channel identifier '$channelIdentifier'")) }
      res <- appKit.register ? fwdReq
    } yield res

  def help = List(
    "connect (uri): open a secure connection to a lightning node",
    "connect (nodeId, host, port): open a secure connection to a lightning node",
    "open (nodeId, fundingSatoshis, pushMsat = 0, feerateSatPerByte = ?, channelFlags = 0x01): open a channel with another lightning node, by default push = 0, feerate for the funding tx targets 6 blocks, and channel is announced",
    "updaterelayfee (channelId, feeBaseMsat, feeProportionalMillionths): update relay fee for payments going through this channel",
    "peers: list existing local peers",
    "channels: list existing local channels",
    "channels (nodeId): list existing local channels to a particular nodeId",
    "channel (channelId): retrieve detailed information about a given channel",
    "channelstats: retrieves statistics about channel usage (fees, number and average amount of payments)",
    "allnodes: list all known nodes",
    "allchannels: list all known channels",
    "allupdates: list all channels updates",
    "allupdates (nodeId): list all channels updates for this nodeId",
    "receive (amountMsat, description): generate a payment request for a given amount",
    "receive (amountMsat, description, expirySeconds): generate a payment request for a given amount with a description and a number of seconds till it expires",
    "parseinvoice (paymentRequest): returns node, amount and payment hash in a payment request",
    "findroute (paymentRequest): returns nodes and channels of the route if there is any",
    "findroute (paymentRequest, amountMsat): returns nodes and channels of the route if there is any",
    "findroute (nodeId, amountMsat): returns nodes and channels of the route if there is any",
    "send (amountMsat, paymentHash, nodeId): send a payment to a lightning node",
    "send (paymentRequest): send a payment to a lightning node using a BOLT11 payment request",
    "send (paymentRequest, amountMsat): send a payment to a lightning node using a BOLT11 payment request and a custom amount",
    "close (channelId): close a channel",
    "close (channelId, scriptPubKey): close a channel and send the funds to the given scriptPubKey",
    "forceclose (channelId): force-close a channel by publishing the local commitment tx (careful: this is more expensive than a regular close and will incur a delay before funds are spendable)",
    "checkpayment (paymentHash): returns true if the payment has been received, false otherwise",
    "checkpayment (paymentRequest): returns true if the payment has been received, false otherwise",
    "audit: list all send/received/relayed payments",
    "audit (from, to): list send/received/relayed payments in that interval (from <= timestamp < to)",
    "networkfees: list all network fees paid to the miners, by transaction",
    "networkfees (from, to): list network fees paid to the miners, by transaction, in that interval (from <= timestamp < to)",
    "getinfo: returns info about the blockchain and this node",
    "help: display this message")

}