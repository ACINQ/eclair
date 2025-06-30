package fr.acinq.eclair.integration.basic.fixtures

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.{ClassicActorRefOps, ClassicActorSystemOps}
import akka.actor.{ActorRef, ActorSystem, typed}
import akka.testkit.{TestActor, TestProbe}
import com.softwaremill.quicklens.ModifyPimp
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, OutPoint, Satoshi, SatoshiLong, Transaction, TxId}
import fr.acinq.eclair.ShortChannelId.txIndex
import fr.acinq.eclair.blockchain.SingleKeyOnChainWallet
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{WatchFundingConfirmed, WatchFundingConfirmedTriggered}
import fr.acinq.eclair.blockchain.fee.{FeeratePerKw, FeeratesPerKw}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.crypto.keymanager.{LocalChannelKeyManager, LocalNodeKeyManager}
import fr.acinq.eclair.io.Peer.OpenChannelResponse
import fr.acinq.eclair.io.PeerConnection.ConnectionResult
import fr.acinq.eclair.io.{Peer, PeerConnection, PendingChannelsRateLimiter, Switchboard}
import fr.acinq.eclair.message.Postman
import fr.acinq.eclair.payment.Bolt11Invoice.ExtraHop
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.offer.{DefaultOfferHandler, OfferManager}
import fr.acinq.eclair.payment.receive.{MultiPartHandler, PaymentHandler}
import fr.acinq.eclair.payment.relay.{ChannelRelayer, PostRestartHtlcCleaner, Relayer}
import fr.acinq.eclair.payment.send.PaymentInitiator
import fr.acinq.eclair.reputation.ReputationRecorder
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.wire.protocol.IPAddress
import fr.acinq.eclair.{BlockHeight, MilliSatoshi, MilliSatoshiLong, NodeParams, RealShortChannelId, SubscriptionsComplete, TestBitcoinCoreClient, TestDatabases}
import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.{Assertions, EitherValues}

import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.concurrent.duration.DurationInt
import scala.util.Random

/**
 * A minimal node setup, with real actors.
 *
 * Only the bitcoin watcher and wallet are mocked.
 */
case class MinimalNodeFixture private(nodeParams: NodeParams,
                                      system: ActorSystem,
                                      register: ActorRef,
                                      router: ActorRef,
                                      relayer: ActorRef,
                                      switchboard: ActorRef,
                                      paymentInitiator: ActorRef,
                                      paymentHandler: ActorRef,
                                      offerManager: typed.ActorRef[OfferManager.Command],
                                      defaultOfferHandler: typed.ActorRef[OfferManager.HandlerCommand],
                                      postman: typed.ActorRef[Postman.Command],
                                      watcher: TestProbe,
                                      wallet: SingleKeyOnChainWallet,
                                      bitcoinClient: TestBitcoinCoreClient) {
  val nodeId = nodeParams.nodeId
  val routeParams = nodeParams.routerConf.pathFindingExperimentConf.experiments.values.head.getDefaultRouteParams
}

object MinimalNodeFixture extends Assertions with Eventually with IntegrationPatience with EitherValues {

  def nodeParamsFor(alias: String, seed: ByteVector32): NodeParams = {
    NodeParams.makeNodeParams(
        config = ConfigFactory.load().getConfig("eclair"),
        instanceId = UUID.randomUUID(),
        nodeKeyManager = LocalNodeKeyManager(seed, Block.RegtestGenesisBlock.hash),
        channelKeyManager = LocalChannelKeyManager(seed, Block.RegtestGenesisBlock.hash),
        onChainKeyManager_opt = None,
        torAddress_opt = None,
        database = TestDatabases.inMemoryDb(),
        blockHeight = new AtomicLong(400_000),
        bitcoinCoreFeerates = new AtomicReference(FeeratesPerKw.single(FeeratePerKw(253 sat)))
      ).modify(_.alias).setTo(alias)
      .modify(_.chainHash).setTo(Block.RegtestGenesisBlock.hash)
      .modify(_.routerConf.routerBroadcastInterval).setTo(1 second)
      .modify(_.peerConnectionConf.maxRebroadcastDelay).setTo(1 second)
      .modify(_.channelConf.maxHtlcValueInFlightPercent).setTo(100)
      .modify(_.channelConf.balanceThresholds).setTo(Nil)
  }

