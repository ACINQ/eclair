package fr.acinq.eclair.db.util

import java.io.File
import java.util.Date

import com.typesafe.config.ConfigFactory
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.channel.NetworkFeePaid
import fr.acinq.eclair.db.Databases
import fr.acinq.eclair.db.psql.{PsqlAuditDb, PsqlPaymentsDb}
import fr.acinq.eclair.db.sqlite.{SqliteChannelsDb, SqlitePaymentsDb}

object SqliteToPgsql extends App {

  val datadir = new File(System.getProperty("eclair.datadir", System.getProperty("user.home") + "/.eclair"))
  val appConfig = NodeParams.loadConfiguration(datadir, ConfigFactory.empty())
  val config = appConfig.getConfig("eclair")
  val chain = config.getString("chain")
  val chaindir = new File(datadir, chain)
  val dbConfig = config.getConfig("db")

  val sqlite = Databases.sqliteJDBC(chaindir)
  val psql = Databases.setupPsqlDatabases(dbConfig)

  sqlite.audit.listNetworkFees(0, new Date().getTime).foreach { fee =>
    psql.audit.asInstanceOf[PsqlAuditDb].add(NetworkFeePaid(
      channel = null, remoteNodeId = fee.remoteNodeId, channelId = fee.channelId, tx = null, fee = fee.fee, txType = fee.txType
    ), txId_opt = Some(fee.txId))
  }

  sqlite.audit.listReceived(0, new Date().getTime).foreach { received =>
    psql.audit.add(received)
  }

  sqlite.audit.listRelayed(0, new Date().getTime).foreach { relayed =>
    psql.audit.add(relayed)
  }

  sqlite.audit.listSent(0, new Date().getTime).foreach { sent =>
    psql.audit.add(sent)
  }

  sqlite.channels.listLocalChannels().foreach { hasCommitments =>
    psql.channels.addOrUpdateChannel(hasCommitments)
    sqlite.channels.asInstanceOf[SqliteChannelsDb].listHtlcInfos(hasCommitments.channelId).foreach { case (paymentHash, cltvExpiry, commitmentNumber) =>
      psql.channels.addOrUpdateHtlcInfo(hasCommitments.channelId, commitmentNumber, paymentHash, cltvExpiry)
    }
  }

  sqlite.network.listChannels().foreach { case (_, channel) =>
    psql.network.addChannel(channel.ann, channel.fundingTxid, channel.capacity)
  }

  sqlite.network.listNodes().foreach { announcement =>
    psql.network.addNode(announcement)
  }

  sqlite.payments.asInstanceOf[SqlitePaymentsDb].listIncomingPayments().foreach { incomingPayment =>
    psql.payments.asInstanceOf[PsqlPaymentsDb].addIncomingPayment(incomingPayment)
  }

  sqlite.payments.asInstanceOf[SqlitePaymentsDb].listOutgoingPayments().foreach { outgoingPayment =>
    psql.payments.asInstanceOf[PsqlPaymentsDb].addOutgoingPayment(outgoingPayment)
  }

  sqlite.peers.listPeers().foreach { case (nodeId, nodeAddress) =>
    psql.peers.addOrUpdatePeer(nodeId, nodeAddress)
  }

  sqlite.pendingRelay.listPendingRelay().map(_._1).foreach { channelId =>
    sqlite.pendingRelay.listPendingRelay(channelId).foreach { cmd =>
      psql.pendingRelay.addPendingRelay(channelId, cmd)
    }
  }

}
