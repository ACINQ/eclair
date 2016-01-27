package fr.acinq.eclair.blockchain

import akka.actor.{Cancellable, Actor, ActorLogging}
import fr.acinq.bitcoin.{JsonRPCError, BitcoinJsonRPCClient}
import org.json4s.JsonAST.JNull
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._

/**
  * Simple blockchain watcher that periodically polls bitcoin-core using rpc api
  * /!\ Obviously not scalable /!\
  * Created by PM on 28/08/2015.
  */
class PollingWatcher(client: BitcoinJsonRPCClient)(implicit ec: ExecutionContext = ExecutionContext.global) extends Actor with ActorLogging {

  import PollingWatcher._

  context.become(watching(Map()))

  override def receive: Receive = ???

  def watching(watches: Map[Watch, Cancellable]): Receive = {
    case w: WatchConfirmedBasedOnOutputs => log.warning(s"ignoring $w (not implemented)")

    case w: WatchLost => log.warning(s"ignoring $w (not implemented)")

    case w: Watch if !watches.contains(w) =>
      log.info(s"adding watch $w for $sender")
      val cancellable = context.system.scheduler.schedule(2 seconds, 30 seconds)(w match {
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
            } yield {if (conf.isDefined && !unspent) {
                // NOTE : isSpent=!isUnspent only works if the parent transaction actually exists (which we assume to be true)
                channel ! event
                self !('remove, w)
            } else {}}
        })
      context.become(watching(watches + (w -> cancellable)))

    case ('remove, w: Watch) if watches.contains(w) =>
      watches(w).cancel()
      context.become(watching(watches - w))

    case Publish(tx) =>
      log.info(s"publishing tx $tx")
  }
}

object PollingWatcher {

  implicit val formats = org.json4s.DefaultFormats

  def getTxConfirmations(client: BitcoinJsonRPCClient, txId: String)(implicit ec: ExecutionContext): Future[Option[Int]] =
  client.invoke("getrawtransaction", txId, 1) // we choose verbose output to get the number of confirmations
    .map(json => Some((json \ "confirmations").extract[Int]))
    .recover {
      case t: JsonRPCError if t.code == -5 => None
  }

  def isUnspent(client: BitcoinJsonRPCClient, txId: String, outputIndex: Int)(implicit ec: ExecutionContext): Future[Boolean] =
    client.invoke("gettxout", txId, outputIndex, true) // mempool=true so that we are warned as soon as possible
      .map(json => json != JNull)
}

/*object MyTest extends App {
  import ExecutionContext.Implicits.global
  implicit val formats = org.json4s.DefaultFormats

  val client = new BitcoinJsonRPCClient("foo", "bar")
  println(Await.result(BlockchainWatcher.getTxConfirmations(client, "28ec4853f134c416757cda8ef3243df549c823d02c2aa5e3c148557ab04a2aa8"), 10 seconds))
  println(Await.result(BlockchainWatcher.isUnspent(client, "c1e932badc68b8d07b714ee87b71dadafad3a3c0058266544ae61fd679481d7a", 0), 10 seconds))
}*/