  def apply(nodeParams: NodeParams, testName: String): MinimalNodeFixture = {
    implicit val system: ActorSystem = ActorSystem(s"system-${nodeParams.alias}", FixtureUtils.actorSystemConfig(testName))
    val readyListener = TestProbe("ready-listener")
    system.eventStream.subscribe(readyListener.ref, classOf[SubscriptionsComplete])
    val bitcoinClient = new TestBitcoinCoreClient()
    val wallet = new SingleKeyOnChainWallet()
    val watcher = TestProbe("watcher")
    val watcherTyped = watcher.ref.toTyped[ZmqWatcher.Command]
    val register = system.actorOf(Register.props(), "register")
    val router = system.actorOf(Router.props(nodeParams, watcherTyped), "router")
    val offerManager = system.spawn(OfferManager(nodeParams, 1 minute), "offer-manager")
    val defaultOfferHandler = system.spawn(DefaultOfferHandler(nodeParams, router), "default-offer-handler")
    val paymentHandler = system.actorOf(PaymentHandler.props(nodeParams, register, offerManager), "payment-handler")
    val relayer = system.actorOf(Relayer.props(nodeParams, router, register, paymentHandler, None), "relayer")
    val txPublisherFactory = Channel.SimpleTxPublisherFactory(nodeParams, bitcoinClient)
    val channelFactory = Peer.SimpleChannelFactory(nodeParams, watcherTyped, relayer, wallet, txPublisherFactory)
    val pendingChannelsRateLimiter = system.spawnAnonymous(Behaviors.supervise(PendingChannelsRateLimiter(nodeParams, router.toTyped, Seq())).onFailure(typed.SupervisorStrategy.resume))
    val peerFactory = Switchboard.SimplePeerFactory(nodeParams, wallet, channelFactory, pendingChannelsRateLimiter, register, router.toTyped)
    val switchboard = system.actorOf(Switchboard.props(nodeParams, peerFactory), "switchboard")
    val paymentFactory = PaymentInitiator.SimplePaymentFactory(nodeParams, router, register)
    val paymentInitiator = system.actorOf(PaymentInitiator.props(nodeParams, paymentFactory), "payment-initiator")
    val channels = nodeParams.db.channels.listLocalChannels()
    val postman = system.spawn(Behaviors.supervise(Postman(nodeParams, switchboard, router.toTyped, register, offerManager)).onFailure(typed.SupervisorStrategy.restart), name = "postman")
    switchboard ! Switchboard.Init(channels)
    relayer ! PostRestartHtlcCleaner.Init(channels)
    readyListener.expectMsgAllOf(
      SubscriptionsComplete(classOf[Router]),
      SubscriptionsComplete(classOf[Register]),
      SubscriptionsComplete(classOf[Switchboard]),
      SubscriptionsComplete(ChannelRelayer.getClass))
    MinimalNodeFixture(
      nodeParams,
      system,
      register = register,
      router = router,
      relayer = relayer,
      switchboard = switchboard,
      paymentInitiator = paymentInitiator,
      paymentHandler = paymentHandler,
      offerManager = offerManager,
      defaultOfferHandler = defaultOfferHandler,
      postman = postman,
      watcher = watcher,
      wallet = wallet,
      bitcoinClient = bitcoinClient
    )
  }

