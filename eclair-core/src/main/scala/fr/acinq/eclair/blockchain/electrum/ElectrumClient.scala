package fr.acinq.eclair.blockchain.electrum

import java.io.InputStream
import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, Stash, Terminated}
import akka.io.{IO, Tcp}
import akka.io.Tcp.{PeerClosed, _}
import akka.util.ByteString
import fr.acinq.bitcoin._
import fr.acinq.eclair.Globals
import fr.acinq.eclair.blockchain.CurrentBlockCount
import fr.acinq.eclair.blockchain.rpc.{JsonRPCRequest, JsonRPCResponse}
import org.json4s.{DefaultFormats, JInt, JLong, JString}
import org.json4s.JsonAST._
import org.json4s.jackson.{JsonMethods, Serialization}
import org.spongycastle.util.encoders.Hex

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Success, Try}

class ElectrumClient(serverAddresses: Seq[InetSocketAddress]) extends Actor with Stash with ActorLogging {

  import ElectrumClient._
  import context.system

  implicit val formats = DefaultFormats

  val newline = "\n".getBytes
  val connectionFailures = collection.mutable.HashMap.empty[InetSocketAddress, Long]

  override def unhandled(message: Any): Unit = {
    message match {
      case Terminated(deadActor) =>
        val removeMe = addressSubscriptions collect {
          case (address, actor) if actor == deadActor => address
        }
        addressSubscriptions --= removeMe

        val removeMe1 = scriptHashSubscriptions collect {
          case (scriptHash, actor) if actor == deadActor => scriptHash
        }
        scriptHashSubscriptions --= removeMe1
        statusListeners -= deadActor
        headerSubscriptions -= deadActor

      case _ => log.warning(s"unhandled $message")
    }
  }

  val statusListeners = collection.mutable.HashSet.empty[ActorRef]

  def send(connection: ActorRef, request: JsonRPCRequest): Unit = {
    import org.json4s._
    import org.json4s.JsonDSL._
    import org.json4s.jackson.JsonMethods._

    log.debug(s"sending $request")
    val json = ("method" -> request.method) ~ ("params" -> request.params.map {
      case s: String => new JString(s)
      case b: BinaryData => new JString(b.toString())
      case t: Int => new JInt(t)
      case t: Long => new JLong(t)
      case t: Double => new JDouble(t)
    }) ~ ("id" -> request.id) ~ ("jsonrpc" -> request.jsonrpc)
    val serialized = compact(render(json))
    val bytes = serialized.getBytes ++ newline
    connection ! Write(ByteString.fromArray(bytes))
  }

  private def updateBlockCount(blockCount: Long) = {
    // when synchronizing we don't want to advertise previous blocks
    if (Globals.blockCount.get() < blockCount) {
      log.debug(s"current blockchain height=$blockCount")
      system.eventStream.publish(CurrentBlockCount(blockCount))
      Globals.blockCount.set(blockCount)
    }
  }

  val addressSubscriptions = collection.mutable.HashMap.empty[String, Set[ActorRef]]
  val scriptHashSubscriptions = collection.mutable.HashMap.empty[BinaryData, Set[ActorRef]]
  val headerSubscriptions = collection.mutable.HashSet.empty[ActorRef]

  self ! Connect(serverAddresses.head)

  def receive = disconnected

  def disconnected: Receive = {
    case c: Connect =>
      log.info(s"connecting to $c")
      IO(Tcp) ! c

    case Connected(remote, local) =>
      log.info(s"connected to $remote")
      connectionFailures.clear()
      val connection = sender()
      connection ! Register(self)
      send(connection, JsonRPCRequest("server.version", "2.1.7" :: "1.1" :: Nil))
      context become waitingForVersion(connection, remote)

    case AddStatusListener(actor) => statusListeners += actor

    case CommandFailed(Connect(remoteAddress, _, _, _, _)) =>
      val pos = serverAddresses.indexWhere(_ == remoteAddress)
      val nextPos = (pos + 1) % serverAddresses.size
      val nextAddress = serverAddresses(nextPos)
      log.warning(s"connection to $remoteAddress failed, trying $nextAddress")
      connectionFailures.put(remoteAddress, connectionFailures.getOrElse(remoteAddress, 0L) + 1L)
      val count = connectionFailures.getOrElse(nextAddress, 0L)
      val delay = Math.min(Math.pow(2.0, count), 60.0) seconds

      context.system.scheduler.scheduleOnce(delay, self, Connect(nextAddress))
  }

  def waitingForVersion(connection: ActorRef, remote: InetSocketAddress): Receive = {
    case Received(data) =>
      val response = parseJsonRpcResonse(new String(data.toArray))
      log.debug(s"received $response")
      send(connection, JsonRPCRequest("blockchain.headers.subscribe", params = Nil))
      log.debug("waiting for tip")
      context become waitingForTip(connection, remote: InetSocketAddress)

    case AddStatusListener(actor) => statusListeners += actor
  }

