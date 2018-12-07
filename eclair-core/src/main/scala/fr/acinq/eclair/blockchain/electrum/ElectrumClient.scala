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

package fr.acinq.eclair.blockchain.electrum

import java.net.{InetSocketAddress, SocketAddress}
import java.util

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Stash, Terminated}
import fr.acinq.bitcoin._
import fr.acinq.eclair.blockchain.bitcoind.rpc.{Error, JsonRPCRequest, JsonRPCResponse}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.SSL
import io.netty.bootstrap.Bootstrap
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.string.{LineEncoder, StringDecoder}
import io.netty.handler.codec.{LineBasedFrameDecoder, MessageToMessageDecoder, MessageToMessageEncoder}
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.util.CharsetUtil
import io.netty.util.concurrent.{Future, GenericFutureListener}
import org.json4s.JsonAST._
import org.json4s.jackson.JsonMethods
import org.json4s.{DefaultFormats, JInt, JLong, JString}
import org.spongycastle.util.encoders.Hex

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class ElectrumClient(serverAddress: InetSocketAddress, ssl: SSL)(implicit val ec: ExecutionContext) extends Actor with Stash with ActorLogging {

  import ElectrumClient._

  implicit val formats = DefaultFormats

  val workerGroup = new NioEventLoopGroup()

  val b = new Bootstrap
  b.group(workerGroup)
  b.channel(classOf[NioSocketChannel])
  b.option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)
  b.handler(new ChannelInitializer[SocketChannel]() {
    override def initChannel(ch: SocketChannel): Unit = {
      ssl match {
        case SSL.OFF => ()
        case SSL.STRICT =>
          val sslCtx = SslContextBuilder.forClient.build
          ch.pipeline.addLast(sslCtx.newHandler(ch.alloc(), serverAddress.getHostName, serverAddress.getPort))
        case SSL.LOOSE =>
          // INSECURE VERSION THAT DOESN'T CHECK CERTIFICATE
          val sslCtx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build()
          ch.pipeline.addLast(sslCtx.newHandler(ch.alloc(), serverAddress.getHostName, serverAddress.getPort))
      }
      // inbound handlers
      ch.pipeline.addLast(new LineBasedFrameDecoder(Int.MaxValue, true, true)) // JSON messages are separated by a new line
      ch.pipeline.addLast(new StringDecoder(CharsetUtil.UTF_8))
      ch.pipeline.addLast(new ElectrumResponseDecoder)
      ch.pipeline.addLast(new ActorHandler(self))
      // outbound handlers
      ch.pipeline.addLast(new LineEncoder)
      ch.pipeline.addLast(new JsonRPCRequestEncoder)
      // error handler
      ch.pipeline().addLast(new ExceptionHandler)
    }
  })

  // Start the client.
  log.info(s"connecting to $serverAddress")
  val channelFuture = b.connect(serverAddress.getHostName, serverAddress.getPort)

  def errorHandler(t: Throwable) = {
    log.error(t, s"connection error: ")
    statusListeners.map(_ ! ElectrumDisconnected)
    context stop self
  }

  /**
    * This error handler catches all exceptions and kill the actor
    * See https://stackoverflow.com/questions/30994095/how-to-catch-all-exception-in-netty
    */
  class ExceptionHandler extends ChannelDuplexHandler {
    override def connect(ctx: ChannelHandlerContext, remoteAddress: SocketAddress, localAddress: SocketAddress, promise: ChannelPromise): Unit = {
      ctx.connect(remoteAddress, localAddress, promise.addListener(new ChannelFutureListener() {
        override def operationComplete(future: ChannelFuture): Unit = {
          if (!future.isSuccess) {
            errorHandler(future.cause())
          }
        }
      }))
    }

    override def write(ctx: ChannelHandlerContext, msg: scala.Any, promise: ChannelPromise): Unit = {
      ctx.write(msg, promise.addListener(new ChannelFutureListener() {
        override def operationComplete(future: ChannelFuture): Unit = {
          if (!future.isSuccess) {
            errorHandler(future.cause())
          }
        }
      }))
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
      errorHandler(cause)
    }
  }

  /**
    * A decoder ByteBuf -> Either[Response, JsonRPCResponse]
    */
  class ElectrumResponseDecoder extends MessageToMessageDecoder[String] {
    override def decode(ctx: ChannelHandlerContext, msg: String, out: util.List[AnyRef]): Unit = {
      val s = msg.asInstanceOf[String]
      val r = parseResponse(s)
      out.add(r)
    }
  }

  /**
    * An encoder JsonRPCRequest -> ByteBuf
    */
  class JsonRPCRequestEncoder extends MessageToMessageEncoder[JsonRPCRequest] {
    override def encode(ctx: ChannelHandlerContext, request: JsonRPCRequest, out: util.List[AnyRef]): Unit = {
      import org.json4s.JsonDSL._
      import org.json4s._
      import org.json4s.jackson.JsonMethods._

      log.info(s"sending $request")
      val json = ("method" -> request.method) ~ ("params" -> request.params.map {
        case s: String => new JString(s)
        case b: BinaryData => new JString(b.toString())
        case t: Int => new JInt(t)
        case t: Long => new JLong(t)
        case t: Double => new JDouble(t)
      }) ~ ("id" -> request.id) ~ ("jsonrpc" -> request.jsonrpc)
      val serialized = compact(render(json))
      out.add(serialized)
    }

  }


  /**
    * Forwards incoming messages to the underlying actor
    *
    * @param actor
    */
  class ActorHandler(actor: ActorRef) extends ChannelInboundHandlerAdapter {

    override def channelActive(ctx: ChannelHandlerContext): Unit = {
      actor ! ctx
    }

    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
      actor ! msg
    }
  }

  var addressSubscriptions = Map.empty[String, Set[ActorRef]]
  var scriptHashSubscriptions = Map.empty[BinaryData, Set[ActorRef]]
  val headerSubscriptions = collection.mutable.HashSet.empty[ActorRef]
  val version = ServerVersion("2.1.7", "1.2")
  val statusListeners = collection.mutable.HashSet.empty[ActorRef]
  val keepHeaders = 100

  var reqId = 0

  // we need to regularly send a ping in order not to get disconnected
  val versionTrigger = context.system.scheduler.schedule(30 seconds, 30 seconds, self, version)

  override def unhandled(message: Any): Unit = {
    message match {
      case Terminated(deadActor) =>
        addressSubscriptions = addressSubscriptions.mapValues(subscribers => subscribers - deadActor)
        scriptHashSubscriptions = scriptHashSubscriptions.mapValues(subscribers => subscribers - deadActor)
        statusListeners -= deadActor
        headerSubscriptions -= deadActor

      case RemoveStatusListener(actor) => statusListeners -= actor

      case _: ServerVersion => () // we only handle this when connected

      case _: ServerVersionResponse => () // we just ignore these messages, they are used as pings

      case _ => log.warning(s"unhandled $message")
    }
  }

  override def postStop(): Unit = {
    versionTrigger.cancel()
    super.postStop()
  }

  /**
    * send an electrum request to the server
    *
    * @param ctx     connection to the electrumx server
    * @param request electrum request
    * @return the request id used to send the request
    */
  def send(ctx: ChannelHandlerContext, request: Request): String = {
    val electrumRequestId = "" + reqId
    ctx.channel().writeAndFlush(makeRequest(request, electrumRequestId))
    reqId = reqId + 1
    electrumRequestId
  }

  def receive = disconnected

  def disconnected: Receive = {
    case ctx: ChannelHandlerContext =>
      log.info(s"connected to $serverAddress")
      send(ctx, version)
      context become waitingForVersion(ctx)

    case AddStatusListener(actor) => statusListeners += actor
  }

  def waitingForVersion(ctx: ChannelHandlerContext): Receive = {
    case Right(json: JsonRPCResponse) =>
      val serverVersion = parseJsonResponse(version, json)
      log.debug(s"serverVersion=$serverVersion")
      send(ctx, HeaderSubscription(self))
      headerSubscriptions += self
      log.debug("waiting for tip")
      context become waitingForTip(ctx)

    case AddStatusListener(actor) => statusListeners += actor
  }

  def waitingForTip(ctx: ChannelHandlerContext): Receive = {
    case Right(json: JsonRPCResponse) =>
      val header = parseHeader(json.result)
      log.debug(s"connected, tip = ${header.block_hash} $header")
      statusListeners.map(_ ! ElectrumReady(header, serverAddress))
      context become connected(ctx, header, "", Map())

    case AddStatusListener(actor) => statusListeners += actor
  }

  def connected(ctx: ChannelHandlerContext, tip: Header, buffer: String, requests: Map[String, (Request, ActorRef)]): Receive = {
    case AddStatusListener(actor) =>
      statusListeners += actor
      actor ! ElectrumReady(tip, serverAddress)

    case HeaderSubscription(actor) =>
      headerSubscriptions += actor
      actor ! HeaderSubscriptionResponse(tip)
      context watch actor

    case request: Request =>
      val curReqId = send(ctx, request)
      request match {
        case AddressSubscription(address, actor) =>
          addressSubscriptions = addressSubscriptions.updated(address, addressSubscriptions.getOrElse(address, Set()) + actor)
          context watch actor
        case ScriptHashSubscription(scriptHash, actor) =>
          scriptHashSubscriptions = scriptHashSubscriptions.updated(scriptHash, scriptHashSubscriptions.getOrElse(scriptHash, Set()) + actor)
          context watch actor
        case _ => ()
      }
      context become connected(ctx, tip, buffer, requests + (curReqId -> (request, sender())))

    case Right(json: JsonRPCResponse) =>
      requests.get(json.id) match {
        case Some((request, requestor)) =>
          val response = parseJsonResponse(request, json)
          log.debug(s"got response for reqId=${json.id} request=$request response=$response")
          requestor ! response
        case None =>
          log.warning(s"could not find requestor for reqId=${json.id} response=$json")
      }
      context become connected(ctx, tip, buffer, requests - json.id)

    case Left(response: HeaderSubscriptionResponse) => headerSubscriptions.map(_ ! response)

    case Left(response: AddressSubscriptionResponse) => addressSubscriptions.get(response.address).map(listeners => listeners.map(_ ! response))

    case Left(response: ScriptHashSubscriptionResponse) => scriptHashSubscriptions.get(response.scriptHash).map(listeners => listeners.map(_ ! response))

    case HeaderSubscriptionResponse(newtip) =>
      log.info(s"new tip $newtip")
      context become connected(ctx, newtip, buffer, requests)
  }
}

