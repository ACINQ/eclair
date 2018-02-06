package fr.acinq.eclair.blockchain.bitcoind.rpc

import java.io.IOException

import org.json4s.JsonAST.JValue

import scala.concurrent.{ExecutionContext, Future}

trait BitcoinJsonRPCClient {

  def invoke(method: String, params: Any*)(implicit ec: ExecutionContext): Future[JValue]

}

// @formatter:off
case class JsonRPCRequest(jsonrpc: String = "1.0", id: String = "scala-client", method: String, params: Seq[Any])
case class Error(code: Int, message: String)
case class JsonRPCResponse(result: JValue, error: Option[Error], id: String)
case class JsonRPCError(error: Error) extends IOException(s"${error.message} (code: ${error.code})")
// @formatter:on