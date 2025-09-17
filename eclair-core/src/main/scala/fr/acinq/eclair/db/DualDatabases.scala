package fr.acinq.eclair.db

import com.google.common.util.concurrent.ThreadFactoryBuilder
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, Satoshi, TxId}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.AuditDb.PublishedTransaction
import fr.acinq.eclair.db.Databases.{FileBackup, PostgresDatabases, SqliteDatabases}
import fr.acinq.eclair.db.DbEventHandler.ChannelEvent
import fr.acinq.eclair.db.DualDatabases.runAsync
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.relay.OnTheFlyFunding
import fr.acinq.eclair.payment.relay.Relayer.{InboundFees, RelayFees}
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiry, Features, InitFeature, MilliSatoshi, Paginated, RealShortChannelId, ShortChannelId, TimestampMilli, TimestampSecond}
import grizzled.slf4j.Logging
import scodec.bits.ByteVector

import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import scala.collection.immutable.SortedMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * An implementation of [[Databases]] where there are two separate underlying db, one primary and one secondary.
 * All calls to primary are replicated asynchronously to secondary.
 * Calls to secondary are made asynchronously in a dedicated thread pool, so that it doesn't have any performance impact.
 */
case class DualDatabases(primary: Databases, secondary: Databases) extends Databases with FileBackup {

  override val network: NetworkDb = DualNetworkDb(primary.network, secondary.network)
  override val audit: AuditDb = DualAuditDb(primary.audit, secondary.audit)
  override val channels: ChannelsDb = DualChannelsDb(primary.channels, secondary.channels)
  override val peers: PeersDb = DualPeersDb(primary.peers, secondary.peers)
  override val payments: PaymentsDb = DualPaymentsDb(primary.payments, secondary.payments)
  override val offers: OffersDb = DualOffersDb(primary.offers, secondary.offers)
  override val pendingCommands: PendingCommandsDb = DualPendingCommandsDb(primary.pendingCommands, secondary.pendingCommands)
  override val liquidity: LiquidityDb = DualLiquidityDb(primary.liquidity, secondary.liquidity)
  override val inboundFees: InboundFeesDb = DualInboundFeesDb(primary.inboundFees, secondary.inboundFees)

  /** if one of the database supports file backup, we use it */
  override def backup(backupFile: File): Unit = (primary, secondary) match {
    case (f: FileBackup, _) => f.backup(backupFile)
    case (_, f: FileBackup) => f.backup(backupFile)
    case _ => ()
  }
}

object DualDatabases extends Logging {

  /** Run asynchronously and print errors */
  def runAsync[T](f: => T)(implicit ec: ExecutionContext): Future[T] = Future {
    Try(f) match {
      case Success(res) => res
      case Failure(t) =>
        logger.error("postgres error:\n", t)
        throw t
    }
  }

  def getDatabases(dualDatabases: DualDatabases): (SqliteDatabases, PostgresDatabases) =
    (dualDatabases.primary, dualDatabases.secondary) match {
      case (sqliteDb: SqliteDatabases, postgresDb: PostgresDatabases) =>
        (sqliteDb, postgresDb)
      case (postgresDb: PostgresDatabases, sqliteDb: SqliteDatabases) =>
        (sqliteDb, postgresDb)
      case _ => throw new IllegalArgumentException("there must be one sqlite and one postgres in dual db mode")
    }
}

