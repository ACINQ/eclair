package fr.acinq.eclair.integration.basic.fixtures

import akka.actor.typed.scaladsl.adapter.ClassicActorRefOps
import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestActor, TestProbe}
import com.softwaremill.quicklens.ModifyPimp
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, Satoshi, Transaction}
import fr.acinq.eclair.ShortChannelId.txIndex
import fr.acinq.eclair.blockchain.DummyOnChainWallet
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{WatchFundingConfirmed, WatchFundingConfirmedTriggered, WatchFundingDeeplyBuried, WatchFundingDeeplyBuriedTriggered}
import fr.acinq.eclair.channel.ChannelOpenResponse.ChannelOpened
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.crypto.keymanager.{LocalChannelKeyManager, LocalNodeKeyManager}
import fr.acinq.eclair.io.PeerConnection.ConnectionResult
import fr.acinq.eclair.io.{Peer, PeerConnection, Switchboard}
import fr.acinq.eclair.payment.Bolt11Invoice.ExtraHop
import fr.acinq.eclair.payment.receive.{MultiPartHandler, PaymentHandler}
import fr.acinq.eclair.payment.relay.{ChannelRelayer, Relayer}
import fr.acinq.eclair.payment.send.PaymentInitiator
import fr.acinq.eclair.payment.{Bolt11Invoice, PaymentEvent, PaymentFailed, PaymentSent}
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.wire.protocol.IPAddress
import fr.acinq.eclair.{BlockHeight, MilliSatoshi, MilliSatoshiLong, NodeParams, RealShortChannelId, SubscriptionsComplete, TestBitcoinCoreClient, TestDatabases, TestFeeEstimator}
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.{Assertions, EitherValues}

import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.DurationInt
import scala.util.{Random, Try}


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
                                      watcher: TestProbe,
                                      wallet: DummyOnChainWallet)

object MinimalNodeFixture extends Assertions with EitherValues {

  def nodeParamsFor(alias: String, seed: ByteVector32): NodeParams = {
    NodeParams.makeNodeParams(
      config = ConfigFactory.load().getConfig("eclair"),
      instanceId = UUID.randomUUID(),
      nodeKeyManager = new LocalNodeKeyManager(seed, Block.RegtestGenesisBlock.hash),
      channelKeyManager = new LocalChannelKeyManager(seed, Block.RegtestGenesisBlock.hash),
      torAddress_opt = None,
      database = TestDatabases.inMemoryDb(),
      blockHeight = new AtomicLong(400_000),
      feeEstimator = new TestFeeEstimator
    ).modify(_.alias).setTo(alias)
      .modify(_.chainHash).setTo(Block.RegtestGenesisBlock.hash)
      .modify(_.routerConf.routerBroadcastInterval).setTo(1 second)
      .modify(_.peerConnectionConf.maxRebroadcastDelay).setTo(1 second)
  }

