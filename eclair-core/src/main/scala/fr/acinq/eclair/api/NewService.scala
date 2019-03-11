package fr.acinq.eclair.api

import akka.util.Timeout
import akka.pattern._
import akka.http.scaladsl.server._
import de.heikoseeberger.akkahttpjson4s.Json4sSupport.ShouldWritePretty
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi, Satoshi}
import fr.acinq.eclair.{Kit, ShortChannelId}
import fr.acinq.eclair.io.{NodeURI, Peer}
import org.json4s.jackson
import Marshallers._
import fr.acinq.eclair.channel.{CMD_CLOSE, Register}
import fr.acinq.eclair.wire.NodeAddress

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

trait NewService extends Directives {

  implicit val ec: ExecutionContext
  implicit val serialization = jackson.Serialization
  implicit val formats = org.json4s.DefaultFormats +
    new BinaryDataSerializer +
    new UInt64Serializer +
    new MilliSatoshiSerializer +
    new ShortChannelIdSerializer +
    new StateSerializer +
    new ShaChainSerializer +
    new PublicKeySerializer +
    new PrivateKeySerializer +
    new ScalarSerializer +
    new PointSerializer +
    new TransactionSerializer +
    new TransactionWithInputInfoSerializer +
    new InetSocketAddressSerializer +
    new OutPointSerializer +
    new OutPointKeySerializer +
    new InputInfoSerializer +
    new ColorSerializer +
    new RouteResponseSerializer +
    new ThrowableSerializer +
    new FailureMessageSerializer +
    new NodeAddressSerializer +
    new DirectionSerializer +
    new PaymentRequestSerializer +
    PublicKeySerializer

  implicit val timeout = Timeout(60 seconds)
  implicit val shouldWritePretty: ShouldWritePretty = ShouldWritePretty.True

  def appKit: Kit

  val motherRoute: Route = pathSingleSlash {
    get {
      connectRoute ~
      openRoute ~
      closeRoute
    }
  }

  val connectRoute = path("connect") {
    parameters("nodeId".as[PublicKey], "address".as[NodeAddress]) { (nodeId, addr) =>
      complete(connect(s"$nodeId@$addr"))
    } ~ parameters("uri") { uri =>
      complete(connect(uri))
    }
  }

  val openRoute = path("open") {
    parameters("nodeId".as[PublicKey], "fundingSatoshis".as[Long], "pushMsat".as[Long].?, "fundingFeerateSatByte".as[Long].?, "channelFlags".as[Int].?) {
      (nodeId, fundingSatoshis, pushMsat, fundingFeerateSatByte, channelFlags) =>
      complete(open(nodeId, fundingSatoshis, pushMsat, fundingFeerateSatByte, channelFlags))
    }
  }

  val closeRoute = path("close") {
    parameters("channelId".as[BinaryData](sha256HashUnmarshaller), "scriptPubKey".as[BinaryData](binaryDataUnmarshaller).?) { (channelId: BinaryData, scriptPubKey_opt: Option[BinaryData]) =>
      complete(close(channelId.toString(), scriptPubKey_opt))
    }
  }


  def connect(uri: String) : Future[String] = {
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

  def close(channelId: String, scriptPubKey: Option[BinaryData]) = {
    sendToChannel(channelId, CMD_CLOSE(scriptPubKey)).mapTo[String]
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

}