case class DualNetworkDb(primary: NetworkDb, secondary: NetworkDb) extends NetworkDb {

  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("db-network").build()))

  override def addNode(n: NodeAnnouncement): Unit = {
    runAsync(secondary.addNode(n))
    primary.addNode(n)
  }

  override def updateNode(n: NodeAnnouncement): Unit = {
    runAsync(secondary.updateNode(n))
    primary.updateNode(n)
  }

  override def getNode(nodeId: Crypto.PublicKey): Option[NodeAnnouncement] = {
    runAsync(secondary.getNode(nodeId))
    primary.getNode(nodeId)
  }

  override def removeNode(nodeId: Crypto.PublicKey): Unit = {
    runAsync(secondary.removeNode(nodeId))
    primary.removeNode(nodeId)
  }

  override def listNodes(): Seq[NodeAnnouncement] = {
    runAsync(secondary.listNodes())
    primary.listNodes()
  }

  override def addChannel(c: ChannelAnnouncement, txid: TxId, capacity: Satoshi): Unit = {
    runAsync(secondary.addChannel(c, txid, capacity))
    primary.addChannel(c, txid, capacity)
  }

  override def updateChannel(u: ChannelUpdate): Unit = {
    runAsync(secondary.updateChannel(u))
    primary.updateChannel(u)
  }

  override def removeChannels(shortChannelIds: Iterable[ShortChannelId]): Unit = {
    runAsync(secondary.removeChannels(shortChannelIds))
    primary.removeChannels(shortChannelIds)
  }

  override def getChannel(shortChannelId: RealShortChannelId): Option[Router.PublicChannel] = {
    runAsync(secondary.getChannel(shortChannelId))
    primary.getChannel(shortChannelId)
  }

  override def listChannels(): SortedMap[RealShortChannelId, Router.PublicChannel] = {
    runAsync(secondary.listChannels())
    primary.listChannels()
  }

}

case class DualAuditDb(primary: AuditDb, secondary: AuditDb) extends AuditDb {

  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("db-audit").build()))

  override def add(channelLifecycle: DbEventHandler.ChannelEvent): Unit = {
    runAsync(secondary.add(channelLifecycle))
    primary.add(channelLifecycle)
  }

  override def add(paymentSent: PaymentSent): Unit = {
    runAsync(secondary.add(paymentSent))
    primary.add(paymentSent)
  }

  override def add(paymentReceived: PaymentReceived): Unit = {
    runAsync(secondary.add(paymentReceived))
    primary.add(paymentReceived)
  }

  override def add(paymentRelayed: PaymentRelayed): Unit = {
    runAsync(secondary.add(paymentRelayed))
    primary.add(paymentRelayed)
  }

  override def add(txPublished: TransactionPublished): Unit = {
    runAsync(secondary.add(txPublished))
    primary.add(txPublished)
  }

  override def add(txConfirmed: TransactionConfirmed): Unit = {
    runAsync(secondary.add(txConfirmed))
    primary.add(txConfirmed)
  }

  override def add(channelErrorOccurred: ChannelErrorOccurred): Unit = {
    runAsync(secondary.add(channelErrorOccurred))
    primary.add(channelErrorOccurred)
  }

  override def addChannelUpdate(channelUpdateParametersChanged: ChannelUpdateParametersChanged): Unit = {
    runAsync(secondary.addChannelUpdate(channelUpdateParametersChanged))
    primary.addChannelUpdate(channelUpdateParametersChanged)
  }

  override def addPathFindingExperimentMetrics(metrics: PathFindingExperimentMetrics): Unit = {
    runAsync(secondary.addPathFindingExperimentMetrics(metrics))
    primary.addPathFindingExperimentMetrics(metrics)
  }

  override def listPublished(channelId: ByteVector32): Seq[PublishedTransaction] = {
    runAsync(secondary.listPublished(channelId))
    primary.listPublished(channelId)
  }

  override def listSent(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated]): Seq[PaymentSent] = {
    runAsync(secondary.listSent(from, to, paginated_opt))
    primary.listSent(from, to, paginated_opt)
  }

  override def listReceived(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated]): Seq[PaymentReceived] = {
    runAsync(secondary.listReceived(from, to, paginated_opt))
    primary.listReceived(from, to, paginated_opt)
  }

  override def listRelayed(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated]): Seq[PaymentRelayed] = {
    runAsync(secondary.listRelayed(from, to, paginated_opt))
    primary.listRelayed(from, to, paginated_opt)
  }

  override def listNetworkFees(from: TimestampMilli, to: TimestampMilli): Seq[AuditDb.NetworkFee] = {
    runAsync(secondary.listNetworkFees(from, to))
    primary.listNetworkFees(from, to)
  }

  override def stats(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated]): Seq[AuditDb.Stats] = {
    runAsync(secondary.stats(from, to, paginated_opt))
    primary.stats(from, to, paginated_opt)
  }
}

