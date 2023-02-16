package fr.acinq.eclair.db

import com.google.common.util.concurrent.ThreadFactoryBuilder
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, Satoshi}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.Databases.{FileBackup, PostgresDatabases, SqliteDatabases}
import fr.acinq.eclair.db.DbEventHandler.ChannelEvent
import fr.acinq.eclair.db.DualDatabases.runAsync
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.wire.protocol.{ChannelAnnouncement, ChannelUpdate, NodeAddress, NodeAnnouncement}
import fr.acinq.eclair.{CltvExpiry, MilliSatoshi, Paginated, RealShortChannelId, ShortChannelId, TimestampMilli}
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

  override val pendingCommands: PendingCommandsDb = DualPendingCommandsDb(primary.pendingCommands, secondary.pendingCommands)

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

  override def addChannel(c: ChannelAnnouncement, txid: ByteVector32, capacity: Satoshi): Unit = {
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

  override def stats(from: TimestampMilli, to: TimestampMilli): Seq[AuditDb.Stats] = {
    runAsync(secondary.stats(from, to))
    primary.stats(from, to)
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

  override def listLocalChannels(): Seq[PersistentChannelData] = {
    runAsync(secondary.listLocalChannels())
    primary.listLocalChannels()
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

  override def addOrUpdatePeer(nodeId: Crypto.PublicKey, address: NodeAddress): Unit = {
    runAsync(secondary.addOrUpdatePeer(nodeId, address))
    primary.addOrUpdatePeer(nodeId, address)
  }

  override def removePeer(nodeId: Crypto.PublicKey): Unit = {
    runAsync(secondary.removePeer(nodeId))
    primary.removePeer(nodeId)
  }

  override def getPeer(nodeId: Crypto.PublicKey): Option[NodeAddress] = {
    runAsync(secondary.getPeer(nodeId))
    primary.getPeer(nodeId)
  }

  override def listPeers(): Map[Crypto.PublicKey, NodeAddress] = {
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
}

case class DualPaymentsDb(primary: PaymentsDb, secondary: PaymentsDb) extends PaymentsDb {

  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("db-payments").build()))

  override def addIncomingPayment(pr: Bolt11Invoice, preimage: ByteVector32, paymentType: String): Unit = {
    runAsync(secondary.addIncomingPayment(pr, preimage, paymentType))
    primary.addIncomingPayment(pr, preimage, paymentType)
  }

  override def addIncomingBlindedPayment(pr: Bolt12Invoice, preimage: ByteVector32, pathIds: Map[PublicKey, ByteVector], paymentType: String): Unit = {
    runAsync(secondary.addIncomingBlindedPayment(pr, preimage, pathIds, paymentType))
    primary.addIncomingBlindedPayment(pr, preimage, pathIds, paymentType)
  }

  override def receiveIncomingPayment(paymentHash: ByteVector32, amount: MilliSatoshi, receivedAt: TimestampMilli): Boolean = {
    runAsync(secondary.receiveIncomingPayment(paymentHash, amount, receivedAt))
    primary.receiveIncomingPayment(paymentHash, amount, receivedAt)
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

  override def listExpiredIncomingPayments(from: TimestampMilli, to: TimestampMilli): Seq[IncomingPayment] = {
    runAsync(secondary.listExpiredIncomingPayments(from, to))
    primary.listExpiredIncomingPayments(from, to)
  }

  override def listReceivedIncomingPayments(from: TimestampMilli, to: TimestampMilli): Seq[IncomingPayment] = {
    runAsync(secondary.listReceivedIncomingPayments(from, to))
    primary.listReceivedIncomingPayments(from, to)
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