  def waitingForTip(connection: ActorRef, remote: InetSocketAddress): Receive = {
    case Received(data) =>
      val response = parseJsonRpcResonse(new String(data.toArray))
      val header = parseHeader(response.result)
      log.debug(s"connected, tip = ${header.block_hash} $header")
      updateBlockCount(header.block_height)
      statusListeners.map(_ ! Ready)
      context become connected(connection, remote, header, "")

    case AddStatusListener(actor) => statusListeners += actor
  }

  def connected(connection: ActorRef, remoteAddress: InetSocketAddress, tip: Header, buffer: String): Receive = {
    case AddStatusListener(actor) =>
      statusListeners += actor
      actor ! Ready

    case HeaderSubscription(actor) =>
      headerSubscriptions += actor
      actor ! HeaderSubscriptionResponse(tip)
      context watch actor

    case request: Request =>
      send(connection, makeRequest(request))
      request match {
        case AddressSubscription(address, actor) =>
          addressSubscriptions.update(address, addressSubscriptions.getOrElse(address, Set()) + actor)
          context watch actor
        case ScriptHashSubscription(scriptHash, actor) =>
          scriptHashSubscriptions.update(scriptHash, scriptHashSubscriptions.getOrElse(scriptHash, Set()) + actor)
          context watch actor
        case _ => ()
      }
      context.become(waitingForAnswer(connection, request, sender(), ""), false)

    case Received(data) =>
      val buffer1 = buffer + new String(data.toArray)
      if (buffer1.endsWith("\n")) {
        buffer1.split("\n").map(json => self ! parseSubscriptionResponse(json))
        context become connected(connection, remoteAddress, tip, "")
      } else {
        context become connected(connection, remoteAddress, tip, buffer1)
      }

    case SubscriptionResponse("blockchain.headers.subscribe", header :: Nil, _) =>
      val newtip = parseHeader(header)
      log.info(s"new tip $newtip")
      updateBlockCount(newtip.block_height)
      headerSubscriptions.map(_ ! HeaderSubscriptionResponse(newtip))
      context become connected(connection, remoteAddress, newtip, buffer)

    case SubscriptionResponse("blockchain.address.subscribe", JString(address) :: JNull :: Nil, _) =>
      log.info(s"address $address changed, status is empty")
      addressSubscriptions.get(address).map(listeners => listeners.map(_ ! AddressSubscriptionResponse(address, "")))

    case SubscriptionResponse("blockchain.address.subscribe", JString(address) :: JString(status) :: Nil, _) =>
      log.info(s"address $address changed, new status $status")
      addressSubscriptions.get(address).map(listeners => listeners.map(_ ! AddressSubscriptionResponse(address, status)))

    case SubscriptionResponse("blockchain.scripthash.subscribe", JString(scriptHashHex) :: JNull :: Nil, _) =>
      val scriptHash = BinaryData(scriptHashHex)
      log.info(s"script $scriptHash changed, status is empty")
      scriptHashSubscriptions.get(scriptHash).map(listeners => listeners.map(_ ! ScriptHashSubscriptionResponse(scriptHash, "")))

    case SubscriptionResponse("blockchain.scripthash.subscribe", JString(scriptHashHex) :: JString(status) :: Nil, _) =>
      val scriptHash = BinaryData(scriptHashHex)
      log.info(s"script $scriptHash, new status $status")
      scriptHashSubscriptions.get(scriptHash).map(listeners => listeners.map(_ ! ScriptHashSubscriptionResponse(scriptHash, status)))

    case PeerClosed =>
      val pos = serverAddresses.indexWhere(_ == remoteAddress)
      val nextPos = (pos + 1) % serverAddresses.size
      val nextAddress = serverAddresses(nextPos)
      log.warning(s"connection to $remoteAddress failed, trying $nextAddress")
      self ! Connect(nextAddress)
      statusListeners.map(_ ! Disconnected)
      context become disconnected
  }

  def waitingForAnswer(connection: ActorRef, request: Request, replyTo: ActorRef, buffer: String): Receive = {
    case Received(data) =>
      val buffer1 = buffer + new String(data.toArray)
      if (buffer1.endsWith("\n")) {
        buffer1.split("\n").map(json => Try(ElectrumClient.parseResponse(request, json))).collectFirst {
          case Success(response: ElectrumClient.Response) => response
        } match {
          case Some(response) =>
            replyTo ! response
            unstashAll()
            context unbecome()
          case None =>
            context become (waitingForAnswer(connection, request, replyTo, buffer1))
        }
      } else context become (waitingForAnswer(connection, request, replyTo, buffer1))
    case _ => stash()
  }
}

object ElectrumClient {

  def apply(addresses: java.util.List[InetSocketAddress]) : ElectrumClient = {
    import collection.JavaConversions._
    new ElectrumClient(addresses)
  }

  // @formatter:off
  sealed trait Request
  sealed trait Response

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
  case class BroadcastTransactionResponse(tx: Transaction, error: Option[String]) extends Response

