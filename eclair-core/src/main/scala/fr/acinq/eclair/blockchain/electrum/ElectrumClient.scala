package fr.acinq.eclair.blockchain.electrum

import java.io.InputStream
import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, Stash, Terminated}
import akka.io.{IO, Tcp}
import akka.util.ByteString
import fr.acinq.bitcoin._
import fr.acinq.eclair.Globals
import fr.acinq.eclair.blockchain.CurrentBlockCount
import fr.acinq.eclair.blockchain.bitcoind.rpc.{Error, JsonRPCRequest, JsonRPCResponse}
import org.json4s.JsonAST._
import org.json4s.jackson.JsonMethods
import org.json4s.{DefaultFormats, JInt, JLong, JString}
import org.spongycastle.util.encoders.Hex

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Random

class ElectrumClient(serverAddresses: Seq[InetSocketAddress]) extends Actor with Stash with ActorLogging {

  import ElectrumClient._
  import context.system

  implicit val formats = DefaultFormats

  val newline = "\n"
  val connectionFailures = collection.mutable.HashMap.empty[InetSocketAddress, Long]
  val socketOptions = Tcp.SO.KeepAlive(true) :: Nil

  val version = ServerVersion("2.1.7", "1.1")
  // we need to regularly send a ping in order not to get disconnected
  context.system.scheduler.schedule(30 seconds, 30 seconds, self, version)

  override def unhandled(message: Any): Unit = {
    message match {
      case _: Tcp.ConnectionClosed =>
        val nextAddress = nextPeer()
        log.warning(s"connection failed, trying $nextAddress")
        self ! Tcp.Connect(nextAddress, options = socketOptions)
        statusListeners.map(_ ! ElectrumDisconnected)
        context.system.eventStream.publish(ElectrumDisconnected)
        context become disconnected

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

      case _: ServerVersion => () // we only handle this when connected

      case _: ServerVersionResponse => () // we just ignore these messages, they are used as pings

      case _ => log.warning(s"unhandled $message")
    }
  }

  val statusListeners = collection.mutable.HashSet.empty[ActorRef]

  def send(connection: ActorRef, request: JsonRPCRequest): Unit = {
    import org.json4s.JsonDSL._
    import org.json4s._
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
    val bytes = (serialized + newline).getBytes
    connection ! Tcp.Write(ByteString.fromArray(bytes))
  }

