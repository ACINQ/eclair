package fr.acinq.eclair.blockchain.peer

import java.net.{InetAddress, InetSocketAddress}

import akka.actor._
import akka.io.Tcp.Connected
import akka.pattern.{Backoff, BackoffSupervisor}
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin._

import scala.compat.Platform
import scala.concurrent.duration._

class PeerClient extends Actor with ActorLogging {

  val config = ConfigFactory.load().getConfig("eclair.bitcoind")
  val magic = config.getString("network") match {
    case "mainnet" => Message.MagicMain
    case "testnet" => Message.MagicTestnet3
    case "regtest" => Message.MagicTestNet
  }
  val peer = new InetSocketAddress(config.getString("host"), config.getInt("port"))
  val supervisor = BackoffSupervisor.props(
    Backoff.onStop(
      Props(classOf[PeerHandler], peer, self),
      childName = "peer-conn",
      minBackoff = 1 seconds,
      maxBackoff = 10 seconds,
      randomFactor = 0.2 // adds 20% "noise" to vary the intervals slightly
    ))
  context.actorOf(supervisor, name = "peer-supervisor")

  override def receive: Actor.Receive = {
    case Connected(remote, local) =>
      val version = Version(
        70015L,
        services = 1L | (1 << 3),
        timestamp = Platform.currentTime / 1000,
        addr_recv = NetworkAddress(1L, local.getAddress, local.getPort.toLong),
        addr_from = NetworkAddress(1L, remote.getAddress, remote.getPort.toLong),
        nonce = 0x4317be39ae6ea291L,
        user_agent = "eclair:alpha",
        start_height = 0x0L,
        relay = true)
      sender ! Message(magic, command = "version", payload = Version.write(version))
    case Message(magic, "version", payload) =>
      val version = Version.read(payload)
      log.debug(s"received $version")
      sender ! Message(magic, "verack", Array.empty[Byte])
      sender ! Message(magic, "mempool", Array.empty[Byte])
      val inventory = Inventory(InventoryVector(1, BinaryData("3545255e0755f7b39115f1da928fd388291c135dbcbf0f45a0563a02a2449d32").reverse) :: InventoryVector(1, BinaryData("124ba11b7ca3299b9f74f83baafd6db154cddc313e99aa50bbbc01c7f91eb411").reverse) :: InventoryVector(1, ("ee75fd2a7bbdf0bbbe363ebb6e2285ee0d5e2240c0cba088139eb49545203646").reverse) :: Nil)
      import scala.concurrent.ExecutionContext.Implicits.global
      context.system.scheduler.scheduleOnce(15 seconds, sender, Message(magic, "getdata", Inventory.write(inventory)))
    case Message(magic, "verack", _) =>
      log.debug("received verack")
    case Message(magic, "inv", payload) =>
      val inventory = Inventory.read(payload)
      log.debug(s"received $inventory")
      // see https://github.com/bitcoin/bips/blob/master/bip-0144.mediawiki: request tx with witness
      val inventory1 = Inventory(inventory.inventory.map(iv => iv.`type` match {
        case 1 => iv.copy(`type` = 0x40000001)
        case _ => iv
      }))
      sender ! Message(magic, "getdata", Inventory.write(inventory1))
    case Message(magic, "ping", payload) =>
      log.debug(s"received a ping")
      sender ! Message(magic, "pong", payload)
    case Message(magic, "tx", payload) =>
      val tx = Transaction.read(payload)
      log.debug(s"received tx ${tx.txid}")
      context.system.eventStream.publish(NewTransaction(tx))
    case Message(magic, "block", payload) =>
      log.debug(s"received block ${toHexString(payload)}")
      context.system.eventStream.publish(NewBlock(Block.read(payload)))
    case Message(magic, "notfound", payload) =>
      val inventory = Inventory.read(payload)
      log.debug(s"received notfound for inv $inventory")
    case Message(magic, command, payload) =>
      log.debug(s"received unknown $command ${toHexString(payload)}")
  }
}

object PeerClientTest extends App {

  val system = ActorSystem()
  val peer = system.actorOf(Props[PeerClient], name = "peer")
  val listener = system.actorOf(Props(new Actor {
    override def receive: Receive = {
      case msg => println(msg)
    }
  }), name = "listener")
  system.eventStream.subscribe(listener, classOf[BlockchainEvent])

}

object Test extends App {

  val local = InetAddress.getLocalHost
  val remote = InetAddress.getLocalHost
  val version = Version(
    70015L,
    services = 1L,
    timestamp = Platform.currentTime / 1000,
    addr_recv = NetworkAddress(1L, local, 42000),
    addr_from = NetworkAddress(1L, remote, 42001),
    nonce = 0x4317be39ae6ea291L,
    user_agent = "eclair:alpha",
    start_height = 0x0L,
    relay = true)
  val msg = Message(Message.MagicTestnet3, command = "version", payload = Version.write(version))
  val bin = Message.write(msg) ++ Message.write(msg)

  val msg1 = Message.read(bin)
  println(msg1)

}
