package fr.acinq.eclair.payment

import fr.acinq.bitcoin.{BinaryData, MilliSatoshi}
import grizzled.slf4j.Logging

import scala.util.{Failure, Success, Try}

/**
  * Created by DPA on 11/04/2017.
  */
object PaymentRequest extends Logging {

  // https://github.com/lightningnetwork/lightning-rfc/blob/master/02-peer-protocol.md#adding-an-htlc-update_add_htlc
  val maxAmountMsat = 4294967296L

  def write(pr: PaymentRequest): String = {
    s"${pr.nodeId.toString}:${pr.amount.amount}:${pr.paymentHash.toString}"
  }

  /**
    * Parse a string and if the string is correctly formatted, returns a PaymentRequest object.
    * Otherwise, throws and exception
    *
    * @param pr payment request string, should look like <pre>node:amount:hash</pre>
    * @return a PaymentRequest object
    */
  def read(pr: String): PaymentRequest = {
    Try {
      val Array(nodeId, amount, hash) = pr.split(":")
      PaymentRequest(BinaryData(nodeId), MilliSatoshi(amount.toLong), BinaryData(hash))
    } match {
      case Success(s) => s
      case Failure(t) =>
        logger.debug(s"could not parse payment request: ${t.getMessage}")
        throw t
    }
  }
}

case class PaymentRequest(nodeId: BinaryData, amount: MilliSatoshi, paymentHash: BinaryData) {
  require(amount.amount > 0 && amount.amount < PaymentRequest.maxAmountMsat,
    f"amount is not valid: must be > 0 and < ${PaymentRequest.maxAmountMsat}%,d msat (~${PaymentRequest.maxAmountMsat / 1e11}%.3f BTC)")
}