case class DualChannelsDb(primary: ChannelsDb, secondary: ChannelsDb) extends ChannelsDb {

  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("db-channels").build()))

  override def addOrUpdateChannel(data: PersistentChannelData): Unit = {
    runAsync(secondary.addOrUpdateChannel(data))
    primary.addOrUpdateChannel(data)
  }

  override def getChannel(channelId: ByteVector32): Option[PersistentChannelData] = {
    runAsync(secondary.getChannel(channelId))
    primary.getChannel(channelId)
  }

  override def updateChannelMeta(channelId: ByteVector32, event: ChannelEvent.EventType): Unit = {
    runAsync(secondary.updateChannelMeta(channelId, event))
    primary.updateChannelMeta(channelId, event)
  }

  override def removeChannel(channelId: ByteVector32): Unit = {
    runAsync(secondary.removeChannel(channelId))
    primary.removeChannel(channelId)
  }

  override def markHtlcInfosForRemoval(channelId: ByteVector32, beforeCommitIndex: Long): Unit = {
    runAsync(secondary.markHtlcInfosForRemoval(channelId, beforeCommitIndex))
    primary.markHtlcInfosForRemoval(channelId, beforeCommitIndex)
  }

  override def removeHtlcInfos(batchSize: Int): Unit = {
    runAsync(secondary.removeHtlcInfos(batchSize))
    primary.removeHtlcInfos(batchSize)
  }

  override def listLocalChannels(): Seq[PersistentChannelData] = {
    runAsync(secondary.listLocalChannels())
    primary.listLocalChannels()
  }

  override def listClosedChannels(remoteNodeId_opt: Option[PublicKey], paginated_opt: Option[Paginated]): Seq[PersistentChannelData] = {
    runAsync(secondary.listClosedChannels(remoteNodeId_opt, paginated_opt))
    primary.listClosedChannels(remoteNodeId_opt, paginated_opt)
  }

  override def addHtlcInfo(channelId: ByteVector32, commitmentNumber: Long, paymentHash: ByteVector32, cltvExpiry: CltvExpiry): Unit = {
    runAsync(secondary.addHtlcInfo(channelId, commitmentNumber, paymentHash, cltvExpiry))
    primary.addHtlcInfo(channelId, commitmentNumber, paymentHash, cltvExpiry)
  }

  override def listHtlcInfos(channelId: ByteVector32, commitmentNumber: Long): Seq[(ByteVector32, CltvExpiry)] = {
    runAsync(secondary.listHtlcInfos(channelId, commitmentNumber))
    primary.listHtlcInfos(channelId, commitmentNumber)
  }
}

