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

import akka.actor.{Actor, ActorLogging, ActorRef, Stash, Terminated}
import fr.acinq.bitcoin._
import fr.acinq.eclair.blockchain.bitcoind.rpc.{Error, JsonRPCRequest, JsonRPCResponse}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.SSL
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.PooledByteBufAllocator
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.string.{LineEncoder, StringDecoder}
import io.netty.handler.codec.{LineBasedFrameDecoder, MessageToMessageDecoder, MessageToMessageEncoder}
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.util.CharsetUtil
import org.json4s.JsonAST._
import org.json4s.jackson.JsonMethods
import org.json4s.{DefaultFormats, JInt, JLong, JString}
import org.spongycastle.util.encoders.Hex

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
  * For later optimizations, see http://normanmaurer.me/presentations/2014-facebook-eng-netty/slides.html
  *
  */
class ElectrumClient(serverAddress: InetSocketAddress, ssl: SSL)(implicit val ec: ExecutionContext) extends Actor with Stash with ActorLogging {

  import ElectrumClient._

  implicit val formats = DefaultFormats

  val b = new Bootstrap
  b.group(workerGroup)
  b.channel(classOf[NioSocketChannel])
  b.option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)
  b.option[java.lang.Boolean](ChannelOption.TCP_NODELAY, true)
  b.option[java.lang.Integer](ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
  b.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
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
      ch.pipeline.addLast(new ExceptionHandler)
    }
  })

  // Start the client.
  log.info("connecting to server={}", serverAddress)

  val channelOpenFuture = b.connect(serverAddress.getHostName, serverAddress.getPort)

  def errorHandler(t: Throwable) = {
    log.info("server={} connection error (reason={})", serverAddress, t.getMessage)
    self ! Close
  }

  channelOpenFuture.addListeners(new ChannelFutureListener {
    override def operationComplete(future: ChannelFuture): Unit = {
      if (!future.isSuccess) {
        errorHandler(future.cause())
      } else {
        future.channel().closeFuture().addListener(new ChannelFutureListener {
          override def operationComplete(future: ChannelFuture): Unit = {
            if (!future.isSuccess) {
              errorHandler(future.cause())
            } else {
              log.info("server={} channel closed: {}", serverAddress, future.channel())
              self ! Close
            }
          }
        })
      }
    }
  })

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

      log.debug("sending {} to {}", request, serverAddress)
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
  val version = ServerVersion(CLIENT_NAME, PROTOCOL_VERSION)
  val statusListeners = collection.mutable.HashSet.empty[ActorRef]

  var reqId = 0

  // we need to regularly send a ping in order not to get disconnected
  val pingTrigger = context.system.scheduler.schedule(30 seconds, 30 seconds, self, Ping)

  override def unhandled(message: Any): Unit = {
    message match {
      case Terminated(deadActor) =>
        addressSubscriptions = addressSubscriptions.mapValues(subscribers => subscribers - deadActor)
        scriptHashSubscriptions = scriptHashSubscriptions.mapValues(subscribers => subscribers - deadActor)
        statusListeners -= deadActor
        headerSubscriptions -= deadActor

      case RemoveStatusListener(actor) => statusListeners -= actor

      case PingResponse => ()

      case Close =>
        statusListeners.map(_ ! ElectrumDisconnected)
        context.stop(self)

      case _ => log.warning("server={} unhandled message {}", serverAddress, message)
    }
  }

  override def postStop(): Unit = {
    pingTrigger.cancel()
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
    if (ctx.channel().isWritable) {
      ctx.channel().writeAndFlush(makeRequest(request, electrumRequestId))
    } else {
      errorHandler(new RuntimeException(s"channel not writable"))
    }
    reqId = reqId + 1
    electrumRequestId
  }

  def receive = disconnected

  def disconnected: Receive = {
    case ctx: ChannelHandlerContext =>
      log.info("connected to server={}", serverAddress)
      send(ctx, version)
      context become waitingForVersion(ctx)

    case AddStatusListener(actor) => statusListeners += actor
  }

  def waitingForVersion(ctx: ChannelHandlerContext): Receive = {
    case Right(json: JsonRPCResponse) =>
      (parseJsonResponse(version, json): @unchecked) match {
        case ServerVersionResponse(clientName, protocolVersion) =>
          log.info("server={} clientName={} protocolVersion={}", serverAddress, clientName, protocolVersion)
          send(ctx, HeaderSubscription(self))
          headerSubscriptions += self
          log.debug("waiting for tip from server={}", serverAddress)
          context become waitingForTip(ctx)
        case ServerError(request, error) =>
          log.error("server={} sent error={} while processing request={}, disconnecting", serverAddress, error, request)
          self ! Close
      }

    case AddStatusListener(actor) => statusListeners += actor
  }

  def waitingForTip(ctx: ChannelHandlerContext): Receive = {
    case Right(json: JsonRPCResponse) =>
      val (height, header) = parseBlockHeader(json.result)
      log.debug("connected to server={}, tip={} height={}", serverAddress, header.hash, height)
      statusListeners.map(_ ! ElectrumReady(height, header, serverAddress))
      context become connected(ctx, height, header, Map())

    case AddStatusListener(actor) => statusListeners += actor
  }

  def connected(ctx: ChannelHandlerContext, height: Int, tip: BlockHeader, requests: Map[String, (Request, ActorRef)]): Receive = {
    case AddStatusListener(actor) =>
      statusListeners += actor
      actor ! ElectrumReady(height, tip, serverAddress)

    case HeaderSubscription(actor) =>
      headerSubscriptions += actor
      actor ! HeaderSubscriptionResponse(height, tip)
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
      context become connected(ctx, height, tip, requests + (curReqId -> (request, sender())))

    case Right(json: JsonRPCResponse) =>
      requests.get(json.id) match {
        case Some((request, requestor)) =>
          val response = parseJsonResponse(request, json)
          log.debug("server={} sent response for reqId={} request={} response={}", serverAddress, json.id, request, response)
          requestor ! response
        case None =>
          log.warning("server={} could not find requestor for reqId=${} response={}", serverAddress, json.id, json)
      }
      context become connected(ctx, height, tip, requests - json.id)

    case Left(response: HeaderSubscriptionResponse) => headerSubscriptions.map(_ ! response)

    case Left(response: AddressSubscriptionResponse) => addressSubscriptions.get(response.address).map(listeners => listeners.map(_ ! response))

    case Left(response: ScriptHashSubscriptionResponse) => scriptHashSubscriptions.get(response.scriptHash).map(listeners => listeners.map(_ ! response))

    case HeaderSubscriptionResponse(height, newtip) =>
      log.info("server={} new tip={}", serverAddress, newtip)
      context become connected(ctx, height, newtip, requests)
  }
}

