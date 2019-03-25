package fr.acinq.eclair.db

import java.io.File
import java.sql.{Connection, DriverManager}

import fr.acinq.eclair.db.sqlite._

trait Databases {

  val network: NetworkDb

  val audit: AuditDb

  val channels: ChannelsDb

  val peers: PeersDb

  val payments: PaymentsDb

  val pendingRelay: PendingRelayDb

}

object Databases {

  /**
    * Given a parent folder it creates or loads all the databases from a JDBC connection
    * @param chaindir
    * @return
    */
  def createOrLoadSQLiteWithJDBC(dbdir: File): Databases = {
    chaindir.mkdir()
    val sqliteEclair = DriverManager.getConnection(s"jdbc:sqlite:${new File(chaindir, "eclair.sqlite")}")
    val sqliteNetwork = DriverManager.getConnection(s"jdbc:sqlite:${new File(chaindir, "network.sqlite")}")
    val sqliteAudit = DriverManager.getConnection(s"jdbc:sqlite:${new File(chaindir, "audit.sqlite")}")
    SqliteUtils.obtainExclusiveLock(sqliteEclair) // there should only be one process writing to this file

    databaseByConnections(sqliteAudit, sqliteNetwork, sqliteEclair)
  }

  def databaseByConnections(auditJdbc: Connection, networkJdbc: Connection, eclairJdbc: Connection) = new Databases {
    override val network = new SqliteNetworkDb(networkJdbc)
    override val audit = new SqliteAuditDb(auditJdbc)
    override val channels = new SqliteChannelsDb(eclairJdbc)
    override val peers = new SqlitePeersDb(eclairJdbc)
    override val payments = new SqlitePaymentsDb(eclairJdbc)
    override val pendingRelay = new SqlitePendingRelayDb(eclairJdbc)
  }

}
