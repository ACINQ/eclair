package fr.acinq.eclair.db

import com.google.common.util.concurrent.ThreadFactoryBuilder
import fr.acinq.bitcoin.{ByteVector32, Crypto, Satoshi}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.Databases.{FileBackup, PostgresDatabases, SqliteDatabases}
import fr.acinq.eclair.db.DbEventHandler.ChannelEvent
import fr.acinq.eclair.db.DualDatabases.runAsync
import fr.acinq.eclair.db.pg._
import fr.acinq.eclair.db.sqlite._
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.wire.protocol.{ChannelAnnouncement, ChannelUpdate, NodeAddress, NodeAnnouncement}
import fr.acinq.eclair.{CltvExpiry, MilliSatoshi, ShortChannelId, TimestampMilli}
import grizzled.slf4j.Logging

import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import scala.collection.immutable.SortedMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * An implementation of [[Databases]] where there are two separate underlying db, one sqlite and one postgres.
 * Sqlite is the main database, but we also replicate all calls to postgres.
 * Calls to postgres are made asynchronously in a dedicated thread pool, so that it doesn't have any performance impact.
 */
case class DualDatabases(sqlite: SqliteDatabases, postgres: PostgresDatabases) extends Databases with FileBackup {

  override val network: NetworkDb = DualNetworkDb(sqlite.network, postgres.network)

  override val audit: AuditDb = DualAuditDb(sqlite.audit, postgres.audit)

  override val channels: ChannelsDb = DualChannelsDb(sqlite.channels, postgres.channels)

  override val peers: PeersDb = DualPeersDb(sqlite.peers, postgres.peers)

  override val payments: PaymentsDb = DualPaymentsDb(sqlite.payments, postgres.payments)

  override val pendingCommands: PendingCommandsDb = DualPendingCommandsDb(sqlite.pendingCommands, postgres.pendingCommands)

  override def backup(backupFile: File): Unit = sqlite.backup(backupFile)
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
}

case class DualNetworkDb(sqlite: SqliteNetworkDb, postgres: PgNetworkDb) extends NetworkDb {

  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("db-network").build()))

  override def addNode(n: NodeAnnouncement): Unit = {
    runAsync(postgres.addNode(n))
    sqlite.addNode(n)
  }

  override def updateNode(n: NodeAnnouncement): Unit = {
    runAsync(postgres.updateNode(n))
    sqlite.updateNode(n)
  }

  override def getNode(nodeId: Crypto.PublicKey): Option[NodeAnnouncement] = {
    runAsync(postgres.getNode(nodeId))
    sqlite.getNode(nodeId)
  }

  override def removeNode(nodeId: Crypto.PublicKey): Unit = {
    runAsync(postgres.removeNode(nodeId))
    sqlite.removeNode(nodeId)
  }

  override def listNodes(): Seq[NodeAnnouncement] = {
    runAsync(postgres.listNodes())
    sqlite.listNodes()
  }

  override def addChannel(c: ChannelAnnouncement, txid: ByteVector32, capacity: Satoshi): Unit = {
    runAsync(postgres.addChannel(c, txid, capacity))
    sqlite.addChannel(c, txid, capacity)
  }

  override def updateChannel(u: ChannelUpdate): Unit = {
    runAsync(postgres.updateChannel(u))
    sqlite.updateChannel(u)
  }

  override def removeChannels(shortChannelIds: Iterable[ShortChannelId]): Unit = {
    runAsync(postgres.removeChannels(shortChannelIds))
    sqlite.removeChannels(shortChannelIds)
  }

  override def listChannels(): SortedMap[ShortChannelId, Router.PublicChannel] = {
    runAsync(postgres.listChannels())
    sqlite.listChannels()
  }

  override def addToPruned(shortChannelIds: Iterable[ShortChannelId]): Unit = {
    runAsync(postgres.addToPruned(shortChannelIds))
    sqlite.addToPruned(shortChannelIds)
  }

  override def removeFromPruned(shortChannelId: ShortChannelId): Unit = {
    runAsync(postgres.removeFromPruned(shortChannelId))
    sqlite.removeFromPruned(shortChannelId)
  }

  override def isPruned(shortChannelId: ShortChannelId): Boolean = {
    runAsync(postgres.isPruned(shortChannelId))
    sqlite.isPruned(shortChannelId)
  }

  override def close(): Unit = {
    runAsync(postgres.close())
    sqlite.close()
  }
}

