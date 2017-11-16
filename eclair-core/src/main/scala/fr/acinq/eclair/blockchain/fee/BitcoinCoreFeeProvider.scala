package fr.acinq.eclair.blockchain.fee

import fr.acinq.bitcoin.Btc
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinJsonRPCClient
import org.json4s.JsonAST.{JDouble, JInt}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by PM on 09/07/2017.
  */
class BitcoinCoreFeeProvider(rpcClient: BitcoinJsonRPCClient, defaultFeerates: FeeratesPerByte)(implicit ec: ExecutionContext) extends FeeProvider {

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

  override def getFeerates: Future[FeeratesPerByte] = for {
    block_1 <- estimateSmartFee(1)
    blocks_2 <- estimateSmartFee(2)
    blocks_6 <- estimateSmartFee(6)
    blocks_12 <- estimateSmartFee(12)
    blocks_36 <- estimateSmartFee(36)
    blocks_72 <- estimateSmartFee(72)
  } yield FeeratesPerByte(
    block_1 = if (block_1 > 0) block_1 else defaultFeerates.block_1,
    blocks_2 = if (blocks_2 > 0) blocks_2 else defaultFeerates.blocks_2,
    blocks_6 = if (blocks_6 > 0) blocks_6 else defaultFeerates.blocks_6,
    blocks_12 = if (blocks_12 > 0) blocks_12 else defaultFeerates.blocks_12,
    blocks_36 = if (blocks_36 > 0) blocks_36 else defaultFeerates.blocks_36,
    blocks_72 = if (blocks_72 > 0) blocks_72 else defaultFeerates.blocks_72)

}
