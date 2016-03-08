package fr.acinq.eclair

import akka.actor._
import akka.event.LoggingReceive
import fr.acinq.bitcoin.{BinaryData, Crypto, Hash}
import fr.acinq.eclair.channel.{ChannelSpec, CMD_SEND_HTLC_UPDATE, ChannelOneSide, ChannelState}
import lightning._
import lightning.locktime.Locktime.Blocks

object Simulator extends App {

  case class CreateChannel(from: String, to: String, amount: Long, connection: ActorRef)

  case class Connect(to: String, peer: ActorRef)

  case class Entry(from: String, to: String, id: String, channel: ActorRef)

  class Register(nodeId: String) extends Actor with ActorLogging {
    def receive = running(List.empty[Entry], 0)

    def running(entries: List[Entry], counter: Long): Receive = LoggingReceive({
      case CreateChannel(from, to, amount, connection) =>
        val id = s"id$counter"
        log.info(s"creating channel $from -> $to:$id")
        val channel = if (amount > 0) {
          val pipe = context.actorOf(Props[ChannelSpec.Pipe])
          val channel = context.actorOf(Props(new Channel(from, to, id, pipe, amount)), name = s"$to:$id")
          pipe ! channel
          connection ! CreateChannel(to, from, -amount, pipe)
          channel
        } else {
          val channel = context.actorOf(Props(new Channel(from, to, id, connection, amount)), name = s"$to:$id")
          connection ! channel
          channel
        }
        context watch channel
        context become running(Entry(from, to, id, channel) :: entries, counter + 1)
      case Terminated(actor) =>
        context become running(entries.filterNot(_.channel == actor), counter)
      case cmd: CMD_SEND_HTLC_UPDATE =>
        entries.find(_.to == cmd.nodeIds.head) match {
          case None => log.error(s"cannot process $cmd: no channels to ${cmd.nodeIds.head}")
          case Some(entry) => entry.channel ! cmd
        }
    })
  }

  class Channel(from: String, to: String, id: String, them: ActorRef, amount: Long) extends Actor with ActorLogging {
    val initialState = if(amount > 0)
      ChannelState(us = ChannelOneSide(amount, 0, Seq.empty[update_add_htlc]), them = ChannelOneSide(0, 0, Seq.empty[update_add_htlc]))
    else
      ChannelState(us = ChannelOneSide(0, 0, Seq.empty[update_add_htlc]), them = ChannelOneSide(-amount, 0, Seq.empty[update_add_htlc]))

    def receive = running(initialState)

    def running(state: ChannelState): Receive = LoggingReceive({
      case htlc:update_add_htlc =>
        val data: BinaryData = Hash.Zeroes
        val key: BinaryData = Hash.One
        val sig = Crypto.encodeSignature(Crypto.sign(data, key))
        val revocationHash = Hash.Zeroes
        them ! update_accept(sig, revocationHash)
        log.info(s"becoming waitingForSig($htlc, $state)")
        context become waitingForSig(htlc, state)
      case CMD_SEND_HTLC_UPDATE(amount, rhash, expiry, nodeIds) =>
        val paymentHash = Hash.Zeroes
        val htlc = update_add_htlc(rhash, amount, paymentHash, expiry, nodeIds)
        them ! htlc
        log.info(s"becoming waitingForAccept($htlc, $state)")
        context become waitingForAccept(htlc, state)
      case unexpected =>
        log.warning(s"receive unexpected message $unexpected in running state")
    })

    def waitingForAccept(htlc: update_add_htlc, state: ChannelState) : Receive = LoggingReceive({
      case update_accept(sig, revocationHash) =>
        them ! update_signature(sig, revocationHash)
        log.info(s"becoming waitingForComplete($htlc, $state)")
        context become waitingForComplete(htlc, state)
    })

    def waitingForComplete(htlc: update_add_htlc, state: ChannelState) : Receive = LoggingReceive({
      case update_complete(revocationPreimage) =>
        log.info(s"becoming running(${state.htlc_send(htlc)})")
        context become running(state.htlc_send(htlc))
    })

    def waitingForSig(htlc: update_add_htlc, state: ChannelState): Receive = LoggingReceive({
      case update_signature(theirSig, theirRevocationPreimage) =>
        them ! update_complete(Hash.Zeroes)
        if (!htlc.nodeIds.tail.isEmpty) {
          context.parent ! CMD_SEND_HTLC_UPDATE(htlc.amountMsat, htlc.rHash, htlc.expiry, htlc.nodeIds.tail)
        }
        log.info(s"becoming running(${state.htlc_receive(htlc)})")
        context become running(state.htlc_receive(htlc))
    })
  }

  implicit val system = ActorSystem("mySystem")
  val registerA = system.actorOf(Props(new Register("nodeA")), "nodeA")
  val registerB = system.actorOf(Props(new Register("nodeB")), "nodeB")
  val registerC = system.actorOf(Props(new Register("nodeC")), "nodeC")

  registerA ! CreateChannel("nodeA", "nodeB", 10000, registerB)
  registerB ! CreateChannel("nodeB", "nodeC", 15000, registerC)

  registerA ! CMD_SEND_HTLC_UPDATE(1000, Hash.Zeroes, locktime(Blocks(10)), "nodeB" :: Nil)

  Thread.sleep(5000)

  registerA ! CMD_SEND_HTLC_UPDATE(2000, Hash.Zeroes, locktime(Blocks(10)), "nodeB" :: "nodeC" :: Nil)
}
