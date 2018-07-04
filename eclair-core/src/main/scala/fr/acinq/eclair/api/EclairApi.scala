package fr.acinq.eclair.api

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi, Satoshi}
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.channel.RES_GETINFO
import fr.acinq.eclair.io.{NodeURI, Peer}
import fr.acinq.eclair.payment.PaymentLifecycle.{PaymentResult, ReceivePayment, SendPayment}
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.router.{ChannelDesc, RouteResponse}
import fr.acinq.eclair.wire.{ChannelUpdate, NodeAnnouncement}

import scala.concurrent.Future

trait EclairApi {

  def nodeParams: NodeParams

  def getBalance: Future[Satoshi]

  /** Creates an invoice for the give amount of mSatoshi */
  def createInvoice(amt: MilliSatoshi, description: String = ""): Future[PaymentRequest]

  def getNodeURI: NodeURI

  /** Send money to the given invoice */
  def payInvoice(invoice: PaymentRequest): Future[PaymentResult]

  def getPeers: Future[Vector[Peer.PeerInfo]]

  def checkPayment(invoice: PaymentRequest): Future[Boolean]

  def connectToNode(nodeURI: NodeURI): Future[String]

  def openChannel(openChannelMsg: Peer.OpenChannel): Future[String]

  def close(id: String, spk: Option[BinaryData]): Future[String]

  def forceClose(id: String): Future[String]

  def channels(nodeIdOpt: Option[PublicKey]): Future[Vector[LocalChannelInfo]]

  def channel(id: String): Future[RES_GETINFO]

  def allChannels(): Future[Vector[ChannelDesc]]

  def allNodes(): Future[Vector[NodeAnnouncement]]

  def allUpdates(nodeIdOpt: Option[PublicKey]): Future[Vector[ChannelUpdate]]

  def receive(receivePayment: ReceivePayment): Future[PaymentRequest]

  def checkInvoice(invoice: PaymentRequest): Future[Boolean]

  def findRoute(nodeId: PublicKey): Future[RouteResponse]

  def findRoute(paymentRequest: PaymentRequest): Future[RouteResponse]

  def send(invoice: SendPayment): Future[PaymentResult]

  def checkPayment(paymentHash: BinaryData): Future[Boolean]

}