object ElectrumClient {
  /**
    * Utility function to converts a publicKeyScript to electrum's scripthash
    *
    * @param publicKeyScript public key script
    * @return the hash of the public key script, as used by ElectrumX's hash-based methods
    */
  def computeScriptHash(publicKeyScript: BinaryData): BinaryData = Crypto.sha256(publicKeyScript).reverse

  // @formatter:off
  case class AddStatusListener(actor: ActorRef)
  case class RemoveStatusListener(actor: ActorRef)

  sealed trait Request
  sealed trait Response

  case class ServerVersion(clientName: String, protocolVersion: String) extends Request
  case class ServerVersionResponse(clientName: String, protocolVersion: String) extends Response

  case class GetAddressHistory(address: String) extends Request
  case class TransactionHistoryItem(height: Long, tx_hash: BinaryData)
  case class GetAddressHistoryResponse(address: String, history: Seq[TransactionHistoryItem]) extends Response

  case class GetScriptHashHistory(scriptHash: BinaryData) extends Request
  case class GetScriptHashHistoryResponse(scriptHash: BinaryData, history: Seq[TransactionHistoryItem]) extends Response

  case class AddressListUnspent(address: String) extends Request
  case class UnspentItem(tx_hash: BinaryData, tx_pos: Int, value: Long, height: Long) {
    lazy val outPoint = OutPoint(tx_hash.reverse, tx_pos)
  }
  case class AddressListUnspentResponse(address: String, unspents: Seq[UnspentItem]) extends Response

