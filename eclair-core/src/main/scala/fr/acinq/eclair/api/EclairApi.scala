package fr.acinq.eclair.api

import fr.acinq.bitcoin.{MilliSatoshi, Satoshi}
import fr.acinq.eclair.io.NodeURI
import fr.acinq.eclair.payment.PaymentLifecycle.PaymentResult
import fr.acinq.eclair.payment.PaymentRequest

import scala.concurrent.Future

trait EclairApi {

  def getBalance: Future[Satoshi]

  /** Creates an invoice for the give amount of mSatoshi */
  def createInvoice(amt: MilliSatoshi, description: String = ""): Future[PaymentRequest]

  def getNodeURI: NodeURI

  /** Send money to the given invoice */
  def payInvoice(invoice: PaymentRequest): Future[PaymentResult]


  def checkPayment(invoice: PaymentRequest): Future[Boolean]

  def connectToNode(nodeURI: NodeURI): Future[String]

  def openChannel(nodeURI: NodeURI, amt: MilliSatoshi, pushMSat: MilliSatoshi): Future[String]

}