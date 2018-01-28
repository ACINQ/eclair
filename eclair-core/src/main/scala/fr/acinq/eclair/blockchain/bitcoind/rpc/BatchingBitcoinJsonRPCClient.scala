package fr.acinq.eclair.blockchain.bitcoind.rpc

import akka.pattern.ask
import akka.actor.{ActorSystem, Props}
import akka.util.Timeout
import org.json4s.JsonAST

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class BatchingBitcoinJsonRPCClient(rpcClient: BitcoinJsonRPCClient)(implicit system: ActorSystem, ec: ExecutionContext) extends BitcoinJsonRPCClient {

  implicit val timeout = Timeout(1 hour)

  val batchingClient = system.actorOf(Props(new BatchingClient(rpcClient)), name = "batching-client")

  override def invoke(request: JsonRPCRequest)(implicit ec: ExecutionContext): Future[JsonAST.JValue] =
    (batchingClient ? request).mapTo[JsonAST.JValue]

  override def invoke(requests: Seq[JsonRPCRequest])(implicit ec: ExecutionContext): Future[Seq[JsonAST.JValue]] =
    (batchingClient ? requests).mapTo[Seq[JsonAST.JValue]]
}