case class DualPeersDb(primary: PeersDb, secondary: PeersDb) extends PeersDb {

  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("db-peers").build()))

  override def addOrUpdatePeer(nodeId: Crypto.PublicKey, address: NodeAddress, features: Features[InitFeature]): Unit = {
    runAsync(secondary.addOrUpdatePeer(nodeId, address, features))
    primary.addOrUpdatePeer(nodeId, address, features)
  }

  override def addOrUpdatePeerFeatures(nodeId: Crypto.PublicKey, features: Features[InitFeature]): Unit = {
    runAsync(secondary.addOrUpdatePeerFeatures(nodeId, features))
    primary.addOrUpdatePeerFeatures(nodeId, features)
  }

  override def removePeer(nodeId: Crypto.PublicKey): Unit = {
    runAsync(secondary.removePeer(nodeId))
    primary.removePeer(nodeId)
  }

  override def getPeer(nodeId: Crypto.PublicKey): Option[NodeInfo] = {
    runAsync(secondary.getPeer(nodeId))
    primary.getPeer(nodeId)
  }

  override def listPeers(): Map[Crypto.PublicKey, NodeInfo] = {
    runAsync(secondary.listPeers())
    primary.listPeers()
  }

  override def addOrUpdateRelayFees(nodeId: Crypto.PublicKey, fees: RelayFees): Unit = {
    runAsync(secondary.addOrUpdateRelayFees(nodeId, fees))
    primary.addOrUpdateRelayFees(nodeId, fees)
  }

  override def getRelayFees(nodeId: Crypto.PublicKey): Option[RelayFees] = {
    runAsync(secondary.getRelayFees(nodeId))
    primary.getRelayFees(nodeId)
  }

  override def updateStorage(nodeId: PublicKey, data: ByteVector): Unit = {
    runAsync(secondary.updateStorage(nodeId, data))
    primary.updateStorage(nodeId, data)
  }

  override def getStorage(nodeId: PublicKey): Option[ByteVector] = {
    runAsync(secondary.getStorage(nodeId))
    primary.getStorage(nodeId)
  }

  override def removePeerStorage(peerRemovedBefore: TimestampSecond): Unit = {
    runAsync(secondary.removePeerStorage(peerRemovedBefore))
    primary.removePeerStorage(peerRemovedBefore)
  }
}

case class DualPaymentsDb(primary: PaymentsDb, secondary: PaymentsDb) extends PaymentsDb {

  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("db-payments").build()))

  override def addIncomingPayment(pr: Bolt11Invoice, preimage: ByteVector32, paymentType: String): Unit = {
    runAsync(secondary.addIncomingPayment(pr, preimage, paymentType))
    primary.addIncomingPayment(pr, preimage, paymentType)
  }

  override def receiveIncomingPayment(paymentHash: ByteVector32, amount: MilliSatoshi, receivedAt: TimestampMilli): Boolean = {
    runAsync(secondary.receiveIncomingPayment(paymentHash, amount, receivedAt))
    primary.receiveIncomingPayment(paymentHash, amount, receivedAt)
  }

  override def receiveIncomingOfferPayment(pr: MinimalBolt12Invoice, preimage: ByteVector32, amount: MilliSatoshi, receivedAt: TimestampMilli, paymentType: String): Unit = {
    runAsync(secondary.receiveIncomingOfferPayment(pr, preimage, amount, receivedAt, paymentType))
    primary.receiveIncomingOfferPayment(pr, preimage, amount, receivedAt, paymentType)
  }

  override def getIncomingPayment(paymentHash: ByteVector32): Option[IncomingPayment] = {
    runAsync(secondary.getIncomingPayment(paymentHash))
    primary.getIncomingPayment(paymentHash)
  }

  override def removeIncomingPayment(paymentHash: ByteVector32): Try[Unit] = {
    runAsync(secondary.removeIncomingPayment(paymentHash))
    primary.removeIncomingPayment(paymentHash)
  }

  override def listIncomingPayments(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated]): Seq[IncomingPayment] = {
    runAsync(secondary.listIncomingPayments(from, to, paginated_opt))
    primary.listIncomingPayments(from, to, paginated_opt)
  }

  override def listPendingIncomingPayments(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated]): Seq[IncomingPayment] = {
    runAsync(secondary.listPendingIncomingPayments(from, to, paginated_opt))
    primary.listPendingIncomingPayments(from, to, paginated_opt)
  }

  override def listExpiredIncomingPayments(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated]): Seq[IncomingPayment] = {
    runAsync(secondary.listExpiredIncomingPayments(from, to, paginated_opt))
    primary.listExpiredIncomingPayments(from, to, paginated_opt)
  }

  override def listReceivedIncomingPayments(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated]): Seq[IncomingPayment] = {
    runAsync(secondary.listReceivedIncomingPayments(from, to, paginated_opt))
    primary.listReceivedIncomingPayments(from, to, paginated_opt)
  }

  override def addOutgoingPayment(outgoingPayment: OutgoingPayment): Unit = {
    runAsync(secondary.addOutgoingPayment(outgoingPayment))
    primary.addOutgoingPayment(outgoingPayment)
  }

  override def updateOutgoingPayment(paymentResult: PaymentSent): Unit = {
    runAsync(secondary.updateOutgoingPayment(paymentResult))
    primary.updateOutgoingPayment(paymentResult)
  }

  override def updateOutgoingPayment(paymentResult: PaymentFailed): Unit = {
    runAsync(secondary.updateOutgoingPayment(paymentResult))
    primary.updateOutgoingPayment(paymentResult)
  }

  override def getOutgoingPayment(id: UUID): Option[OutgoingPayment] = {
    runAsync(secondary.getOutgoingPayment(id))
    primary.getOutgoingPayment(id)
  }

  override def listOutgoingPayments(parentId: UUID): Seq[OutgoingPayment] = {
    runAsync(secondary.listOutgoingPayments(parentId))
    primary.listOutgoingPayments(parentId)
  }

  override def listOutgoingPayments(paymentHash: ByteVector32): Seq[OutgoingPayment] = {
    runAsync(secondary.listOutgoingPayments(paymentHash))
    primary.listOutgoingPayments(paymentHash)
  }

  override def listOutgoingPayments(from: TimestampMilli, to: TimestampMilli): Seq[OutgoingPayment] = {
    runAsync(secondary.listOutgoingPayments(from, to))
    primary.listOutgoingPayments(from, to)
  }

  override def listOutgoingPaymentsToOffer(offerId: ByteVector32): Seq[OutgoingPayment] = {
    runAsync(secondary.listOutgoingPaymentsToOffer(offerId))
    primary.listOutgoingPaymentsToOffer(offerId)
  }
}