  case class ScriptHashListUnspent(scriptHash: BinaryData) extends Request
  case class ScriptHashListUnspentResponse(scriptHash: BinaryData, unspents: Seq[UnspentItem]) extends Response

  case class BroadcastTransaction(tx: Transaction) extends Request
  case class BroadcastTransactionResponse(tx: Transaction, error: Option[Error]) extends Response

  case class GetTransaction(txid: BinaryData) extends Request
  case class GetTransactionResponse(tx: Transaction) extends Response

  case class GetHeader(height: Int) extends Request
  case class GetHeaderResponse(header: Header) extends Response

  case class GetMerkle(txid: BinaryData, height: Long) extends Request
  case class GetMerkleResponse(txid: BinaryData, merkle: Seq[BinaryData], block_height: Long, pos: Int) extends Response {
    lazy val root: BinaryData = {
      @tailrec
      def loop(pos: Int, hashes: Seq[BinaryData]): BinaryData = {
        if (hashes.length == 1) hashes(0).reverse
        else {
          val h = if (pos % 2 == 1) Crypto.hash256(hashes(1) ++ hashes(0)) else Crypto.hash256(hashes(0) ++ hashes(1))
          loop(pos / 2, h +: hashes.drop(2))
        }
      }
      loop(pos, BinaryData(txid.reverse) +: merkle.map(b => BinaryData(b.reverse)))
    }
  }