  def apply(nodeParams: NodeParams): MinimalNodeFixture = {
    implicit val system: ActorSystem = ActorSystem(s"system-${nodeParams.alias}")
    val readyListener = TestProbe("ready-listener")
    system.eventStream.subscribe(readyListener.ref, classOf[SubscriptionsComplete])
    val bitcoinClient = new TestBitcoinCoreClient()
    val wallet = new DummyOnChainWallet()
    val watcher = TestProbe("watcher")
    val watcherTyped = watcher.ref.toTyped[ZmqWatcher.Command]
    val register = system.actorOf(Register.props(), "register")
    val router = system.actorOf(Router.props(nodeParams, watcherTyped), "router")
    val paymentHandler = system.actorOf(PaymentHandler.props(nodeParams, register), "payment-handler")
    val relayer = system.actorOf(Relayer.props(nodeParams, router, register, paymentHandler), "relayer")
    val txPublisherFactory = Channel.SimpleTxPublisherFactory(nodeParams, watcherTyped, bitcoinClient)
    val channelFactory = Peer.SimpleChannelFactory(nodeParams, watcherTyped, relayer, wallet, txPublisherFactory)
    val peerFactory = Switchboard.SimplePeerFactory(nodeParams, wallet, channelFactory)
    val switchboard = system.actorOf(Switchboard.props(nodeParams, peerFactory), "switchboard")
    val paymentFactory = PaymentInitiator.SimplePaymentFactory(nodeParams, router, register)
    val paymentInitiator = system.actorOf(PaymentInitiator.props(nodeParams, paymentFactory), "payment-initiator")
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
      watcher = watcher,
      wallet = wallet
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

  def openChannel(node1: MinimalNodeFixture, node2: MinimalNodeFixture, funding: Satoshi, channelType_opt: Option[SupportedChannelType] = None)(implicit system: ActorSystem): ChannelOpened = {
    val sender = TestProbe("sender")
    sender.send(node1.switchboard, Peer.OpenChannel(node2.nodeParams.nodeId, funding, 0L msat, channelType_opt, None, None, None))
    sender.expectMsgType[ChannelOpened]
  }

  def fundingTx(node: MinimalNodeFixture, channelId: ByteVector32)(implicit system: ActorSystem): Transaction = {
    val fundingTxid = getChannelData(node, channelId).asInstanceOf[PersistentChannelData].commitments.commitInput.outPoint.txid
    node.wallet.funded(fundingTxid)
  }

  def confirmChannel(node1: MinimalNodeFixture, node2: MinimalNodeFixture, channelId: ByteVector32, blockHeight: BlockHeight, txIndex: Int)(implicit system: ActorSystem): Option[RealScidStatus.Temporary] = {
    assert(getChannelState(node1, channelId) == WAIT_FOR_FUNDING_CONFIRMED)
    val data1Before = getChannelData(node1, channelId).asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED]
    val fundingTx = data1Before.fundingTx.get

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
    val realScid1 = data1After.shortIds.real.asInstanceOf[RealScidStatus.Temporary]
    val realScid2 = data2After.shortIds.real.asInstanceOf[RealScidStatus.Temporary]
    assert(realScid1 == realScid2)
    Some(realScid1)
  }

