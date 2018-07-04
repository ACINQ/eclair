package fr.acinq.eclair.api

import java.io.File

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.util.Timeout
import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.{MilliSatoshi, Satoshi}
import fr.acinq.eclair.{Kit, Setup}
import fr.acinq.eclair.blockchain.bitcoind.rpc.BasicBitcoinJsonRPCClient
import fr.acinq.eclair.io.{NodeURI, Peer}
import fr.acinq.eclair.payment.PaymentLifecycle.{CheckPayment, PaymentResult, ReceivePayment, SendPayment}
import fr.acinq.eclair.payment.PaymentRequest
import org.slf4j.LoggerFactory

import scala.concurrent.Future

class EclairClient(datadir: File = new File(s"${System.getenv("HOME")}/.eclair"))
                  (implicit system: ActorSystem, timeout: Timeout) extends EclairApi {


  implicit val dispatcher = system.dispatcher
  private val _ = datadir.mkdirs()
  private val setup = new Setup(datadir, actorSystem = system)
  private val kitF: Future[Kit] = setup.bootstrap
  private val logger = LoggerFactory.getLogger(this.getClass.getSimpleName)

  override def getBalance: Future[Satoshi] = kitF.flatMap(_.wallet.getBalance)

  override def createInvoice(amt: MilliSatoshi, description: String = ""): Future[PaymentRequest] = {
    kitF.flatMap { kit =>
      (kit.paymentHandler.ask(
        ReceivePayment(Some(MilliSatoshi(amt.toLong)),
          description)
      )).mapTo[PaymentRequest]
    }
  }


  override def payInvoice(invoice: PaymentRequest): Future[PaymentResult] = {
    kitF.flatMap { kit =>
      val sendPayment = SendPayment(invoice.amount.get.toLong,invoice.paymentHash,invoice.nodeId)
      kit.paymentInitiator.ask(sendPayment).mapTo[PaymentResult]
    }
  }

  override def checkPayment(invoice: PaymentRequest): Future[Boolean] = {
    kitF.flatMap { kit =>
      val checkPayment = CheckPayment(invoice.paymentHash)
      kit.paymentHandler.ask(checkPayment).mapTo[Boolean]
    }
  }

  override def connectToNode(nodeURI: NodeURI): Future[String] = {
    logger.debug(s"${setup.nodeParams.nodeId} is connecting to ${nodeURI.nodeId}")
    kitF.flatMap { kit =>
      val conn = (kit.switchboard.ask(Peer.Connect(nodeURI))).mapTo[String]
      conn
    }
  }


  override def getNodeURI: NodeURI = {
    val address = setup.nodeParams.publicAddresses.head
    val hostAndPort =  HostAndPort.fromParts(address.getHostString,address.getPort)
    new NodeURI(setup.nodeParams.nodeId,hostAndPort)
  }

  override def openChannel(nodeURI: NodeURI, amount: MilliSatoshi, pushMSat: MilliSatoshi): Future[String] = {
    logger.debug(s"${setup.nodeParams.nodeId} is opening a channel with ${nodeURI.nodeId}")
    kitF.flatMap { kit =>
      val openChannelMsg = Peer.OpenChannel(nodeURI.nodeId,
        Satoshi(amount.toLong), pushMSat,
        fundingTxFeeratePerKw_opt = None, channelFlags = None)
      val channel = kit.switchboard.ask(openChannelMsg).mapTo[String]
      channel
    }
  }
}