object ElectrumClient {
  val CLIENT_NAME = "3.3.2" // client name that we will include in our "version" message
  val PROTOCOL_VERSION = "1.4" // version of the protocol that we require

  // this is expensive and shared with all clients
  val workerGroup = new NioEventLoopGroup()

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

  case object Ping extends Request
  case object PingResponse extends Response

  case class GetAddressHistory(address: String) extends Request
  case class TransactionHistoryItem(height: Int, tx_hash: BinaryData)
  case class GetAddressHistoryResponse(address: String, history: Seq[TransactionHistoryItem]) extends Response

  case class GetScriptHashHistory(scriptHash: BinaryData) extends Request
  case class GetScriptHashHistoryResponse(scriptHash: BinaryData, history: List[TransactionHistoryItem]) extends Response

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
  case class GetHeaderResponse(height: Int, header: BlockHeader) extends Response
  object GetHeaderResponse {
    def apply(t: (Int, BlockHeader)) = new GetHeaderResponse(t._1, t._2)
  }

  case class GetHeaders(start_height: Int, count: Int, cp_height: Int = 0) extends Request
  case class GetHeadersResponse(start_height: Int, headers: Seq[BlockHeader], max: Int) extends Response

  case class GetMerkle(txid: BinaryData, height: Int) extends Request
  case class GetMerkleResponse(txid: BinaryData, merkle: List[BinaryData], block_height: Int, pos: Int) extends Response {
    lazy val root: BinaryData = {
      @tailrec
      def loop(pos: Int, hashes: Seq[BinaryData]): BinaryData = {
        if (hashes.length == 1) hashes(0)
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
  case class HeaderSubscriptionResponse(height: Int, header: BlockHeader) extends Response
  object HeaderSubscriptionResponse {
    def apply(t: (Int, BlockHeader)) = new HeaderSubscriptionResponse(t._1, t._2)
  }

  case class Header(block_height: Long, version: Long, prev_block_hash: BinaryData, merkle_root: BinaryData, timestamp: Long, bits: Long, nonce: Long) {
    def blockHeader = BlockHeader(version, prev_block_hash.reverse, merkle_root.reverse, timestamp, bits, nonce)

    lazy val block_hash: BinaryData = blockHeader.hash
    lazy val block_id: BinaryData = block_hash.reverse
  }

  object Header {
    def makeHeader(height: Long, header: BlockHeader) = ElectrumClient.Header(height, header.version, header.hashPreviousBlock.reverse, header.hashMerkleRoot.reverse, header.time, header.bits, header.nonce)

    val RegtestGenesisHeader = makeHeader(0, Block.RegtestGenesisBlock.header)
    val TestnetGenesisHeader = makeHeader(0, Block.TestnetGenesisBlock.header)
    val LivenetGenesisHeader = makeHeader(0, Block.LivenetGenesisBlock.header)
  }

  case class TransactionHistory(history: Seq[TransactionHistoryItem]) extends Response

  case class AddressStatus(address: String, status: String) extends Response

  case class ServerError(request: Request, error: Error) extends Response

  sealed trait ElectrumEvent

  case class ElectrumReady(height: Int, tip: BlockHeader, serverAddress: InetSocketAddress) extends ElectrumEvent
  object ElectrumReady {
    def apply(t: (Int, BlockHeader), serverAddress: InetSocketAddress) = new ElectrumReady(t._1 , t._2, serverAddress)
  }
  case object ElectrumDisconnected extends ElectrumEvent

  sealed trait SSL
  object SSL {
    case object OFF extends SSL
    case object STRICT extends SSL
    case object LOOSE extends SSL
  }

  case object Close

  // @formatter:on

  def parseResponse(input: String): Either[Response, JsonRPCResponse] = {
    implicit val formats = DefaultFormats
    val json = JsonMethods.parse(new String(input))
    json \ "method" match {
      case JString(method) =>
        // this is a jsonrpc request, i.e. a subscription response
        val JArray(params) = json \ "params"
        Left(((method, params): @unchecked) match {
          case ("blockchain.headers.subscribe", header :: Nil) => HeaderSubscriptionResponse(parseBlockHeader(header))
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

  def parseBlockHeader(json: JValue): (Int, BlockHeader) = {
    val height = intField(json, "height")
    val JString(hex) = json \ "hex"
    (height, BlockHeader.read(hex))
  }

  def makeRequest(request: Request, reqId: String): JsonRPCRequest = request match {
    case ServerVersion(clientName, protocolVersion) => JsonRPCRequest(id = reqId, method = "server.version", params = clientName :: protocolVersion :: Nil)
    case Ping => JsonRPCRequest(id = reqId, method = "server.ping", params = Nil)
    case GetAddressHistory(address) => JsonRPCRequest(id = reqId, method = "blockchain.address.get_history", params = address :: Nil)
    case GetScriptHashHistory(scripthash) => JsonRPCRequest(id = reqId, method = "blockchain.scripthash.get_history", params = scripthash.toString() :: Nil)
    case AddressListUnspent(address) => JsonRPCRequest(id = reqId, method = "blockchain.address.listunspent", params = address :: Nil)
    case ScriptHashListUnspent(scripthash) => JsonRPCRequest(id = reqId, method = "blockchain.scripthash.listunspent", params = scripthash.toString() :: Nil)
    case AddressSubscription(address, _) => JsonRPCRequest(id = reqId, method = "blockchain.address.subscribe", params = address :: Nil)
    case ScriptHashSubscription(scriptHash, _) => JsonRPCRequest(id = reqId, method = "blockchain.scripthash.subscribe", params = scriptHash.toString() :: Nil)
    case BroadcastTransaction(tx) => JsonRPCRequest(id = reqId, method = "blockchain.transaction.broadcast", params = Hex.toHexString(Transaction.write(tx)) :: Nil)
    case GetTransaction(txid: BinaryData) => JsonRPCRequest(id = reqId, method = "blockchain.transaction.get", params = txid :: Nil)
    case HeaderSubscription(_) => JsonRPCRequest(id = reqId, method = "blockchain.headers.subscribe", params = Nil)
    case GetHeader(height) => JsonRPCRequest(id = reqId, method = "blockchain.block.header", params = height :: Nil)
    case GetHeaders(start_height, count, cp_height) => JsonRPCRequest(id = reqId, method = "blockchain.block.headers", params = start_height :: count :: Nil)
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
        case Ping => PingResponse
        case GetAddressHistory(address) =>
          val JArray(jitems) = json.result
          val items = jitems.map(jvalue => {
            val JString(tx_hash) = jvalue \ "tx_hash"
            val height = intField(jvalue, "height")
            TransactionHistoryItem(height, tx_hash)
          })
          GetAddressHistoryResponse(address, items)
        case GetScriptHashHistory(scripthash) =>
          val JArray(jitems) = json.result
          val items = jitems.map(jvalue => {
            val JString(tx_hash) = jvalue \ "tx_hash"
            val height = intField(jvalue, "height")
            TransactionHistoryItem(height, tx_hash)
          })
          GetScriptHashHistoryResponse(scripthash, items)
        case AddressListUnspent(address) =>
          val JArray(jitems) = json.result
          val items = jitems.map(jvalue => {
            val JString(tx_hash) = jvalue \ "tx_hash"
            val tx_pos = intField(jvalue, "tx_pos")
            val height = intField(jvalue, "height")
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
          val JString(hex) = json.result
          GetHeaderResponse(height, BlockHeader.read(hex))
        case GetHeaders(start_height, count, cp_height) =>
          val count = intField(json.result, "count")
          val max = intField(json.result, "max")
          val JString(hex) = json.result \ "hex"
          val bin = fromHexString(hex)
          val blockHeaders = bin.grouped(80).map(BlockHeader.read).toList
          GetHeadersResponse(start_height, blockHeaders, max)
        case GetMerkle(txid, height) =>
          val JArray(hashes) = json.result \ "merkle"
          val leaves = hashes collect { case JString(value) => BinaryData(value) }
          val blockHeight = intField(json.result, "block_height")
          val JInt(pos) = json.result \ "pos"
          GetMerkleResponse(txid, leaves, blockHeight, pos.toInt)
      }
    }
  }
}
