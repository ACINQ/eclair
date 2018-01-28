package fr.acinq.eclair.blockchain.bitcoind.rpc

import org.json4s.JsonAST.JValue

import scala.concurrent.{ExecutionContext, Future}

trait BitcoinJsonRPCClient {

  def invoke(method: String, params: Any*)(implicit ec: ExecutionContext): Future[JValue] =
    invoke(JsonRPCRequest(method = method, params = params))

  def invoke(request: JsonRPCRequest)(implicit ec: ExecutionContext): Future[JValue]

  def invoke(requests: Seq[JsonRPCRequest])(implicit ec: ExecutionContext): Future[Seq[JValue]]

}