case class DualOffersDb(primary: OffersDb, secondary: OffersDb) extends OffersDb {

  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("db-offers").build()))

  override def addOffer(offer: OfferTypes.Offer, pathId_opt: Option[ByteVector32], createdAt: TimestampMilli = TimestampMilli.now()): Option[OfferData] = {
    runAsync(secondary.addOffer(offer, pathId_opt, createdAt))
    primary.addOffer(offer, pathId_opt, createdAt)
  }

  override def disableOffer(offer: OfferTypes.Offer, disabledAt: TimestampMilli = TimestampMilli.now()): Unit = {
    runAsync(secondary.disableOffer(offer, disabledAt))
    primary.disableOffer(offer, disabledAt)
  }

  override def listOffers(onlyActive: Boolean): Seq[OfferData] = {
    runAsync(secondary.listOffers(onlyActive))
    primary.listOffers(onlyActive)
  }
}

case class DualPendingCommandsDb(primary: PendingCommandsDb, secondary: PendingCommandsDb) extends PendingCommandsDb {

  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("db-pending-commands").build()))

  override def addSettlementCommand(channelId: ByteVector32, cmd: HtlcSettlementCommand): Unit = {
    runAsync(secondary.addSettlementCommand(channelId, cmd))
    primary.addSettlementCommand(channelId, cmd)
  }

  override def removeSettlementCommand(channelId: ByteVector32, htlcId: Long): Unit = {
    runAsync(secondary.removeSettlementCommand(channelId, htlcId))
    primary.removeSettlementCommand(channelId, htlcId)
  }

  override def listSettlementCommands(channelId: ByteVector32): Seq[HtlcSettlementCommand] = {
    runAsync(secondary.listSettlementCommands(channelId))
    primary.listSettlementCommands(channelId)
  }

  override def listSettlementCommands(): Seq[(ByteVector32, HtlcSettlementCommand)] = {
    runAsync(secondary.listSettlementCommands())
    primary.listSettlementCommands()
  }
}