  case class AddressSubscription(address: String, actor: ActorRef) extends Request
  case class AddressSubscriptionResponse(address: String, status: String) extends Response

  case class ScriptHashSubscription(scriptHash: BinaryData, actor: ActorRef) extends Request
  case class ScriptHashSubscriptionResponse(scriptHash: BinaryData, status: String) extends Response

  case class HeaderSubscription(actor: ActorRef) extends Request
  case class HeaderSubscriptionResponse(header: Header) extends Response

  case class Header(block_height: Long, version: Long, prev_block_hash: BinaryData, merkle_root: BinaryData, timestamp: Long, bits: Long, nonce: Long) {
    def blockHeader = BlockHeader(version, prev_block_hash.reverse, merkle_root.reverse, timestamp, bits, nonce)
    lazy val block_id: BinaryData = blockHeader.hash
    lazy val block_hash: BinaryData = block_id.reverse
  }

  object Header {
    def makeHeader(height: Long, header: BlockHeader) = ElectrumClient.Header(0, header.version, header.hashPreviousBlock, header.hashMerkleRoot, header.time, header.bits, header.nonce)

    val RegtestGenesisHeader = makeHeader(0, Block.RegtestGenesisBlock.header)
    val TestnetGenesisHeader = makeHeader(0, Block.TestnetGenesisBlock.header)
    val LivenetGenesisHeader = makeHeader(0, Block.LivenetGenesisBlock.header)
  }

  case class TransactionHistory(history: Seq[TransactionHistoryItem]) extends Response

  case class AddressStatus(address: String, status: String) extends Response

  case class ServerError(request: Request, error: Error) extends Response

  sealed trait ElectrumEvent
  case class ElectrumReady(tip: Header, serverAddress: InetSocketAddress) extends ElectrumEvent
  case object ElectrumDisconnected extends ElectrumEvent

  sealed trait SSL
  object SSL {
    case object OFF extends SSL
    case object STRICT extends SSL
    case object LOOSE extends SSL
  }

  // @formatter:on