case class DualAuditDb(sqlite: SqliteAuditDb, postgres: PgAuditDb) extends AuditDb {

  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("db-audit").build()))

  override def add(channelLifecycle: DbEventHandler.ChannelEvent): Unit = {
    runAsync(postgres.add(channelLifecycle))
    sqlite.add(channelLifecycle)
  }

  override def add(paymentSent: PaymentSent): Unit = {
    runAsync(postgres.add(paymentSent))
    sqlite.add(paymentSent)
  }

  override def add(paymentReceived: PaymentReceived): Unit = {
    runAsync(postgres.add(paymentReceived))
    sqlite.add(paymentReceived)
  }

  override def add(paymentRelayed: PaymentRelayed): Unit = {
    runAsync(postgres.add(paymentRelayed))
    sqlite.add(paymentRelayed)
  }

  override def add(txPublished: TransactionPublished): Unit = {
    runAsync(postgres.add(txPublished))
    sqlite.add(txPublished)
  }

  override def add(txConfirmed: TransactionConfirmed): Unit = {
    runAsync(postgres.add(txConfirmed))
    sqlite.add(txConfirmed)
  }

  override def add(channelErrorOccurred: ChannelErrorOccurred): Unit = {
    runAsync(postgres.add(channelErrorOccurred))
    sqlite.add(channelErrorOccurred)
  }

  override def addChannelUpdate(channelUpdateParametersChanged: ChannelUpdateParametersChanged): Unit = {
    runAsync(postgres.addChannelUpdate(channelUpdateParametersChanged))
    sqlite.addChannelUpdate(channelUpdateParametersChanged)
  }

  override def addPathFindingExperimentMetrics(metrics: PathFindingExperimentMetrics): Unit = {
    runAsync(postgres.addPathFindingExperimentMetrics(metrics))
    sqlite.addPathFindingExperimentMetrics(metrics)
  }

  override def listSent(from: TimestampMilli, to: TimestampMilli): Seq[PaymentSent] = {
    runAsync(postgres.listSent(from, to))
    sqlite.listSent(from, to)
  }

  override def listReceived(from: TimestampMilli, to: TimestampMilli): Seq[PaymentReceived] = {
    runAsync(postgres.listReceived(from, to))
    sqlite.listReceived(from, to)
  }

  override def listRelayed(from: TimestampMilli, to: TimestampMilli): Seq[PaymentRelayed] = {
    runAsync(postgres.listRelayed(from, to))
    sqlite.listRelayed(from, to)
  }

  override def listNetworkFees(from: TimestampMilli, to: TimestampMilli): Seq[AuditDb.NetworkFee] = {
    runAsync(postgres.listNetworkFees(from, to))
    sqlite.listNetworkFees(from, to)
  }

  override def stats(from: TimestampMilli, to: TimestampMilli): Seq[AuditDb.Stats] = {
    runAsync(postgres.stats(from, to))
    sqlite.stats(from, to)
  }

  override def close(): Unit = {
    runAsync(postgres.close())
    sqlite.close()
  }
}