  /**
   * Connect node1 to node2, using a real [[PeerConnection]] and a fake transport layer.
   *
   * @param mutate12 a method to alter messages from node1 to node2 mid-flight for testing purposes
   * @param mutate21 a method to alter messages from node2 to node1 mid-flight for testing purposes
   */
  def connect(node1: MinimalNodeFixture, node2: MinimalNodeFixture, mutate12: Any => Any = identity, mutate21: Any => Any = identity)(implicit system: ActorSystem): ConnectionResult.Connected = {
    val sender = TestProbe("sender")

    val connection1 = TestProbe("connection-1")(node1.system)
    val transport1 = TestProbe("transport-1")(node1.system)

    val connection2 = TestProbe("connection-2")(node2.system)
    val transport2 = TestProbe("transport-2")(node2.system)

    val peerConnection1 = node1.system.actorOf(PeerConnection.props(node1.nodeParams.keyPair, node1.nodeParams.peerConnectionConf, node1.switchboard, node1.router), s"peer-connection-${Random.nextLong()}")
    val peerConnection2 = node2.system.actorOf(PeerConnection.props(node2.nodeParams.keyPair, node2.nodeParams.peerConnectionConf, node2.switchboard, node2.router), s"peer-connection-${Random.nextLong()}")

    transport1.setAutoPilot { (_: ActorRef, msg: Any) =>
      msg match {
        case _: TransportHandler.Listener => TestActor.KeepRunning
        case _: TransportHandler.ReadAck => TestActor.KeepRunning
        case _ =>
          peerConnection2.tell(mutate12(msg), transport2.ref)
          TestActor.KeepRunning
      }
    }

    transport2.setAutoPilot { (_: ActorRef, msg: Any) =>
      msg match {
        case _: TransportHandler.Listener => TestActor.KeepRunning
        case _: TransportHandler.ReadAck => TestActor.KeepRunning
        case _ =>
          peerConnection1.tell(mutate21(msg), transport1.ref)
          TestActor.KeepRunning
      }
    }

    val pendingAuth1 = PeerConnection.PendingAuth(connection1.ref, Some(node2.nodeParams.nodeId), IPAddress(InetAddress.getLoopbackAddress, 65432), origin_opt = Some(sender.ref), transport_opt = Some(transport1.ref), isPersistent = false)
    peerConnection1 ! pendingAuth1

    val pendingAuth2 = PeerConnection.PendingAuth(connection2.ref, None, IPAddress(InetAddress.getLoopbackAddress, 65432), origin_opt = None, transport_opt = Some(transport2.ref), isPersistent = false)
    peerConnection2 ! pendingAuth2

    peerConnection1 ! TransportHandler.HandshakeCompleted(node2.nodeParams.nodeId)
    peerConnection2 ! TransportHandler.HandshakeCompleted(node1.nodeParams.nodeId)

    sender.expectMsgType[ConnectionResult.Connected]
  }

  def openChannel(node1: MinimalNodeFixture, node2: MinimalNodeFixture, funding: Satoshi, channelType_opt: Option[SupportedChannelType] = None)(implicit system: ActorSystem): OpenChannelResponse.Created = {
    val sender = TestProbe("sender")
    sender.send(node1.switchboard, Peer.OpenChannel(node2.nodeParams.nodeId, funding, channelType_opt, None, None, None, None, None, None))
    sender.expectMsgType[OpenChannelResponse.Created]
  }

  def spliceIn(node1: MinimalNodeFixture, channelId: ByteVector32, amountIn: Satoshi, pushAmount_opt: Option[MilliSatoshi])(implicit system: ActorSystem): CommandResponse[CMD_SPLICE] = {
    val sender = TestProbe("sender")
    val spliceIn = SpliceIn(additionalLocalFunding = amountIn, pushAmount = pushAmount_opt.getOrElse(0.msat))
    val cmd = CMD_SPLICE(sender.ref.toTyped, spliceIn_opt = Some(spliceIn), spliceOut_opt = None, requestFunding_opt = None, channelType_opt = None)
    sender.send(node1.register, Register.Forward(sender.ref.toTyped, channelId, cmd))
    sender.expectMsgType[CommandResponse[CMD_SPLICE]]
  }

  def confirmChannel(node1: MinimalNodeFixture, node2: MinimalNodeFixture, channelId: ByteVector32, blockHeight: BlockHeight, txIndex: Int)(implicit system: ActorSystem): RealShortChannelId = {
    val fundingTx = getChannelData(node1, channelId) match {
      case d: DATA_WAIT_FOR_DUAL_FUNDING_SIGNED => d.signingSession.fundingTx.tx.buildUnsignedTx()
      case d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED => d.latestFundingTx.sharedTx.tx.buildUnsignedTx()
      case d => fail(s"unexpected channel=$d")
    }

    val watch1 = node1.watcher.fishForMessage() { case w: WatchFundingConfirmed if w.txId == fundingTx.txid => true; case _ => false }.asInstanceOf[WatchFundingConfirmed]
    val watch2 = node2.watcher.fishForMessage() { case w: WatchFundingConfirmed if w.txId == fundingTx.txid => true; case _ => false }.asInstanceOf[WatchFundingConfirmed]

    watch1.replyTo ! WatchFundingConfirmedTriggered(blockHeight, txIndex, fundingTx)
    watch2.replyTo ! WatchFundingConfirmedTriggered(blockHeight, txIndex, fundingTx)

    eventually {
      assert(getChannelState(node1, channelId) == NORMAL)
      assert(getChannelState(node2, channelId) == NORMAL)
    }

    val data1After = getChannelData(node1, channelId).asInstanceOf[DATA_NORMAL]
    val data2After = getChannelData(node2, channelId).asInstanceOf[DATA_NORMAL]
    val realScid1 = data1After.commitments.latest.shortChannelId_opt.get
    val realScid2 = data2After.commitments.latest.shortChannelId_opt.get
    assert(realScid1 == realScid2)
    realScid1
  }