  case class GetTransaction(txid: BinaryData) extends Request
  case class GetTransactionResponse(tx: Transaction) extends Response

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
    lazy val block_hash: BinaryData = {
      val blockHeader = BlockHeader(version, prev_block_hash.reverse, merkle_root.reverse, timestamp, bits, nonce)
      blockHeader.hash.reverse
    }
  }

  object Header {
    def makeHeader(height: Long, header: BlockHeader) = ElectrumClient.Header(0, header.version, header.hashPreviousBlock, header.hashMerkleRoot, header.time, header.bits, header.nonce)

    val RegtestGenesisHeader = makeHeader(0, Block.RegtestGenesisBlock.header)
    val TestnetGenesisHeader = makeHeader(0, Block.TestnetGenesisBlock.header)
  }

  case class TransactionHistory(history: Seq[TransactionHistoryItem]) extends Response

  case class AddressStatus(address: String, status: String) extends Response

  case class ServerError(request: Request, error: String) extends Response
  case class AddStatusListener(actor: ActorRef) extends Response

  case object Ready
  case object Disconnected
  // @formatter:on

  case class SubscriptionResponse(method: String, params: List[JValue], jsonrpc: String)

  def parseSubscriptionResponse(input: String) : SubscriptionResponse = {
    implicit val formats = DefaultFormats
    val json = JsonMethods.parse(new String(input))
    val JString(method) = json \ "method"
    val jsonrpc = json \ "jsonrpc" match {
      case JString(value) => value
      case _ => ""
    }
    val JArray(params) = json \ "params"
    SubscriptionResponse(method, params, jsonrpc)
  }

  def parseJsonRpcResonse(input: String) : JsonRPCResponse = {
    implicit val formats = DefaultFormats
    val json = JsonMethods.parse(new String(input))
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
        Some(fr.acinq.eclair.blockchain.rpc.Error(code, message))
    }
    val id = json \ " id" match {
      case JString(value) => value
      case JInt(value) => value.toString()
      case JLong(value) => value.toString
      case _ => ""
    }
    JsonRPCResponse(result, error, id)
  }

  def longField(jvalue: JValue, field: String): Long = jvalue \ field match {
    case JLong(value) => value.longValue()
    case JInt(value) => value.longValue()
  }

  def intField(jvalue: JValue, field: String): Int = jvalue \ field match {
    case JLong(value) => value.intValue()
    case JInt(value) => value.intValue()
  }

  def parseHeader(json: JValue) : Header = {
    val block_height = longField(json, "block_height")
    val version = longField(json, "version")
    val timestamp = longField(json, "timestamp")
    val bits = longField(json, "bits")
    val nonce = longField(json, "nonce")
    val JString(prev_block_hash) = json \ "prev_block_hash"
    val JString(merkle_root) = json \ "merkle_root"
    Header(block_height, version, prev_block_hash, merkle_root, timestamp, bits, nonce)
  }

  def makeRequest(request: Request): JsonRPCRequest = request match {
    case GetAddressHistory(address) => JsonRPCRequest("blockchain.address.get_history", address :: Nil)
    case GetScriptHashHistory(scripthash) => JsonRPCRequest("blockchain.scripthash.get_history", scripthash.toString() :: Nil)
    case AddressListUnspent(address) => JsonRPCRequest("blockchain.address.listunspent", address :: Nil)
    case ScriptHashListUnspent(scripthash) => JsonRPCRequest("blockchain.scripthash.listunspent", scripthash.toString() :: Nil)
    case AddressSubscription(address, _) => JsonRPCRequest("blockchain.address.subscribe", address :: Nil)
    case ScriptHashSubscription(scriptHash, _) => JsonRPCRequest("blockchain.scripthash.subscribe", scriptHash.toString() :: Nil)
    case BroadcastTransaction(tx) => JsonRPCRequest("blockchain.transaction.broadcast", Hex.toHexString(Transaction.write(tx)) :: Nil)
    case GetTransaction(txid: BinaryData) => JsonRPCRequest("blockchain.transaction.get", txid :: Nil)
    case HeaderSubscription(_) => JsonRPCRequest("blockchain.headers.subscribe", params = Nil)
    case GetMerkle(txid, height) => JsonRPCRequest("blockchain.transaction.get_merkle", txid :: height :: Nil)
  }

  def parseResponse(request: Request, response: String): Response = {
    implicit val formats = DefaultFormats
    val json = parseJsonRpcResonse(response)

    json.error match {
      case Some(error) => ServerError(request, error.message)
      case None => request match {
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
        case GetMerkle(txid, height) =>
          val JArray(hashes) = json.result \ "merkle"
          val leaves = hashes collect { case JString(value) => BinaryData(value) }
          val blockHeight: Long = json.result \ "block_height" match {
            case JInt(value) => value.longValue()
            case JLong(value) => value
          }
          val JInt(pos) = json.result \ "pos"
          GetMerkleResponse(txid, leaves, blockHeight, pos.toInt)
      }
    }
  }

  def readServerAddresses(stream: InputStream): Seq[InetSocketAddress] = {
    val JObject(values) = JsonMethods.parse(stream)
    values.map {
      case (name, fields) =>
        val JString(port) = fields \ "t"
        new InetSocketAddress(name, port.toInt)
    }
  }
}