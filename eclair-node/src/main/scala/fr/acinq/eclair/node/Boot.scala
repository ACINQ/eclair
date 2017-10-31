package fr.acinq.eclair.node

import java.io.File
import java.net.InetSocketAddress

import _root_.io.undertow.Undertow
import _root_.io.undertow.io.Receiver
import _root_.io.undertow.server.{HttpHandler, HttpServerExchange}
import akka.actor.{ActorRef, PoisonPill}
import akka.pattern.ask
import akka.util.Timeout
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi, Satoshi}
import fr.acinq.eclair._
import fr.acinq.eclair.channel.{CMD_CLOSE, CMD_GETINFO, RES_GETINFO}
import fr.acinq.eclair.io.Switchboard.{NewChannel, NewConnection}
import fr.acinq.eclair.payment.{PaymentRequest, PaymentResult, ReceivePayment, SendPayment}
import fr.acinq.eclair.wire.{ChannelAnnouncement, NodeAnnouncement}
import grizzled.slf4j.Logging
import org.json4s.JsonAST.{JInt, JString}
import org.json4s.{JValue, jackson}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

/**
  * Created by PM on 25/01/2016.
  */
object Boot extends App with Logging {

  val datadir = new File(System.getProperty("eclair.datadir", System.getProperty("user.home") + "/.eclair"))
  val config = NodeParams.loadConfiguration(datadir)
  val nodeParams = NodeParams.makeNodeParams(datadir, config)
  val kit = Await.result(new Setup(datadir, None).bootstrap, 15 seconds)
  import kit._

  // @formatter:off
  case class JsonRPCBody(jsonrpc: String = "1.0", id: String = "scala-client", method: String, params: Seq[JValue])
  case class Error(code: Int, message: String)
  case class JsonRPCRes(result: AnyRef, error: Option[Error], id: String)
  case class Status(node_id: String)
  case class GetInfoResponse(nodeId: PublicKey, alias: String, port: Int, chainHash: BinaryData, blockHeight: Int)
  case class ChannelInfo(shortChannelId: String, nodeId1: PublicKey , nodeId2: PublicKey)
  // @formatter:on

  implicit val formats = org.json4s.DefaultFormats + new BinaryDataSerializer + new PublicKeySerializer
  implicit val timeout = Timeout(30 seconds)

  def getInfoResponse: Future[GetInfoResponse] = Future.successful(GetInfoResponse(nodeId = nodeParams.privateKey.publicKey, alias = nodeParams.alias, port = config.getInt("server.port"), chainHash = nodeParams.chainHash, blockHeight = Globals.blockCount.intValue()))