  /** Utility method to make sure that the channel has processed all previous messages */
  def waitReady(node: MinimalNodeFixture, channelId: ByteVector32)(implicit system: ActorSystem): Unit = {
    getChannelState(node, channelId)
  }

  def getChannelState(node: MinimalNodeFixture, channelId: ByteVector32)(implicit system: ActorSystem): ChannelState = {
    val sender = TestProbe("sender")
    node.register ! Register.Forward(sender.ref.toTyped, channelId, CMD_GET_CHANNEL_STATE(sender.ref))
    sender.expectMsgType[RES_GET_CHANNEL_STATE].state
  }

  def getChannelData(node: MinimalNodeFixture, channelId: ByteVector32)(implicit system: ActorSystem): ChannelData = {
    val sender = TestProbe("sender")
    node.register ! Register.Forward(sender.ref.toTyped, channelId, CMD_GET_CHANNEL_DATA(sender.ref))
    sender.expectMsgType[RES_GET_CHANNEL_DATA[ChannelData]].data
  }

  def getPeerChannels(node: MinimalNodeFixture, remoteNodeId: PublicKey)(implicit system: ActorSystem): Seq[Peer.ChannelInfo] = {
    val sender = TestProbe("sender")
    node.switchboard ! Switchboard.GetPeerInfo(sender.ref.toTyped, remoteNodeId)
    val peer = sender.expectMsgType[Peer.PeerInfo].peer
    peer ! Peer.GetPeerChannels(sender.ref.toTyped)
    sender.expectMsgType[Peer.PeerChannels].channels
  }

  def getRouterData(node: MinimalNodeFixture)(implicit system: ActorSystem): Router.Data = {
    val sender = TestProbe("sender")
    sender.send(node.router, Router.GetRouterData)
    sender.expectMsgType[Router.Data]
  }

  /** All known funding txs (we don't evaluate immediately because new ones could be created) */
  def knownFundingTxs(nodes: MinimalNodeFixture*): () => Iterable[Transaction] = () => nodes.flatMap(_.wallet.published.values)

  /**
   * An autopilot method for the watcher, that handled funding confirmation requests from the channel and channel
   * validation requests from the router
   */
  def watcherAutopilot(knownFundingTxs: () => Iterable[Transaction], confirm: Boolean = true)(implicit system: ActorSystem): TestActor.AutoPilot = {
    // we forward messages to an actor to emulate a stateful autopilot
    val fundingTxWatcher = system.spawnAnonymous(FundingTxWatcher(knownFundingTxs, confirm))
    (_, msg) =>
      msg match {
        case msg: ZmqWatcher.Command => fundingTxWatcher ! msg
        case _ => ()
      }
      TestActor.KeepRunning
  }

  // When opening a channel, only one of the two nodes publishes the funding transaction, which creates a race when the
  // other node sets a watch before that happens. We simply retry until the funding transaction is published.
  private object FundingTxWatcher {
    def apply(knownFundingTxs: () => Iterable[Transaction], confirm: Boolean): Behavior[ZmqWatcher.Command] = {
      Behaviors.setup { _ =>
        Behaviors.withTimers { timers =>
          Behaviors.receiveMessagePartial {
            case vr: ZmqWatcher.ValidateRequest =>
              val res = knownFundingTxs().find(tx => deterministicTxCoordinates(tx.txid) == (vr.ann.shortChannelId.blockHeight, txIndex(vr.ann.shortChannelId))) match {
                case Some(fundingTx) => Right(fundingTx, ZmqWatcher.UtxoStatus.Unspent)
                case None => Left(new RuntimeException(s"unknown realScid=${vr.ann.shortChannelId}, known=${knownFundingTxs().map(tx => deterministicTxCoordinates(tx.txid)).mkString(",")}"))
              }
              vr.replyTo ! ZmqWatcher.ValidateResult(vr.ann, res)
              Behaviors.same
            case watch: ZmqWatcher.WatchPublished =>
              knownFundingTxs().find(_.txid == watch.txId) match {
                case Some(fundingTx) => watch.replyTo ! ZmqWatcher.WatchPublishedTriggered(fundingTx)
                case None => timers.startSingleTimer(watch, 10 millis)
              }
              Behaviors.same
            case watch: ZmqWatcher.WatchFundingConfirmed if confirm =>
              val (blockHeight, txIndex) = deterministicTxCoordinates(watch.txId)
              knownFundingTxs().find(_.txid == watch.txId) match {
                case Some(fundingTx) => watch.replyTo ! ZmqWatcher.WatchFundingConfirmedTriggered(blockHeight, txIndex, fundingTx)
                case None => timers.startSingleTimer(watch, 10 millis)
              }
              Behaviors.same
            case watch: ZmqWatcher.WatchExternalChannelSpent =>
              knownFundingTxs().find(_.txIn.exists(_.outPoint == OutPoint(watch.txId, watch.outputIndex))) match {
                case Some(nextFundingTx) =>
                  watch.replyTo ! ZmqWatcher.WatchExternalChannelSpentTriggered(watch.shortChannelId, nextFundingTx)
                case None => timers.startSingleTimer(watch, 10 millis)
              }
              Behaviors.same
            case _ =>
              Behaviors.same
          }
        }
      }
    }

