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
import org.json4s.DefaultFormats
import org.json4s.JsonAST._
import org.json4s.jackson.{JsonMethods, Serialization}
import org.spongycastle.util.encoders.Hex

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

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
    log.debug(s"sending $request")
    val bytes = Serialization.write(request).getBytes ++ newline
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

  val addressSubscriptions = collection.mutable.HashMap.empty[String, ActorRef]
  val scriptHashSubscriptions = collection.mutable.HashMap.empty[BinaryData, ActorRef]
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
      val response = Serialization.read[JsonRPCResponse](new String(data.toArray))
      log.debug(s"received $response")
      send(connection, JsonRPCRequest("blockchain.headers.subscribe", params = Nil))
      log.debug("waiting for tip")
      context become waitingForTip(connection, remote: InetSocketAddress)

    case AddStatusListener(actor) => statusListeners += actor
  }

  def waitingForTip(connection: ActorRef, remote: InetSocketAddress): Receive = {
    case Received(data) =>
      val response = Serialization.read[JsonRPCResponse](new String(data.toArray))
      val header = response.result.extract[Header]
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

    case request: ElectrumXRequest =>
      send(connection, makeRequest(request))
      request match {
        case AddressSubscription(address, actor) =>
          addressSubscriptions += (address -> actor)
          context watch actor
        case ScriptHashSubscription(scriptHash, actor) =>
          scriptHashSubscriptions += (scriptHash -> actor)
          context watch actor
        case _ => ()
      }
      context.become(waitingForAnswer(connection, request, sender(), ""), false)

    case Received(data) =>
      val buffer1 = buffer + new String(data.toArray)
      if (buffer1.endsWith("\n")) {
        buffer1.split("\n").map(json => self ! Serialization.read[SubscriptionResponse](json))
        context become connected(connection, remoteAddress, tip, "")
      } else {
        context become connected(connection, remoteAddress, tip, buffer1)
      }

    case SubscriptionResponse("blockchain.headers.subscribe", header :: Nil, _) =>
      val newtip = header.extract[Header]
      log.info(s"new tip $newtip")
      updateBlockCount(newtip.block_height)
      headerSubscriptions.map(_ ! HeaderSubscriptionResponse(newtip))
      context become connected(connection, remoteAddress, newtip, buffer)

    case SubscriptionResponse("blockchain.address.subscribe", JString(address) :: JNull :: Nil, _) =>
      log.info(s"address $address changed, status is empty")
      addressSubscriptions.get(address).map(_ ! AddressSubscriptionResponse(address, ""))

    case SubscriptionResponse("blockchain.address.subscribe", JString(address) :: JString(status) :: Nil, _) =>
      log.info(s"address $address changed, new status $status")
      addressSubscriptions.get(address).map(_ ! AddressSubscriptionResponse(address, status))

    case SubscriptionResponse("blockchain.scripthash.subscribe", JString(scriptHashHex) :: JNull :: Nil, _) =>
      val scriptHash = BinaryData(scriptHashHex)
      log.info(s"script $scriptHash changed, status is empty")
      scriptHashSubscriptions.get(scriptHash).map(_ ! ScriptHashSubscriptionResponse(scriptHash, ""))

    case SubscriptionResponse("blockchain.scripthash.subscribe", JString(scriptHashHex) :: JString(status) :: Nil, _) =>
      val scriptHash = BinaryData(scriptHashHex)
      log.info(s"script $scriptHash, new status $status")
      scriptHashSubscriptions.get(scriptHash).map(_ ! ScriptHashSubscriptionResponse(scriptHash, status))

    case PeerClosed =>
      val pos = serverAddresses.indexWhere(_ == remoteAddress)
      val nextPos = (pos + 1) % serverAddresses.size
      val nextAddress = serverAddresses(nextPos)
      log.warning(s"connection to $remoteAddress failed, trying $nextAddress")
      self ! Connect(nextAddress)
      statusListeners.map(_ ! Disconnected)
      context become disconnected
  }

  def waitingForAnswer(connection: ActorRef, request: ElectrumXRequest, replyTo: ActorRef, buffer: String): Receive = {
    case Received(data) =>
      val buffer1 = buffer + new String(data.toArray)
      if (buffer1.endsWith("\n")) {
        val response = ElectrumClient.parseResponse(request, buffer1)
        replyTo ! response
        unstashAll()
        context unbecome()
      } else {
        context become waitingForAnswer(connection, request, replyTo, buffer1)
      }
    case _ => stash()
  }
}

object ElectrumClient {

  // @formatter:off
  sealed trait ElectrumXRequest
  sealed trait ElectrumxReponse

  case class GetAddressHistory(address: String) extends ElectrumXRequest
  case class TransactionHistoryItem(height: Long, tx_hash: String)
  case class GetAddressHistoryResponse(address: String, history: Seq[TransactionHistoryItem]) extends ElectrumxReponse

  case class GetScriptHashHistory(scriptHash: BinaryData) extends ElectrumXRequest
  case class GetScriptHashHistoryResponse(scriptHash: BinaryData, history: Seq[TransactionHistoryItem]) extends ElectrumxReponse

