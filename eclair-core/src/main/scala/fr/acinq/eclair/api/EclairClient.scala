package fr.acinq.eclair.api

import java.io.File

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.HttpMethods.register
import akka.pattern.ask
import akka.util.Timeout
import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi, Satoshi}
import fr.acinq.eclair.{Kit, NodeParams, Setup, ShortChannelId}
import fr.acinq.eclair.blockchain.bitcoind.rpc.BasicBitcoinJsonRPCClient
import fr.acinq.eclair.channel._
import fr.acinq.eclair.io.Peer.{GetPeerInfo, PeerInfo}
import fr.acinq.eclair.io.{NodeURI, Peer}
import fr.acinq.eclair.payment.PaymentLifecycle._
import fr.acinq.eclair.payment.{PaymentLifecycle, PaymentRequest}
import fr.acinq.eclair.router.{ChannelDesc, RouteRequest, RouteResponse}
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, NodeAnnouncement}
import org.slf4j.LoggerFactory

import scala.concurrent.Future

class EclairClient(datadir: File = new File(s"${System.getenv("HOME")}/.eclair"))
                  (implicit system: ActorSystem, timeout: Timeout) extends EclairApi {


  implicit val dispatcher = system.dispatcher

  private val _ = require(datadir.mkdirs(), s"Failed to make directory for eclair ${datadir.getAbsolutePath}")

  private val setup = new Setup(datadir, actorSystem = system)
  private val kitF: Future[Kit] = setup.bootstrap
  private val logger = LoggerFactory.getLogger(this.getClass.getSimpleName)

  def nodeParams: NodeParams = setup.nodeParams

  override def getBalance: Future[Satoshi] = kitF.flatMap(_.wallet.getBalance)

  override def createInvoice(amt: MilliSatoshi, description: String = ""): Future[PaymentRequest] = {
    val recv = ReceivePayment(Some(MilliSatoshi(amt.toLong)), description)
    receive(recv)
  }


  override def payInvoice(invoice: PaymentRequest): Future[PaymentResult] = {
    kitF.flatMap { kit =>
      val sendPayment = SendPayment(invoice.amount.get.toLong,invoice.paymentHash,invoice.nodeId)
      kit.paymentInitiator.ask(sendPayment).mapTo[PaymentResult]
    }
  }

  override def checkPayment(invoice: PaymentRequest): Future[Boolean] = {
    checkPayment(invoice.paymentHash)
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

  override def openChannel(openChannelMsg: Peer.OpenChannel): Future[String] = {
    logger.debug(s"${setup.nodeParams.nodeId} is opening a channel with ${openChannelMsg.remoteNodeId}")
    kitF.flatMap { kit =>
      val channel = kit.switchboard.ask(openChannelMsg).mapTo[String]
      channel
    }
  }

  override def getPeers: Future[Vector[Peer.PeerInfo]] = {
    kitF.flatMap { kit =>
      val result: Future[Iterable[PeerInfo]] = for {
        peers <- (kit.switchboard ? 'peers).mapTo[Map[PublicKey, ActorRef]]
        peerinfos <- Future.sequence(peers.values.map(peer => (peer ? GetPeerInfo).mapTo[PeerInfo]))
      } yield peerinfos
      result.map(_.toVector)
    }
  }

  override def close(id: String, spk: Option[BinaryData]): Future[String] = {
    sendToChannel(id, CMD_CLOSE(scriptPubKey = spk)).mapTo[String]
  }

  override def forceClose(id: String): Future[String] = {
    sendToChannel(id, CMD_FORCECLOSE).mapTo[String]
  }

  override def channels(pubKeyOpt: Option[PublicKey]): Future[Vector[LocalChannelInfo]] = {
    kitF.flatMap { kit =>

      val channelsIdF: Future[Iterable[BinaryData]] = (kit.register ? 'channels)
        .mapTo[Map[BinaryData, ActorRef]]
        .map { m :Map[BinaryData, ActorRef] =>
          if (pubKeyOpt.isDefined) {
            m.filter(_._1 == pubKeyOpt.get).keys
          } else {
            m.keys
          }
        }

      val sentF: Future[Iterable[RES_GETINFO]] = channelsIdF.flatMap { channels_id: Iterable[BinaryData] =>
        val x: Iterable[Future[RES_GETINFO]] = channels_id.map { channel_id =>
          val x = sendToChannel(channel_id.toString(), CMD_GETINFO).mapTo[RES_GETINFO]
          x
        }
        Future.sequence(x)
      }

      val f: Future[Iterable[LocalChannelInfo]] = sentF.map { sent =>
        sent.map(s => LocalChannelInfo(s.nodeId, s.channelId, s.state.toString))
      }

      f.map(_.toVector)
    }
  }

  override def channel(id: String): Future[RES_GETINFO] = {
    sendToChannel(id, CMD_GETINFO).mapTo[RES_GETINFO]
  }

  override def allChannels(): Future[Vector[ChannelDesc]] = {
    kitF.flatMap { kit =>
      val descs: Future[Iterable[ChannelDesc]] = {
        (kit.router ? 'channels).mapTo[Iterable[ChannelAnnouncement]]
          .map(_.map(c => ChannelDesc(c.shortChannelId, c.nodeId1, c.nodeId2)))
      }
      descs.map(_.toVector)
    }
  }

  override def allNodes(): Future[Vector[NodeAnnouncement]] = {
    kitF.flatMap { kit =>
      val ann = (kit.router ? 'nodes).mapTo[Iterable[NodeAnnouncement]]
      ann.map(_.toVector)
    }
  }

  override def allUpdates(nodeIdOpt: Option[PublicKey]): Future[Vector[ChannelUpdate]] = {
    kitF.flatMap { kit =>
      val updates: Future[Iterable[ChannelUpdate]] = {
        if (nodeIdOpt.isDefined) {
          val updateMap: Future[Map[ChannelDesc, ChannelUpdate]] = {
            (kit.router ? 'updatesMap).mapTo[Map[ChannelDesc, ChannelUpdate]]
          }
          updateMap.map(_.filter(e => e._1.a == nodeIdOpt.get || e._1.b == nodeIdOpt.get).values)
        } else {
          (kit.router ? 'updates).mapTo[Iterable[ChannelUpdate]]
        }
      }
      updates.map(_.toVector)
    }
  }

  override def receive(receivePayment: ReceivePayment): Future[PaymentRequest] = {
    kitF.flatMap { kit =>
      (kit.paymentHandler.ask(receivePayment)).mapTo[PaymentRequest]
    }
  }

  override def checkInvoice(invoice: PaymentRequest): Future[Boolean] = {
    kitF.flatMap { kit =>
      val checkPayment = CheckPayment(invoice.paymentHash)
      kit.paymentHandler.ask(checkPayment).mapTo[Boolean]
    }
  }

  override def findRoute(nodeId: PublicKey): Future[RouteResponse] = {
    kitF.flatMap { kit =>
      (kit.router ? RouteRequest(kit.nodeParams.nodeId, nodeId)).mapTo[RouteResponse]
    }
  }

  override def findRoute(paymentRequest: PaymentRequest): Future[RouteResponse] = {
    findRoute(paymentRequest.nodeId)
  }

  override def send(invoice: SendPayment): Future[PaymentResult]  = {
    kitF.flatMap { kit =>
      kit.paymentInitiator.ask(invoice).mapTo[PaymentResult].map {
        case s: PaymentSucceeded => s
        case f: PaymentFailed => f.copy(failures = PaymentLifecycle.transformForUser(f.failures))
      }
    }
  }

  override def checkPayment(paymentHash: BinaryData): Future[Boolean] = {
    kitF.flatMap { kit =>
      (kit.paymentHandler ? CheckPayment(paymentHash)).mapTo[Boolean]
    }
  }

  /** Pasting this over here for now as a bridge between old implementation in Service
    * and the new implementation in EclairClient
    */
  private def sendToChannel(channelIdentifier: String, request: Any): Future[Any] = {
    val res: Future[Any] = for {
      fwdReq <- Future(Register.ForwardShortId(ShortChannelId(channelIdentifier), request))
        .recoverWith { case _ => Future(Register.Forward(BinaryData(channelIdentifier), request)) }
        .recoverWith { case _ => Future.failed(new RuntimeException(s"invalid channel identifier '$channelIdentifier'")) }
      res <- kitF.flatMap(kit => kit.register ? fwdReq)
    } yield res
    res
  }

  private val rpcClient = {
    new BasicBitcoinJsonRPCClient(
      user = setup.config.getString("bitcoind.rpcuser"),
      password = setup.config.getString("bitcoind.rpcpassword"),
      host = setup.config.getString("bitcoind.host"),
      port = setup.config.getInt("bitcoind.rpcport"))
  }

  def generateBlocks(n: Int): Future[Unit] = {
    rpcClient.invoke("generate", n).map(_ => ())
  }
}