    /**
     * We don't use a blockchain in this test setup, but we want to simulate channels being confirmed.
     * We choose a block height and transaction index at which the channel confirms deterministically from its txid.
     */
    private def deterministicTxCoordinates(txId: TxId): (BlockHeight, Int) = {
      val blockHeight = txId.value.take(3).toInt(signed = false)
      val txIndex = txId.value.takeRight(2).toInt(signed = false)
      (BlockHeight(blockHeight), txIndex)
    }
  }

  def sendPayment(node1: MinimalNodeFixture, amount: MilliSatoshi, invoice: Bolt11Invoice)(implicit system: ActorSystem): Either[PaymentFailed, PaymentSent] = {
    val sender = TestProbe("sender")

    val routeParams = node1.nodeParams.routerConf.pathFindingExperimentConf.experiments.values.head.getDefaultRouteParams
    sender.send(node1.paymentInitiator, PaymentInitiator.SendPaymentToNode(sender.ref, amount, invoice, Nil, maxAttempts = 1, routeParams = routeParams, blockUntilComplete = true))
    sender.expectMsgType[PaymentEvent] match {
      case e: PaymentSent => Right(e)
      case e: PaymentFailed => Left(e)
      case e => fail(s"unexpected event $e")
    }
  }

  def sendPayment(node1: MinimalNodeFixture, node2: MinimalNodeFixture, amount: MilliSatoshi, hints: List[List[ExtraHop]] = List.empty)(implicit system: ActorSystem): Either[PaymentFailed, PaymentSent] = {
    val sender = TestProbe("sender")
    sender.send(node2.paymentHandler, MultiPartHandler.ReceiveStandardPayment(sender.ref.toTyped, Some(amount), Left("test payment"), extraHops = hints))
    val invoice = sender.expectMsgType[Bolt11Invoice]

    sendPayment(node1, amount, invoice)
  }

  def sendSuccessfulPayment(node1: MinimalNodeFixture, node2: MinimalNodeFixture, amount: MilliSatoshi, hints: List[List[ExtraHop]] = List.empty)(implicit system: ActorSystem): PaymentSent = {
    sendPayment(node1, node2, amount, hints).value
  }

  def sendFailingPayment(node1: MinimalNodeFixture, node2: MinimalNodeFixture, amount: MilliSatoshi, hints: List[List[ExtraHop]] = List.empty)(implicit system: ActorSystem): PaymentFailed = {
    sendPayment(node1, node2, amount, hints).left.value
  }

  def prettyPrint(routerData: Router.Data, nodes: MinimalNodeFixture*): Unit = {
    val nodeId2Alias = nodes.map(n => n.nodeParams.nodeId -> n.nodeParams.alias).toMap
      .withDefault(nodeId => throw new RuntimeException(s"cannot resolve nodeId=$nodeId, make sure you have provided all node fixtures"))
    routerData.channels.values.foreach { channel =>
      val name = Seq(channel.nodeId1, channel.nodeId2).map(nodeId2Alias).sorted.mkString("-")
      val u1 = channel.update_1_opt.map(_ => s"${nodeId2Alias(channel.nodeId1)}=yes").getOrElse(s"${nodeId2Alias(channel.nodeId1)}=no")
      val u2 = channel.update_2_opt.map(_ => s"${nodeId2Alias(channel.nodeId2)}=yes").getOrElse(s"${nodeId2Alias(channel.nodeId2)}=no")
      println(s"$name : $u1 $u2")
    }
  }

}