  case class AddressListUnspent(address: String) extends ElectrumXRequest
  case class UnspentItem(tx_hash: String, tx_pos: Int, value: Long, height: Long)
  case class AddressListUnspentResponse(address: String, unspents: Seq[UnspentItem]) extends ElectrumxReponse

  case class ScriptHashListUnspent(scriptHash: BinaryData) extends ElectrumXRequest
  case class ScriptHashListUnspentResponse(scriptHash: BinaryData, unspents: Seq[UnspentItem]) extends ElectrumxReponse

  case class BroadcastTransaction(tx: Transaction) extends ElectrumXRequest
  case class BroadcastTransactionResponse(tx: Transaction, error: Option[String]) extends ElectrumxReponse

  case class GetTransaction(txid: String) extends ElectrumXRequest
  case class GetTransactionResponse(tx: Transaction) extends ElectrumxReponse

  case class GetMerkle(txid: String, height: Long) extends ElectrumXRequest
  case class GetMerkleResponse(txid: String, merkle: Seq[String], block_height: Long, pos: Int) extends ElectrumxReponse

  case class AddressSubscription(address: String, actor: ActorRef) extends ElectrumXRequest
  case class AddressSubscriptionResponse(address: String, status: String) extends ElectrumxReponse

  case class ScriptHashSubscription(scriptHash: BinaryData, actor: ActorRef) extends ElectrumXRequest
  case class ScriptHashSubscriptionResponse(scriptHash: BinaryData, status: String) extends ElectrumxReponse

  case class HeaderSubscription(actor: ActorRef) extends ElectrumXRequest
  case class HeaderSubscriptionResponse(header: Header) extends ElectrumxReponse

  case class Header(block_height: Long, version: Long, prev_block_hash: String, merkle_root: String, timestamp: Long, bits: Long, nonce: Long) {
    lazy val block_hash: BinaryData = {
      val blockHeader = BlockHeader(version, BinaryData(prev_block_hash).reverse, BinaryData(merkle_root).reverse, timestamp, bits, nonce)
      blockHeader.hash.reverse
    }
  }
  case class TransactionHistory(history: Seq[TransactionHistoryItem]) extends ElectrumxReponse

  case class AddressStatus(address: String, status: String) extends ElectrumxReponse

  case class ServerError(request: ElectrumXRequest, error: String) extends ElectrumxReponse
  case class AddStatusListener(actor: ActorRef) extends ElectrumxReponse

  case object Ready
  case object Disconnected
  // @formatter:on

  case class SubscriptionResponse(method: String, params: List[JValue], jsonrpc: String)

  def makeRequest(request: ElectrumXRequest): JsonRPCRequest = request match {
    case GetAddressHistory(address) => JsonRPCRequest("blockchain.address.get_history", address :: Nil)
    case GetScriptHashHistory(scripthash) => JsonRPCRequest("blockchain.scripthash.get_history", scripthash.toString() :: Nil)
    case AddressListUnspent(address) => JsonRPCRequest("blockchain.address.listunspent", address :: Nil)
    case ScriptHashListUnspent(scripthash) => JsonRPCRequest("blockchain.scripthash.listunspent", scripthash.toString() :: Nil)
    case AddressSubscription(address, _) => JsonRPCRequest("blockchain.address.subscribe", address :: Nil)
    case ScriptHashSubscription(scriptHash, _) => JsonRPCRequest("blockchain.scripthash.subscribe", scriptHash.toString() :: Nil)
    case BroadcastTransaction(tx) => JsonRPCRequest("blockchain.transaction.broadcast", Hex.toHexString(Transaction.write(tx)) :: Nil)
    case GetTransaction(txid: String) => JsonRPCRequest("blockchain.transaction.get", txid :: Nil)
    case HeaderSubscription(_) => JsonRPCRequest("blockchain.headers.subscribe", params = Nil)
    case GetMerkle(txid, height) => JsonRPCRequest("blockchain.transaction.get_merkle", txid :: height :: Nil)
  }

  def parseResponse(request: ElectrumXRequest, response: String): ElectrumxReponse = {
    implicit val formats = DefaultFormats
    val json = Serialization.read[JsonRPCResponse](response)
    json.error match {
      case Some(error) => ServerError(request, error.message)
      case None => request match {
        case GetAddressHistory(address) => GetAddressHistoryResponse(address, json.result.extract[Seq[TransactionHistoryItem]])
        case GetScriptHashHistory(scripthash) => GetScriptHashHistoryResponse(scripthash, json.result.extract[Seq[TransactionHistoryItem]])
        case AddressListUnspent(address) => AddressListUnspentResponse(address, json.result.extract[Seq[UnspentItem]])
        case ScriptHashListUnspent(scripthash) => ScriptHashListUnspentResponse(scripthash, json.result.extract[Seq[UnspentItem]])
        case GetTransaction(_) => GetTransactionResponse(Transaction.read(json.result.extract[String]))
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
          // JObject(List((pos,JInt(1)), (merkle,JArray(List(JString(fa4f39f5a6df91539cad1e216161c52c9625325a993df744748b5b3d54bd4ae0)))), (block_height,JInt(4174))))
          val JArray(hashes) = json.result \ "merkle"
          val leaves = hashes collect { case JString(value) => value }
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