package fr.acinq.eclair.io

import java.net.InetSocketAddress

import akka.actor._
import akka.io.{IO, Tcp}
import fr.acinq.bitcoin.{BinaryData, Satoshi}
import fr.acinq.eclair.channel.Register.CreateChannel

/**
  * Created by PM on 27/10/2015.
  */
class Client(remote: InetSocketAddress, pubkey: BinaryData, amount: Satoshi, register: ActorRef) extends Actor with ActorLogging {

  import Tcp._
  import context.system

  IO(Tcp) ! Connect(remote)

  def receive = {
    case CommandFailed(_: Connect) => context stop self

    case c@Connected(remote, local) =>
      log.info(s"connected to $remote")
      val connection = sender()
      register ! CreateChannel(connection, Some(pubkey), Some(amount))
    // TODO: kill this actor ?
  }
}

object Client extends App {

  def props(address: InetSocketAddress, pubkey: BinaryData, amount: Satoshi, register: ActorRef): Props = Props(classOf[Client], address, pubkey, amount, register)

  def props(host: String, port: Int, pubkey: BinaryData, amount: Satoshi, register: ActorRef): Props = Props(classOf[Client], new InetSocketAddress(host, port), pubkey, amount, register)

}