  private def nextPeer() = {
    val nextPos = Random.nextInt(serverAddresses.size)
    serverAddresses(nextPos)
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

  context.system.eventStream.publish(ElectrumDisconnected)
  self ! Tcp.Connect(serverAddresses.head, options = socketOptions)

  var reqId = 0L

  def receive = disconnected

  def disconnected: Receive = {
    case c: Tcp.Connect =>
      log.info(s"connecting to $c")
      IO(Tcp) ! c

    case Tcp.Connected(remote, _) =>
      log.info(s"connected to $remote")
      connectionFailures.clear()
      val connection = sender()
      connection ! Tcp.Register(self)
      val request = version
      send(connection, makeRequest(request, "" + reqId))
      reqId = reqId + 1
      context become waitingForVersion(connection, remote)

    case AddStatusListener(actor) => statusListeners += actor

    case Tcp.CommandFailed(Tcp.Connect(remoteAddress, _, _, _, _)) =>
      val nextAddress = nextPeer()
      log.warning(s"connection to $remoteAddress failed, trying $nextAddress")
      connectionFailures.put(remoteAddress, connectionFailures.getOrElse(remoteAddress, 0L) + 1L)
      val count = connectionFailures.getOrElse(nextAddress, 0L)
      val delay = Math.min(Math.pow(2.0, count), 20.0) seconds;
      context.system.scheduler.scheduleOnce(delay, self, Tcp.Connect(nextAddress, options = socketOptions))
  }

  def waitingForVersion(connection: ActorRef, remote: InetSocketAddress): Receive = {
    case Tcp.Received(data) =>
      val response = parseResponse(new String(data.toArray)).right.get
      val serverVersion = parseJsonResponse(version, response)
      log.debug(s"serverVersion=$serverVersion")
      val request = HeaderSubscription(self)
      send(connection, makeRequest(request, "" + reqId))
      headerSubscriptions += self
      log.debug("waiting for tip")
      reqId = reqId + 1
      context become waitingForTip(connection, remote: InetSocketAddress)

    case AddStatusListener(actor) => statusListeners += actor
  }

  def waitingForTip(connection: ActorRef, remote: InetSocketAddress): Receive = {
    case Tcp.Received(data) =>
      val response = parseResponse(new String(data.toArray)).right.get
      val header = parseHeader(response.result)
      log.debug(s"connected, tip = ${header.block_hash} $header")
      updateBlockCount(header.block_height)
      statusListeners.map(_ ! ElectrumReady)
      context.system.eventStream.publish(ElectrumConnected)
      context become connected(connection, remote, header, "", Map.empty)

    case AddStatusListener(actor) => statusListeners += actor
  }

  def connected(connection: ActorRef, remoteAddress: InetSocketAddress, tip: Header, buffer: String, requests: Map[String, (Request, ActorRef)]): Receive = {
    case AddStatusListener(actor) =>
      statusListeners += actor
      actor ! ElectrumReady

    case HeaderSubscription(actor) =>
      headerSubscriptions += actor
      actor ! HeaderSubscriptionResponse(tip)
      context watch actor

    case request: Request =>
      val curReqId = "" + reqId
      send(connection, makeRequest(request, curReqId))
      request match {
        case AddressSubscription(address, actor) =>
          addressSubscriptions.update(address, addressSubscriptions.getOrElse(address, Set()) + actor)
          context watch actor
        case ScriptHashSubscription(scriptHash, actor) =>
          scriptHashSubscriptions.update(scriptHash, scriptHashSubscriptions.getOrElse(scriptHash, Set()) + actor)
          context watch actor
        case _ => ()
      }
      reqId = reqId + 1
      context become connected(connection, remoteAddress, tip, buffer, requests + (curReqId -> (request, sender())))

    case Tcp.Received(data) =>
      val buffer1 = buffer + new String(data.toArray)
      val (jsons, buffer2) = buffer1.split(newline) match {
        case chunks if buffer1.endsWith(newline) => (chunks, "")
        case chunks => (chunks.dropRight(1), chunks.last)
      }
      jsons.map(parseResponse(_)).map(self ! _)
      context become connected(connection, remoteAddress, tip, buffer2, requests)

    case Right(json: JsonRPCResponse) =>
      requests.get(json.id) match {
        case Some((request, requestor)) =>
          val response = parseJsonResponse(request, json)
          log.debug(s"got response for reqId=${json.id} request=$request response=$response")
          requestor ! response
        case None =>
          log.warning(s"could not find requestor for reqId=${json.id} response=$json")
      }
      context become connected(connection, remoteAddress, tip, buffer, requests - json.id)

    case Left(response: HeaderSubscriptionResponse) => headerSubscriptions.map(_ ! response)

    case Left(response: AddressSubscriptionResponse) => addressSubscriptions.get(response.address).map(listeners => listeners.map(_ ! response))

    case Left(response: ScriptHashSubscriptionResponse) => scriptHashSubscriptions.get(response.scriptHash).map(listeners => listeners.map(_ ! response))

    case HeaderSubscriptionResponse(newtip) =>
      log.info(s"new tip $newtip")
      updateBlockCount(newtip.block_height)
      context become connected(connection, remoteAddress, newtip, buffer, requests)
  }

}

object ElectrumClient {

  def apply(addresses: java.util.List[InetSocketAddress]): ElectrumClient = {
    import collection.JavaConversions._
    new ElectrumClient(addresses)
  }

  /**
    * Utility function to converts a publicKeyScript to electrum's scripthash
    *
    * @param publicKeyScript public key script
    * @return the hash of the public key script, as used by ElectrumX's hash-based methods
    */
  def computeScriptHash(publicKeyScript: BinaryData): BinaryData = Crypto.sha256(publicKeyScript).reverse

  // @formatter:off
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

  case class ServerError(request: Request, error: Error) extends Response
  case class AddStatusListener(actor: ActorRef) extends Response

  sealed trait ElectrumEvent
  case object ElectrumConnected extends ElectrumEvent
  case object ElectrumReady extends ElectrumEvent
  case object ElectrumDisconnected extends ElectrumEvent

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
        case GetMerkle(txid, height) =>
          val JArray(hashes) = json.result \ "merkle"
          val leaves = hashes collect { case JString(value) => BinaryData(value) }
          val blockHeight = longField(json.result, "block_height")
          val JInt(pos) = json.result \ "pos"
          GetMerkleResponse(txid, leaves, blockHeight, pos.toInt)
      }
    }
  }

  def readServerAddresses(stream: InputStream): Seq[InetSocketAddress] = try {
    val JObject(values) = JsonMethods.parse(stream)
    val addresses = values.map {
      case (name, fields) =>
        val JString(port) = fields \ "t"
        new InetSocketAddress(name, port.toInt)
    }
    val randomized = Random.shuffle(addresses)
    randomized
  } finally {
    stream.close()
  }
}
