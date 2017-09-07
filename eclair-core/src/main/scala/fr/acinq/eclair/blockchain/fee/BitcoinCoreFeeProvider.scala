package fr.acinq.eclair.blockchain.fee
import fr.acinq.bitcoin.Btc
import fr.acinq.eclair.blockchain.rpc.BitcoinJsonRPCClient
import org.json4s.JsonAST.{JDouble, JInt}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by PM on 09/07/2017.
  */
class BitcoinCoreFeeProvider(rpcClient: BitcoinJsonRPCClient, defaultFeeratePerKB: Long)(implicit ec: ExecutionContext) extends FeeProvider {

  /**
    * We need this to keep commitment tx fees in sync with the state of the network
    *
    * @param nBlocks number of blocks until tx is confirmed
    * @return the current
    */
  def estimateSmartFee(nBlocks: Int): Future[Long] =
    rpcClient.invoke("estimatesmartfee", nBlocks).map(json => {
      json \ "feerate" match {
        case JDouble(feerate) => Btc(feerate).toLong
        case JInt(feerate) if feerate.toLong < 0 => feerate.toLong
        case JInt(feerate) => Btc(feerate.toLong).toLong
      }
    })

  override def getFeeratePerKB: Future[Long] = estimateSmartFee(3).map {
    case f if f < 0 => defaultFeeratePerKB
    case f => f
  }

}