  def parseResponse(input: String): Either[Response, JsonRPCResponse] = {
    implicit val formats = DefaultFormats
    val json = JsonMethods.parse(new String(input))
    json \ "method" match {
      case JString(method) =>
        // this is a jsonrpc request, i.e. a subscription response
        val JArray(params) = json \ "params"
        Left(((method, params): @unchecked) match {
          case ("blockchain.headers.subscribe", header :: Nil) => HeaderSubscriptionResponse(parseHeader(header))
          case ("blockchain.address.subscribe", JString(address) :: JNull :: Nil) => AddressSubscriptionResponse(address, "")
          case ("blockchain.address.subscribe", JString(address) :: JString(status) :: Nil) => AddressSubscriptionResponse(address, status)
          case ("blockchain.scripthash.subscribe", JString(scriptHashHex) :: JNull :: Nil) => ScriptHashSubscriptionResponse(BinaryData(scriptHashHex), "")
          case ("blockchain.scripthash.subscribe", JString(scriptHashHex) :: JString(status) :: Nil) => ScriptHashSubscriptionResponse(BinaryData(scriptHashHex), status)
        })
      case _ => Right(parseJsonRpcResponse(json))
    }
  }

  def parseJsonRpcResponse(json: JValue): JsonRPCResponse = {
    implicit val formats = DefaultFormats
    val result = json \ "result"
    val error = json \ "error" match {
      case JNull => None
      case JNothing => None
      case other =>
        val message = other \ "message" match {
          case JString(value) => value
          case _ => ""
        }
        val code = other \ " code" match {
          case JInt(value) => value.intValue()
          case JLong(value) => value.intValue()
          case _ => 0
        }
        Some(Error(code, message))
    }
    val id = json \ "id" match {
      case JString(value) => value
      case JInt(value) => value.toString()
      case JLong(value) => value.toString
      case _ => ""
    }
    JsonRPCResponse(result, error, id)
  }

  def longField(jvalue: JValue, field: String): Long = (jvalue \ field: @unchecked) match {
    case JLong(value) => value.longValue()
    case JInt(value) => value.longValue()
  }

  def intField(jvalue: JValue, field: String): Int = (jvalue \ field: @unchecked) match {
    case JLong(value) => value.intValue()
    case JInt(value) => value.intValue()
  }

  def parseHeader(json: JValue): Header = {
    val block_height = longField(json, "block_height")
    val version = longField(json, "version")
    val timestamp = longField(json, "timestamp")
    val bits = longField(json, "bits")
    val nonce = longField(json, "nonce")
    val JString(prev_block_hash) = json \ "prev_block_hash"
    val JString(merkle_root) = json \ "merkle_root"
    Header(block_height, version, prev_block_hash, merkle_root, timestamp, bits, nonce)
  }

  def makeRequest(request: Request, reqId: String): JsonRPCRequest = request match {
    case ServerVersion(clientName, protocolVersion) => JsonRPCRequest(id = reqId, method = "server.version", params = clientName :: protocolVersion :: Nil)
    case GetAddressHistory(address) => JsonRPCRequest(id = reqId, method = "blockchain.address.get_history", params = address :: Nil)
    case GetScriptHashHistory(scripthash) => JsonRPCRequest(id = reqId, method = "blockchain.scripthash.get_history", params = scripthash.toString() :: Nil)
    case AddressListUnspent(address) => JsonRPCRequest(id = reqId, method = "blockchain.address.listunspent", params = address :: Nil)
    case ScriptHashListUnspent(scripthash) => JsonRPCRequest(id = reqId, method = "blockchain.scripthash.listunspent", params = scripthash.toString() :: Nil)
    case AddressSubscription(address, _) => JsonRPCRequest(id = reqId, method = "blockchain.address.subscribe", params = address :: Nil)
    case ScriptHashSubscription(scriptHash, _) => JsonRPCRequest(id = reqId, method = "blockchain.scripthash.subscribe", params = scriptHash.toString() :: Nil)
    case BroadcastTransaction(tx) => JsonRPCRequest(id = reqId, method = "blockchain.transaction.broadcast", params = Hex.toHexString(Transaction.write(tx)) :: Nil)
    case GetTransaction(txid: BinaryData) => JsonRPCRequest(id = reqId, method = "blockchain.transaction.get", params = txid :: Nil)
    case HeaderSubscription(_) => JsonRPCRequest(id = reqId, method = "blockchain.headers.subscribe", params = Nil)
    case GetHeader(height) => JsonRPCRequest(id = reqId, method = "blockchain.block.get_header", params = height :: Nil)
    case GetMerkle(txid, height) => JsonRPCRequest(id = reqId, method = "blockchain.transaction.get_merkle", params = txid :: height :: Nil)
  }