  def getChannel(channelId: String): Future[ActorRef] =
    for {
      channels <- (kit.register ? 'channels).mapTo[Map[BinaryData, ActorRef]]
    } yield channels.get(BinaryData(channelId)).getOrElse(throw new RuntimeException("unknown channel"))

  def process(req: JsonRPCBody) : Future[JsonRPCRes] = {
    val future = req match {
      case JsonRPCBody(_, _, "getinfo", _) => getInfoResponse
      case JsonRPCBody(_, _, "connect", JString(host) :: JInt(port) :: JString(nodeId) :: Nil) =>
        (switchboard ? NewConnection(PublicKey(nodeId), new InetSocketAddress(host, port.toInt), None)).mapTo[String]
      case JsonRPCBody(_, _, "open", JString(nodeId) :: JString(host) :: JInt(port) :: JInt(fundingSatoshi) :: JInt(pushMsat) :: options) =>
        val channelFlags = options match {
          case JInt(value) :: Nil => Some(value.toByte)
          case _ => None // TODO: too lax?
        }
        (switchboard ? NewConnection(PublicKey(nodeId), new InetSocketAddress(host, port.toInt), Some(NewChannel(Satoshi(fundingSatoshi.toLong), MilliSatoshi(pushMsat.toLong), channelFlags)))).mapTo[String]
      case JsonRPCBody(_, _, "disconnect", _) =>
        (switchboard ? 'connections).mapTo[Map[PublicKey, ActorRef]].map(_.values.toList).map(_.map(a => a ! PoisonPill))
      case JsonRPCBody(_, _, "peers", _) =>
        (switchboard ? 'peers).mapTo[Map[PublicKey, ActorRef]].map(_.map(_._1.toBin))
      case JsonRPCBody(_, _, "channels", _) =>
        (register ? 'channels).mapTo[Map[Long, ActorRef]].map(_.keys)
      case JsonRPCBody(_, _, "channel", JString(channelId) :: Nil) =>
        getChannel(channelId).flatMap(_ ? CMD_GETINFO).mapTo[RES_GETINFO]
      case JsonRPCBody(_, _, "allnodes", _) =>
        (router ? 'nodes).mapTo[Iterable[NodeAnnouncement]].map(_.map(_.nodeId))
      case JsonRPCBody(_, _, "allchannels", _) =>
        (router ? 'channels).mapTo[Iterable[ChannelAnnouncement]].map(_.map(c => ChannelInfo(c.shortChannelId.toHexString, c.nodeId1, c.nodeId2)))
      case JsonRPCBody(_,_, "receive", JInt(amountMsat) :: JString(description) :: Nil) =>
        (paymentHandler ? ReceivePayment(MilliSatoshi(amountMsat.toLong), description)).mapTo[PaymentRequest].map(PaymentRequest.write)
      case JsonRPCBody(_, _, "send", JInt(amountMsat) :: JString(paymentHash) :: JString(nodeId) :: Nil) =>
        (paymentInitiator ? SendPayment(amountMsat.toLong, paymentHash, PublicKey(nodeId))).mapTo[PaymentResult]
      case JsonRPCBody(_, _, "send", JString(paymentRequest) :: rest) =>
        for {
          req <- Future(PaymentRequest.read(paymentRequest))
          amount = (req.amount, rest) match {
            case (Some(_), JInt(amt) :: Nil) => amt.toLong // overriding payment request amount with the one provided
            case (Some(amt), _) => amt.amount
            case (None, JInt(amt) :: Nil) => amt.toLong // amount wasn't specified in request, using custom one
            case (None, _) => throw new RuntimeException("you need to manually specify an amount for this payment request")
          }
          res <- (paymentInitiator ? SendPayment(amount, req.paymentHash, req.nodeId)).mapTo[PaymentResult]
        } yield res
      case JsonRPCBody(_, _, "close", JString(channelId) :: JString(scriptPubKey) :: Nil) =>
        getChannel(channelId).flatMap(_ ? CMD_CLOSE(scriptPubKey = Some(scriptPubKey))).mapTo[String]
      case JsonRPCBody(_, _, "close", JString(channelId) :: Nil) =>
        getChannel(channelId).flatMap(_ ? CMD_CLOSE(scriptPubKey = None)).mapTo[String]
      case JsonRPCBody(_, _, "help", _) =>
        Future.successful(List(
          "connect (host, port, nodeId): connect to another lightning node through a secure connection",
          "open (nodeId, host, port, fundingSatoshi, pushMsat, channelFlags = 0x01): open a channel with another lightning node",
          "peers: list existing local peers",
          "channels: list existing local channels",
          "channel (channelId): retrieve detailed information about a given channel",
          "allnodes: list all known nodes",
          "allchannels: list all known channels",
          "receive (amountMsat, description): generate a payment request for a given amount",
          "send (amountMsat, paymentHash, nodeId): send a payment to a lightning node",
          "send (paymentRequest): send a payment to a lightning node using a BOLT11 payment request",
          "send (paymentRequest, amountMsat): send a payment to a lightning node using a BOLT11 payment request and a custom amount",
          "close (channelId): close a channel",
          "close (channelId, scriptPubKey): close a channel and send the funds to the given scriptPubKey",
          "help: display this message"))
      case _ => Future.failed(new RuntimeException("method not found"))
    }
    future.map(result => JsonRPCRes(result, None, req.id))
  }

  val server = Undertow.builder.addHttpListener(8080, "localhost").setHandler(new HttpHandler {
    override def handleRequest(exchange: HttpServerExchange): Unit = {
      exchange.getRequestReceiver.receiveFullString(new Receiver.FullStringCallback {
        override def handle(exchange: HttpServerExchange, message: String): Unit = {
          val req = jackson.Serialization.read[JsonRPCBody](message)
          process(req)
        }
      })
    }
  }).build()
  server.start()
}

