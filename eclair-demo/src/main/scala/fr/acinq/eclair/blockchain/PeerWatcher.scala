package fr.acinq.eclair.blockchain

import akka.actor.{Props, Actor, ActorLogging}
import akka.pattern.pipe
import fr.acinq.bitcoin._
import fr.acinq.eclair.blockchain.peer.PeerClient
import fr.acinq.eclair.channel.BITCOIN_ANCHOR_SPENT
import org.bouncycastle.util.encoders.Hex

import scala.concurrent.ExecutionContext

/**
  * A blockchain watcher that:
  * - connects directly to the bitcoin network and listens to new tx
  * - periodically polls bitcoin-core using rpc api
  * Created by PM on 21/02/2016.
  */
class PeerWatcher(client: ExtendedBitcoinClient)(implicit ec: ExecutionContext = ExecutionContext.global) extends Actor with ActorLogging {

  context.actorOf(Props(classOf[PeerClient], self))
  context.become(watching(Set[Watch]()))

  override def receive: Receive = ???

  def watching(watches: Set[Watch]): Receive = {

    case tx: Transaction =>
      watches.foreach {
        _ match {
          case w@WatchSpent(channel, txid, outputIndex, minDepth, event)
            if tx.txIn.exists(i => i.outPoint.hash == BinaryData(txid.reverse) && i.outPoint.index == outputIndex) =>
            channel ! (BITCOIN_ANCHOR_SPENT, tx)
            self !('remove, w)
          case _ => {}
        }
      }

    case block: Block =>
      // TODO : beware of the herd effect
      watches.foreach {
        _ match {
          case w@WatchConfirmed(channel, txId, minDepth, event) =>
            client.getTxConfirmations(txId.toString).map(_ match {
              case Some(confirmations) if confirmations >= minDepth =>
                channel ! event
                self !('remove, w)
              case _ => {}
            })
          case _ => {}
        }
      }

    case w: WatchLost => log.warning(s"ignoring $w (not implemented)")

    case w: Watch if !watches.contains(w) =>
      log.info(s"adding watch $w for $sender")
      context.become(watching(watches + w))

    case ('remove, w: Watch) if watches.contains(w) =>
      context.become(watching(watches - w))

    case Publish(tx) =>
      log.info(s"publishing tx $tx")
      client.publishTransaction(tx).onFailure {
        case t: Throwable => log.error(t, s"cannot publish tx ${BinaryData(Transaction.write(tx))}")
      }

    case MakeAnchor(ourCommitPub, theirCommitPub, amount) =>
      client.makeAnchorTx(ourCommitPub, theirCommitPub, amount).pipeTo(sender)
  }
}