  def parseJsonResponse(request: Request, json: JsonRPCResponse): Response = {
    implicit val formats = DefaultFormats
    json.error match {
      case Some(error) => (request: @unchecked) match {
        case BroadcastTransaction(tx) => BroadcastTransactionResponse(tx, Some(error)) // for this request type, error are considered a "normal" response
        case _ => ServerError(request, error)
      }
      case None => (request: @unchecked) match {
        case s: ServerVersion =>
          val JArray(jitems) = json.result
          val JString(clientName) = jitems(0)
          val JString(protocolVersion) = jitems(1)
          ServerVersionResponse(clientName, protocolVersion)
        case GetAddressHistory(address) =>
          val JArray(jitems) = json.result
          val items = jitems.map(jvalue => {
            val JString(tx_hash) = jvalue \ "tx_hash"
            val height = longField(jvalue, "height")
            TransactionHistoryItem(height, tx_hash)
          })
          GetAddressHistoryResponse(address, items)
        case GetScriptHashHistory(scripthash) =>
          val JArray(jitems) = json.result
          val items = jitems.map(jvalue => {
            val JString(tx_hash) = jvalue \ "tx_hash"
            val height = longField(jvalue, "height")
            TransactionHistoryItem(height, tx_hash)
          })
          GetScriptHashHistoryResponse(scripthash, items)
        case AddressListUnspent(address) =>
          val JArray(jitems) = json.result
          val items = jitems.map(jvalue => {
            val JString(tx_hash) = jvalue \ "tx_hash"
            val tx_pos = intField(jvalue, "tx_pos")
            val height = longField(jvalue, "height")
            val value = longField(jvalue, "value")
            UnspentItem(tx_hash, tx_pos, value, height)
          })
          AddressListUnspentResponse(address, items)
        case ScriptHashListUnspent(scripthash) =>
          val JArray(jitems) = json.result
          val items = jitems.map(jvalue => {
            val JString(tx_hash) = jvalue \ "tx_hash"
            val tx_pos = intField(jvalue, "tx_pos")
            val height = longField(jvalue, "height")
            val value = longField(jvalue, "value")
            UnspentItem(tx_hash, tx_pos, value, height)
          })
          ScriptHashListUnspentResponse(scripthash, items)
        case GetTransaction(_) =>
          val JString(hex) = json.result
          GetTransactionResponse(Transaction.read(hex))
        case AddressSubscription(address, _) => json.result match {
          case JString(status) => AddressSubscriptionResponse(address, status)
          case _ => AddressSubscriptionResponse(address, "")
        }
        case ScriptHashSubscription(scriptHash, _) => json.result match {
          case JString(status) => ScriptHashSubscriptionResponse(scriptHash, status)
          case _ => ScriptHashSubscriptionResponse(scriptHash, "")
        }
        case BroadcastTransaction(tx) =>
          val JString(txid) = json.result
          require(BinaryData(txid) == tx.txid)
          BroadcastTransactionResponse(tx, None)
        case GetHeader(height) =>
          GetHeaderResponse(parseHeader(json.result))
        case GetMerkle(txid, height) =>
          val JArray(hashes) = json.result \ "merkle"
          val leaves = hashes collect { case JString(value) => BinaryData(value) }
          val blockHeight = longField(json.result, "block_height")
          val JInt(pos) = json.result \ "pos"
          GetMerkleResponse(txid, leaves, blockHeight, pos.toInt)
      }
    }
  }
}
