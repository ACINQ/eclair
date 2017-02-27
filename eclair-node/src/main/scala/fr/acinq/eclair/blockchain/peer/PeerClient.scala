package fr.acinq.eclair.blockchain.peer

import java.net.InetSocketAddress

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
    case "test" => Message.MagicTestnet3
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
      log.debug(s"received tx ${toHexString(payload)}")
      context.system.eventStream.publish(NewTransaction(Transaction.read(payload)))
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

