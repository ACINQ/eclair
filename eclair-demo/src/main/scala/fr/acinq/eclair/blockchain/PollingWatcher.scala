package fr.acinq.eclair.blockchain


import akka.actor.{Cancellable, Actor, ActorLogging}
import akka.pattern.pipe
import fr.acinq.bitcoin._
import fr.acinq.eclair.channel
import fr.acinq.eclair.channel.{Scripts, BITCOIN_ANCHOR_SPENT}
import grizzled.slf4j.Logging
import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST._
import scala.concurrent.{Await, Promise, Future, ExecutionContext}
import scala.concurrent.duration._

/**
  * Simple blockchain watcher that periodically polls bitcoin-core using rpc api
  * /!\ Not scalable /!\
  * Created by PM on 28/08/2015.
  */
class PollingWatcher(client: BitcoinJsonRPCClient)(implicit ec: ExecutionContext = ExecutionContext.global) extends Actor with ActorLogging {

  import fr.acinq.eclair.blockchain.BitcoinRpcClient._

  context.become(watching(Map()))

  override def receive: Receive = ???

  def watching(watches: Map[Watch, Cancellable]): Receive = {
    case w: WatchConfirmedBasedOnOutputs => log.warning(s"ignoring $w (not implemented)")

    case w: WatchLost => log.warning(s"ignoring $w (not implemented)")

    case w: Watch if !watches.contains(w) =>
      log.info(s"adding watch $w for $sender")
      val cancellable = context.system.scheduler.schedule(2 seconds, 10 seconds)(w match {
        case w@WatchConfirmed(channel, txId, minDepth, event) =>
          getTxConfirmations(client, txId.toString).map(_ match {
            case Some(confirmations) if confirmations >= minDepth =>
              channel ! event
              self !('remove, w)
            case _ => {}
          })
        case w@WatchSpent(channel, txId, outputIndex, minDepth, event) =>
          for {
            conf <- getTxConfirmations(client, txId.toString)
            unspent <- isUnspent(client, txId.toString, outputIndex)
          } yield {
            if (conf.isDefined && !unspent) {
              // NOTE : isSpent=!isUnspent only works if the parent transaction actually exists (which we assume to be true)
              findSpendingTransaction(client, txId.toString(), outputIndex).map(tx => channel ! (BITCOIN_ANCHOR_SPENT, tx))
              self !('remove, w)
            } else {}
          }
      })
      context.become(watching(watches + (w -> cancellable)))

    case ('remove, w: Watch) if watches.contains(w) =>
      watches(w).cancel()
      context.become(watching(watches - w))

    case Publish(tx) =>
      log.info(s"publishing tx $tx")
      publishTransaction(client, tx).onFailure {
        case t: Throwable => log.error(t, s"cannot publish tx ${Hex.toHexString(Transaction.write(tx))}")
      }

    case MakeAnchor(ourCommitPub, theirCommitPub, amount) =>
      makeAnchorTx(client, ourCommitPub, theirCommitPub, amount).pipeTo(sender)
  }
}