case class DualLiquidityDb(primary: LiquidityDb, secondary: LiquidityDb) extends LiquidityDb {

  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("db-liquidity").build()))

  override def addPurchase(liquidityPurchase: ChannelLiquidityPurchased): Unit = {
    runAsync(secondary.addPurchase(liquidityPurchase))
    primary.addPurchase(liquidityPurchase)
  }

  override def setConfirmed(remoteNodeId: PublicKey, txId: TxId): Unit = {
    runAsync(secondary.setConfirmed(remoteNodeId, txId))
    primary.setConfirmed(remoteNodeId, txId)
  }

  override def listPurchases(remoteNodeId: PublicKey): Seq[LiquidityPurchase] = {
    runAsync(secondary.listPurchases(remoteNodeId))
    primary.listPurchases(remoteNodeId)
  }

  override def addPendingOnTheFlyFunding(remoteNodeId: PublicKey, pending: OnTheFlyFunding.Pending): Unit = {
    runAsync(secondary.addPendingOnTheFlyFunding(remoteNodeId, pending))
    primary.addPendingOnTheFlyFunding(remoteNodeId, pending)
  }

  override def removePendingOnTheFlyFunding(remoteNodeId: PublicKey, paymentHash: ByteVector32): Unit = {
    runAsync(secondary.removePendingOnTheFlyFunding(remoteNodeId, paymentHash))
    primary.removePendingOnTheFlyFunding(remoteNodeId, paymentHash)
  }

  override def listPendingOnTheFlyFunding(remoteNodeId: PublicKey): Map[ByteVector32, OnTheFlyFunding.Pending] = {
    runAsync(secondary.listPendingOnTheFlyFunding(remoteNodeId))
    primary.listPendingOnTheFlyFunding(remoteNodeId)
  }

  override def listPendingOnTheFlyFunding(): Map[PublicKey, Map[ByteVector32, OnTheFlyFunding.Pending]] = {
    runAsync(secondary.listPendingOnTheFlyFunding())
    primary.listPendingOnTheFlyFunding()
  }

  override def listPendingOnTheFlyPayments(): Map[PublicKey, Set[ByteVector32]] = {
    runAsync(secondary.listPendingOnTheFlyPayments())
    primary.listPendingOnTheFlyPayments()
  }

  override def addOnTheFlyFundingPreimage(preimage: ByteVector32): Unit = {
    runAsync(secondary.addOnTheFlyFundingPreimage(preimage))
    primary.addOnTheFlyFundingPreimage(preimage)
  }

  override def getOnTheFlyFundingPreimage(paymentHash: ByteVector32): Option[ByteVector32] = {
    runAsync(secondary.getOnTheFlyFundingPreimage(paymentHash))
    primary.getOnTheFlyFundingPreimage(paymentHash)
  }

  override def addFeeCredit(nodeId: PublicKey, amount: MilliSatoshi, receivedAt: TimestampMilli): MilliSatoshi = {
    runAsync(secondary.addFeeCredit(nodeId, amount, receivedAt))
    primary.addFeeCredit(nodeId, amount, receivedAt)
  }

  override def getFeeCredit(nodeId: PublicKey): MilliSatoshi = {
    runAsync(secondary.getFeeCredit(nodeId))
    primary.getFeeCredit(nodeId)
  }

  override def removeFeeCredit(nodeId: PublicKey, amountUsed: MilliSatoshi): MilliSatoshi = {
    runAsync(secondary.removeFeeCredit(nodeId, amountUsed))
    primary.removeFeeCredit(nodeId, amountUsed)
  }

}


case class DualInboundFeesDb(primary: InboundFeesDb, secondary: InboundFeesDb) extends InboundFeesDb {
  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("db-liquidity").build()))

  override def addOrUpdateInboundFees(nodeId: PublicKey, fees: InboundFees): Unit = {
    runAsync(secondary.addOrUpdateInboundFees(nodeId, fees))
    primary.addOrUpdateInboundFees(nodeId, fees)
  }

  override def getInboundFees(nodeId: PublicKey): Option[InboundFees] = {
    runAsync(secondary.getInboundFees(nodeId))
    primary.getInboundFees(nodeId)
  }
}