case class DualChannelsDb(sqlite: SqliteChannelsDb, postgres: PgChannelsDb) extends ChannelsDb {

  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("db-channels").build()))

  override def addOrUpdateChannel(state: HasCommitments): Unit = {
    runAsync(postgres.addOrUpdateChannel(state))
    sqlite.addOrUpdateChannel(state)
  }

  override def updateChannelMeta(channelId: ByteVector32, event: ChannelEvent.EventType): Unit = {
    runAsync(postgres.updateChannelMeta(channelId, event))
    sqlite.updateChannelMeta(channelId, event)
  }

  override def removeChannel(channelId: ByteVector32): Unit = {
    runAsync(postgres.removeChannel(channelId))
    sqlite.removeChannel(channelId)
  }

  override def listLocalChannels(): Seq[HasCommitments] = {
    runAsync(postgres.listLocalChannels())
    sqlite.listLocalChannels()
  }

  override def addHtlcInfo(channelId: ByteVector32, commitmentNumber: Long, paymentHash: ByteVector32, cltvExpiry: CltvExpiry): Unit = {
    runAsync(postgres.addHtlcInfo(channelId, commitmentNumber, paymentHash, cltvExpiry))
    sqlite.addHtlcInfo(channelId, commitmentNumber, paymentHash, cltvExpiry)
  }

  override def listHtlcInfos(channelId: ByteVector32, commitmentNumber: Long): Seq[(ByteVector32, CltvExpiry)] = {
    runAsync(postgres.listHtlcInfos(channelId, commitmentNumber))
    sqlite.listHtlcInfos(channelId, commitmentNumber)
  }

  override def close(): Unit = {
    runAsync(postgres.close())
    sqlite.close()
  }
}

case class DualPeersDb(sqlite: SqlitePeersDb, postgres: PgPeersDb) extends PeersDb {

  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("db-peers").build()))

  override def addOrUpdatePeer(nodeId: Crypto.PublicKey, address: NodeAddress): Unit = {
    runAsync(postgres.addOrUpdatePeer(nodeId, address))
    sqlite.addOrUpdatePeer(nodeId, address)
  }

  override def removePeer(nodeId: Crypto.PublicKey): Unit = {
    runAsync(postgres.removePeer(nodeId))
    sqlite.removePeer(nodeId)
  }

  override def getPeer(nodeId: Crypto.PublicKey): Option[NodeAddress] = {
    runAsync(postgres.getPeer(nodeId))
    sqlite.getPeer(nodeId)
  }

  override def listPeers(): Map[Crypto.PublicKey, NodeAddress] = {
    runAsync(postgres.listPeers())
    sqlite.listPeers()
  }

  override def addOrUpdateRelayFees(nodeId: Crypto.PublicKey, fees: RelayFees): Unit = {
    runAsync(postgres.addOrUpdateRelayFees(nodeId, fees))
    sqlite.addOrUpdateRelayFees(nodeId, fees)
  }

  override def getRelayFees(nodeId: Crypto.PublicKey): Option[RelayFees] = {
    runAsync(postgres.getRelayFees(nodeId))
    sqlite.getRelayFees(nodeId)
  }

  override def close(): Unit = {
    runAsync(postgres.close())
    sqlite.close()
  }
}

