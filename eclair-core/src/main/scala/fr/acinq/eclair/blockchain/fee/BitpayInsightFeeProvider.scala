package fr.acinq.eclair.blockchain.fee

import akka.actor.ActorSystem
import fr.acinq.bitcoin.{Btc, Satoshi}
import fr.acinq.eclair.HttpHelper.get
import org.json4s.JsonAST.JDouble

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by PM on 09/07/2017.
  */
class BitpayInsightFeeProvider(implicit system: ActorSystem, ec: ExecutionContext) extends FeeProvider {


  override def getFeeratePerKB: Future[Long] =
    for {
      json <- get("https://test-insight.bitpay.com/api/utils/estimatefee?nbBlocks=3")
      JDouble(fee_per_kb) = json \ "3"
    } yield (Btc(fee_per_kb): Satoshi).amount
}