  def confirmChannelDeep(node1: MinimalNodeFixture, node2: MinimalNodeFixture, channelId: ByteVector32, blockHeight: BlockHeight, txIndex: Int)(implicit system: ActorSystem): RealScidStatus.Final = {
    assert(getChannelState(node1, channelId) == NORMAL)
    val data1Before = getChannelData(node1, channelId).asInstanceOf[DATA_NORMAL]
    val fundingTxid = data1Before.commitments.commitInput.outPoint.txid
    val fundingTx = node1.wallet.funded(fundingTxid)

    val watch1 = node1.watcher.fishForMessage() { case w: WatchFundingDeeplyBuried if w.txId == fundingTx.txid => true; case _ => false }.asInstanceOf[WatchFundingDeeplyBuried]
    val watch2 = node2.watcher.fishForMessage() { case w: WatchFundingDeeplyBuried if w.txId == fundingTx.txid => true; case _ => false }.asInstanceOf[WatchFundingDeeplyBuried]

    watch1.replyTo ! WatchFundingDeeplyBuriedTriggered(blockHeight, txIndex, fundingTx)
    watch2.replyTo ! WatchFundingDeeplyBuriedTriggered(blockHeight, txIndex, fundingTx)

    waitReady(node1, channelId)
    waitReady(node2, channelId)

    val data1After = getChannelData(node1, channelId).asInstanceOf[DATA_NORMAL]
    val data2After = getChannelData(node2, channelId).asInstanceOf[DATA_NORMAL]
    val realScid1 = data1After.shortIds.real.asInstanceOf[RealScidStatus.Final]
    val realScid2 = data2After.shortIds.real.asInstanceOf[RealScidStatus.Final]
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

  def getRouterData(node: MinimalNodeFixture)(implicit system: ActorSystem): Router.Data = {
    val sender = TestProbe("sender")
    sender.send(node.router, Router.GetRouterData)
    sender.expectMsgType[Router.Data]
  }

  /**
   * Computes a deterministic [[RealShortChannelId]] based on a txid. We need this so that watchers can verify
   * transactions in a independent and stateless fashion, since there is no actual blockchain in those tests.
   */
  def deterministicShortId(txId: ByteVector32): RealShortChannelId = {
    val blockHeight = txId.take(3).toInt(signed = false)
    val txIndex = txId.takeRight(2).toInt(signed = false)
    val outputIndex = 0 // funding txs created by the dummy wallet used in tests only have one output
    RealShortChannelId(BlockHeight(blockHeight), txIndex, outputIndex)
  }

  /** All known funding txs (we don't evaluate immediately because new ones could be created) */
  def knownFundingTxs(nodes: MinimalNodeFixture*): () => Iterable[Transaction] = () => nodes.map(_.wallet.funded.values).reduce(_ ++ _)

  /**
   * An autopilot method for the watcher, that handled funding confirmation requests from the channel and channel
   * validation requests from the router
   */
  def watcherAutopilot(knownFundingTxs: () => Iterable[Transaction], deepConfirm: Boolean = true): TestActor.AutoPilot = (_, msg) => msg match {
    case watch: ZmqWatcher.WatchFundingConfirmed =>
      val realScid = deterministicShortId(watch.txId)
      val fundingTx = knownFundingTxs().find(_.txid == watch.txId)
        .getOrElse(throw new RuntimeException(s"unknown fundingTxId=${watch.txId}, known=${knownFundingTxs().map(_.txid).mkString(",")}"))
      watch.replyTo ! ZmqWatcher.WatchFundingConfirmedTriggered(realScid.blockHeight, txIndex(realScid), fundingTx)
      TestActor.KeepRunning
    case watch: ZmqWatcher.WatchFundingDeeplyBuried if deepConfirm =>
      val realScid = deterministicShortId(watch.txId)
      val fundingTx = knownFundingTxs().find(_.txid == watch.txId).get
      watch.replyTo ! ZmqWatcher.WatchFundingDeeplyBuriedTriggered(realScid.blockHeight, txIndex(realScid), fundingTx)
      TestActor.KeepRunning
    case vr: ZmqWatcher.ValidateRequest =>
      val res = Try {
        val fundingTx = knownFundingTxs().find(tx => deterministicShortId(tx.txid) == vr.ann.shortChannelId)
          .getOrElse(throw new RuntimeException(s"unknown realScid=${vr.ann.shortChannelId}, known=${knownFundingTxs().map(tx => deterministicShortId(tx.txid)).mkString(",")}"))
        (fundingTx, ZmqWatcher.UtxoStatus.Unspent)
      }.toEither
      vr.replyTo ! ZmqWatcher.ValidateResult(vr.ann, res)
      TestActor.KeepRunning
    case _ => TestActor.KeepRunning
  }

  def sendPayment(node1: MinimalNodeFixture, node2: MinimalNodeFixture, amount: MilliSatoshi, hints: Seq[Seq[ExtraHop]] = Seq.empty)(implicit system: ActorSystem): Either[PaymentFailed, PaymentSent] = {
    val sender = TestProbe("sender")
    sender.send(node2.paymentHandler, MultiPartHandler.ReceivePayment(Some(amount), Left("test payment")))
    val invoice = sender.expectMsgType[Bolt11Invoice]

    val routeParams = node1.nodeParams.routerConf.pathFindingExperimentConf.experiments.values.head.getDefaultRouteParams
    sender.send(node1.paymentInitiator, PaymentInitiator.SendPaymentToNode(amount, invoice, maxAttempts = 1, routeParams = routeParams, extraEdges = hints.flatMap(Bolt11Invoice.toExtraEdges(_, node2.nodeParams.nodeId)), blockUntilComplete = true))
    sender.expectMsgType[PaymentEvent] match {
      case e: PaymentSent => Right(e)
      case e: PaymentFailed => Left(e)
      case e => fail(s"unexpected event $e")
    }
  }

  def sendSuccessfulPayment(node1: MinimalNodeFixture, node2: MinimalNodeFixture, amount: MilliSatoshi, hints: Seq[Seq[ExtraHop]] = Seq.empty)(implicit system: ActorSystem): PaymentSent = {
    sendPayment(node1, node2, amount, hints).value
  }

  def sendFailingPayment(node1: MinimalNodeFixture, node2: MinimalNodeFixture, amount: MilliSatoshi, hints: Seq[Seq[ExtraHop]] = Seq.empty)(implicit system: ActorSystem): PaymentFailed = {
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