case class DualPaymentsDb(sqlite: SqlitePaymentsDb, postgres: PgPaymentsDb) extends PaymentsDb {

  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("db-payments").build()))

  override def listPaymentsOverview(limit: Int): Seq[PlainPayment] = {
    runAsync(postgres.listPaymentsOverview(limit))
    sqlite.listPaymentsOverview(limit)
  }

  override def close(): Unit = {
    runAsync(postgres.close())
    sqlite.close()
  }

  override def addIncomingPayment(pr: PaymentRequest, preimage: ByteVector32, paymentType: String): Unit = {
    runAsync(postgres.addIncomingPayment(pr, preimage, paymentType))
    sqlite.addIncomingPayment(pr, preimage, paymentType)
  }

  override def receiveIncomingPayment(paymentHash: ByteVector32, amount: MilliSatoshi, receivedAt: TimestampMilli): Unit = {
    runAsync(postgres.receiveIncomingPayment(paymentHash, amount, receivedAt))
    sqlite.receiveIncomingPayment(paymentHash, amount, receivedAt)
  }

  override def getIncomingPayment(paymentHash: ByteVector32): Option[IncomingPayment] = {
    runAsync(postgres.getIncomingPayment(paymentHash))
    sqlite.getIncomingPayment(paymentHash)
  }

  override def listIncomingPayments(from: TimestampMilli, to: TimestampMilli): Seq[IncomingPayment] = {
    runAsync(postgres.listIncomingPayments(from, to))
    sqlite.listIncomingPayments(from, to)
  }

  override def listPendingIncomingPayments(from: TimestampMilli, to: TimestampMilli): Seq[IncomingPayment] = {
    runAsync(postgres.listPendingIncomingPayments(from, to))
    sqlite.listPendingIncomingPayments(from, to)
  }

  override def listExpiredIncomingPayments(from: TimestampMilli, to: TimestampMilli): Seq[IncomingPayment] = {
    runAsync(postgres.listExpiredIncomingPayments(from, to))
    sqlite.listExpiredIncomingPayments(from, to)
  }

  override def listReceivedIncomingPayments(from: TimestampMilli, to: TimestampMilli): Seq[IncomingPayment] = {
    runAsync(postgres.listReceivedIncomingPayments(from, to))
    sqlite.listReceivedIncomingPayments(from, to)
  }

  override def addOutgoingPayment(outgoingPayment: OutgoingPayment): Unit = {
    runAsync(postgres.addOutgoingPayment(outgoingPayment))
    sqlite.addOutgoingPayment(outgoingPayment)
  }

  override def updateOutgoingPayment(paymentResult: PaymentSent): Unit = {
    runAsync(postgres.updateOutgoingPayment(paymentResult))
    sqlite.updateOutgoingPayment(paymentResult)
  }

  override def updateOutgoingPayment(paymentResult: PaymentFailed): Unit = {
    runAsync(postgres.updateOutgoingPayment(paymentResult))
    sqlite.updateOutgoingPayment(paymentResult)
  }

  override def getOutgoingPayment(id: UUID): Option[OutgoingPayment] = {
    runAsync(postgres.getOutgoingPayment(id))
    sqlite.getOutgoingPayment(id)
  }

  override def listOutgoingPayments(parentId: UUID): Seq[OutgoingPayment] = {
    runAsync(postgres.listOutgoingPayments(parentId))
    sqlite.listOutgoingPayments(parentId)
  }

  override def listOutgoingPayments(paymentHash: ByteVector32): Seq[OutgoingPayment] = {
    runAsync(postgres.listOutgoingPayments(paymentHash))
    sqlite.listOutgoingPayments(paymentHash)
  }

  override def listOutgoingPayments(from: TimestampMilli, to: TimestampMilli): Seq[OutgoingPayment] = {
    runAsync(postgres.listOutgoingPayments(from, to))
    sqlite.listOutgoingPayments(from, to)
  }
}

case class DualPendingCommandsDb(sqlite: SqlitePendingCommandsDb, postgres: PgPendingCommandsDb) extends PendingCommandsDb {

  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("db-pending-commands").build()))

  override def addSettlementCommand(channelId: ByteVector32, cmd: HtlcSettlementCommand): Unit = {
    runAsync(postgres.addSettlementCommand(channelId, cmd))
    sqlite.addSettlementCommand(channelId, cmd)
  }

  override def removeSettlementCommand(channelId: ByteVector32, htlcId: Long): Unit = {
    runAsync(postgres.removeSettlementCommand(channelId, htlcId))
    sqlite.removeSettlementCommand(channelId, htlcId)
  }

  override def listSettlementCommands(channelId: ByteVector32): Seq[HtlcSettlementCommand] = {
    runAsync(postgres.listSettlementCommands(channelId))
    sqlite.listSettlementCommands(channelId)
  }

  override def listSettlementCommands(): Seq[(ByteVector32, HtlcSettlementCommand)] = {
    runAsync(postgres.listSettlementCommands())
    sqlite.listSettlementCommands()
  }

  override def close(): Unit = {
    runAsync(postgres.close())
    sqlite.close()
  }
}
