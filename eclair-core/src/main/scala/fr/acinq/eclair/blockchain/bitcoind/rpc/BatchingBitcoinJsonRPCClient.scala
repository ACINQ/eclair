package fr.acinq.eclair.blockchain.bitcoind.rpc

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import org.json4s.JsonAST

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class BatchingBitcoinJsonRPCClient(rpcClient: BasicBitcoinJsonRPCClient)(implicit system: ActorSystem, ec: ExecutionContext) extends BitcoinJsonRPCClient {

  implicit val timeout = Timeout(1 hour)

  val batchingClient = system.actorOf(Props(new BatchingClient(rpcClient)), name = "batching-client")

  override def invoke(method: String, params: Any*)(implicit ec: ExecutionContext): Future[JsonAST.JValue] =
    (batchingClient ? JsonRPCRequest(method = method, params = params)).mapTo[JsonAST.JValue]
}
