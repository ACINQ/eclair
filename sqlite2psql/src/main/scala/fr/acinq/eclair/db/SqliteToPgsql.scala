package fr.acinq.eclair.db

import java.io.File
import java.util.Date

import com.typesafe.config.ConfigFactory
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.channel.NetworkFeePaid
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
  val psql = Databases.setupPsqlDatabases(dbConfig, datadir, { _ => () })

  println(s"Transferring data from ${chaindir} to ${dbConfig.getString("psql.database")} at ${dbConfig.getString("psql.host")}")

  val (from, to) = (0, new Date().getTime)

  val existingDataRows = psql.audit.listNetworkFees(from, to).size +
    psql.audit.listReceived(from, to).size +
    psql.audit.listRelayed(from, to).size +
    psql.audit.listSent(from, to).size +
    psql.channels.listLocalChannels().size +
    psql.network.listChannels().size +
    psql.network.listNodes().size +
    psql.payments.listIncomingPayments(from, to).size +
    psql.payments.listOutgoingPayments(from, to).size +
    psql.peers.listPeers().size +
    psql.pendingRelay.listPendingRelay().size

  if (existingDataRows > 0) {
    System.err.println("ERROR! Destination database is not empty.")
    sys.exit(-1)
  }

  println("audit ... ")
  sqlite.audit.listNetworkFees(from, to).foreach { fee =>
    psql.audit.asInstanceOf[PsqlAuditDb].add(NetworkFeePaid(
      channel = null, remoteNodeId = fee.remoteNodeId, channelId = fee.channelId, tx = null, fee = fee.fee, txType = fee.txType
    ), txId = fee.txId)
  }

  sqlite.audit.listReceived(from, to).foreach { received =>
    psql.audit.add(received)
  }

  sqlite.audit.listRelayed(from, to).foreach { relayed =>
    psql.audit.add(relayed)
  }

  sqlite.audit.listSent(from, to).foreach { sent =>
    psql.audit.add(sent)
  }

  println("channels ... ")

  sqlite.channels.listLocalChannels().foreach { hasCommitments =>
    psql.channels.addOrUpdateChannel(hasCommitments)
    sqlite.channels.asInstanceOf[SqliteChannelsDb].listHtlcInfos(hasCommitments.channelId).foreach { case (paymentHash, cltvExpiry, commitmentNumber) =>
      psql.channels.addHtlcInfo(hasCommitments.channelId, commitmentNumber, paymentHash, cltvExpiry)
    }
  }

  println("network ... ")

  sqlite.network.listChannels().foreach { case (_, channel) =>
    psql.network.addChannel(channel.ann, channel.fundingTxid, channel.capacity)
  }

  sqlite.network.listNodes().foreach { announcement =>
    psql.network.addNode(announcement)
  }

  println("payments ... ")

  sqlite.payments.asInstanceOf[SqlitePaymentsDb].listIncomingPayments().foreach { incomingPayment =>
    psql.payments.asInstanceOf[PsqlPaymentsDb].addIncomingPayment(incomingPayment)
  }

  sqlite.payments.asInstanceOf[SqlitePaymentsDb].listOutgoingPayments().foreach { outgoingPayment =>
    psql.payments.asInstanceOf[PsqlPaymentsDb].addOutgoingPayment(outgoingPayment)
  }

  println("peers ... ")

  sqlite.peers.listPeers().foreach { case (nodeId, nodeAddress) =>
    psql.peers.addOrUpdatePeer(nodeId, nodeAddress)
  }

  println("pendingRelay ... ")

  sqlite.pendingRelay.listPendingRelay().map(_._1).foreach { channelId =>
    sqlite.pendingRelay.listPendingRelay(channelId).foreach { cmd =>
      psql.pendingRelay.addPendingRelay(channelId, cmd)
    }
  }

  println("Done!")

}
