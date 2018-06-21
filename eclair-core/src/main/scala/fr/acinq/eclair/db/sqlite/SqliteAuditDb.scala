package fr.acinq.eclair.db.sqlite

import java.sql.Connection

import fr.acinq.bitcoin.{BinaryData, MilliSatoshi}
import fr.acinq.eclair.db.sqlite.SqliteUtils.{using,getVersion}
import fr.acinq.eclair.db._
import grizzled.slf4j.Logging
import scala.compat.Platform
import fr.acinq.eclair.db.ChannelBalances
import fr.acinq.eclair.payment._

import scala.collection.immutable.Queue

/**
  * Audit entries are stored in the audit table.
  */
class SqliteAuditDb(sqlite: Connection) extends AuditDb with Logging {
  val DB_NAME = "audit"
  val CURRENT_VERSION = 1

  using(sqlite.createStatement()) { statement =>
    require(getVersion(statement, DB_NAME, CURRENT_VERSION) == CURRENT_VERSION) // there is only one version currently deployed
    statement.executeUpdate("""
      CREATE TABLE IF NOT EXISTS audit (timemsec INTEGER NOT NULL,channelid BLOB NOT NULL,otherchannelid BLOB NOT NULL,
        localhtlcid INTEGER NOT NULL, remotehtlcid INTEGER NOT NULL, amountmsat INTEGER NOT NULL, htlcmsat INTEGER NOT NULL, profitinmsat INTEGER NOT NULL, profitoutmsat INTEGER NOT NULL,
        feemsat INTEGER NOT NULL,entrytype TEXT NOT NULL, paymenttx_hash BLOB NOT NULL, btcamount INTEGER NOT NULL, btcfee INTEGER NOT NULL, delayed INTEGER NOT NULL, 
        penalty INTEGER NOT NULL, roundingmsat INTEGER NOT NULL)
      """)
    statement.executeUpdate("CREATE INDEX IF NOT EXISTS audit_channel_idx ON audit(channelid)")
    statement.executeUpdate("CREATE INDEX IF NOT EXISTS audit_channel_close_idx ON audit(channelid,paymenttx_hash)")
  }

//select timemsec ,quote(channelid),quote(otherchannelid),localhtlcid,remotehtlcid,amountmsat,profitmsat,feemsat,entrytype,quote(targetnodeid),quote(paymenttx_hash) from audit;
  override def checkExists(a: AuditEntry): Boolean = {
    if(a.entryType==CLOSE_REVOKED_COMMIT) {
      //ignore amount and penalty for these entries as varies due to booking the local into penalty
      using(sqlite.prepareStatement("SELECT * FROM audit WHERE channelid=? AND otherchannelid=? AND localhtlcid=? AND remotehtlcid=?  AND htlcmsat=? AND " + 
          " profitinmsat=? AND Profitoutmsat=? AND feemsat=? AND entrytype=? AND paymenttx_hash=? AND btcamount=? AND btcfee=? AND delayed=? ")) { statement =>
        statement.setBytes(1, a.channel)
        statement.setBytes(2, a.otherChannel)
        statement.setLong(3,a.localHtlcId)
        statement.setLong(4,a.remoteHtlcId)
        statement.setLong(5,a.htlcMsat.amount)
        statement.setLong(6,a.profitInMsat.amount)
        statement.setLong(7,a.profitOutMsat.amount)
        statement.setLong(8,a.feeMsat.amount)
        statement.setString(9,a.entryType.toString())
        statement.setBytes(10, a.paymentTxHash)
        statement.setLong(11, a.btcAmount)
        statement.setLong(12, a.btcFee)
        statement.setLong(13, a.delayed)
        val rs=statement.executeQuery();
        rs.next() // if we find a row then already logged this tx
        // This is needed as in some closing cases we double trigger the message due to watcher being registered twice.
        
      }
    } else {
      
      using(sqlite.prepareStatement("SELECT * FROM audit WHERE channelid=? AND otherchannelid=? AND localhtlcid=? AND remotehtlcid=? AND amountmsat=? AND htlcmsat=? AND " + 
          " profitinmsat=? AND Profitoutmsat=? AND feemsat=? AND entrytype=? AND paymenttx_hash=? AND btcamount=? AND btcfee=? AND delayed=? AND penalty=? AND roundingmsat=?")) { statement =>
        statement.setBytes(1, a.channel)
        statement.setBytes(2, a.otherChannel)
        statement.setLong(3,a.localHtlcId)
        statement.setLong(4,a.remoteHtlcId)
        statement.setLong(5,a.amountMsat.amount)
        statement.setLong(6,a.htlcMsat.amount)
        statement.setLong(7,a.profitInMsat.amount)
        statement.setLong(8,a.profitOutMsat.amount)
        statement.setLong(9,a.feeMsat.amount)
        statement.setString(10,a.entryType.toString())
        statement.setBytes(11, a.paymentTxHash)
        statement.setLong(12, a.btcAmount)
        statement.setLong(13, a.btcFee)
        statement.setLong(14, a.delayed)
        statement.setLong(15, a.penalty)
        statement.setLong(16, a.roundingMsat)
        val rs=statement.executeQuery();
        rs.next() // if we find a row then already logged this tx
        // This is needed as in some closing cases we double trigger the message due to watcher being registered twice.
        
      }
    }
  }
  override def addAuditEntry(a: AuditEntry): Unit = {
    using(sqlite.prepareStatement("INSERT INTO audit VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setLong(1,Platform.currentTime)
      statement.setBytes(2, a.channel)
      statement.setBytes(3, a.otherChannel)
      statement.setLong(4,a.localHtlcId)
      statement.setLong(5,a.remoteHtlcId)
      statement.setLong(6,a.amountMsat.amount)
      statement.setLong(7,a.htlcMsat.amount)
      statement.setLong(8,a.profitInMsat.amount)
      statement.setLong(9,a.profitOutMsat.amount)
      statement.setLong(10,a.feeMsat.amount)
      statement.setString(11,a.entryType.toString())
      statement.setBytes(12, a.paymentTxHash)
      statement.setLong(13, a.btcAmount)
      statement.setLong(14, a.btcFee)
      statement.setLong(15, a.delayed)
      statement.setLong(16, a.penalty)
      statement.setLong(17, a.roundingMsat)
      val res = statement.executeUpdate()
      logger.debug(s"inserted $res audit entry=${a} in DB")
    }
  }
//select quote(channelid),max(htlcid),sum(amountmsat),sum(profitmsat),sum(feemsat) from audit group by channelid;

  override def checkChannelClosed(channelId: BinaryData, txid: BinaryData): Boolean = {
    using(sqlite.prepareStatement("SELECT * FROM audit WHERE channelid=? and paymenttx_hash=?")) { statement =>
      statement.setBytes(1,channelId)
      statement.setBytes(2,txid)
      val rs=statement.executeQuery();

      rs.next() // if we find a row then already closed with this TX
    }
  }


  override def checkRelayAdded(channelId: BinaryData, htlcId: Long, paymentHash: BinaryData): Boolean = {
    using(sqlite.prepareStatement("SELECT * FROM audit WHERE channelid=? and remotehtlcid=? and paymenttx_hash=?")) { statement =>
      statement.setBytes(1,channelId)
      statement.setLong(2,htlcId)
      statement.setBytes(3,paymentHash)
      val rs=statement.executeQuery();
      rs.next() // if we find a row then already logged this tx
      // This is needed as in some closing cases we double trigger the message due to watcher being registered twice.
    }
  }
  
  override def checkSentAdded(channelId: BinaryData, htlcId: Long, paymentHash: BinaryData): Boolean = {
    using(sqlite.prepareStatement("SELECT * FROM audit WHERE channelid=? and localhtlcid=? and paymenttx_hash=? and entryType='"+LN_ADD_HTLC+"'")) { statement =>
      statement.setBytes(1,channelId)
      statement.setLong(2,htlcId)
      statement.setBytes(3,paymentHash)
      val rs=statement.executeQuery();
      rs.next() // if we find a row then already logged this tx
      // This is needed as in some closing cases we double trigger the message due to watcher being registered twice.
    }
  }

  override def checkExists(channelId: BinaryData ,paymentTxHash: BinaryData): Boolean = {
    using(sqlite.prepareStatement("SELECT * FROM audit WHERE channelid=? and paymenttx_hash=?")) { statement =>
      statement.setBytes(1,channelId)
      statement.setBytes(2,paymentTxHash)
      val rs=statement.executeQuery();
      rs.next() // if we find a row then already logged this tx
      // This is needed as in some closing cases we double trigger the message due to watcher being registered twice.
    }
  }
  
  val checkSQL="SELECT * FROM audit WHERE channelid=? and localhtlcid=? and " + 
    "( entrytype='"+LN_ERROR_HTLC+"' or entrytype='"+LN_PAYMENT+"' or entrytype='"+LN_RELAY+"')"
  def checkExists(channelId: BinaryData , htlcId: Long): Boolean = {
    using(sqlite.prepareStatement(checkSQL)) { statement =>
      statement.setBytes(1,channelId)
      statement.setLong(2,htlcId)
      val rs=statement.executeQuery();
      rs.next() // if we find a row then already logged this update
    }
  }
  
  val errorSQL="SELECT otherchannelid,amountmsat,htlcmsat,paymenttx_hash FROM audit WHERE channelid=? and localhtlcid=? and entrytype='"+LN_ADD_HTLC+"'"
  override def errorHtlc(channelId: BinaryData, htlcId: Long): Unit = {
    // check if same channel/channel/hash/htlcidl/htclidr has already been paid/errored 1st.
    if(!checkExists(channelId,htlcId)) {
    
      using(sqlite.prepareStatement(errorSQL)) { statement =>
        statement.setBytes(1,channelId)
        statement.setLong(2,htlcId)
        val rs=statement.executeQuery()
        if (rs.next()){
          val a=AuditEntry(channelId,rs.getBytes(1),htlcId,-1L,MilliSatoshi(-rs.getLong(2)),MilliSatoshi(-rs.getLong(3)),MilliSatoshi(0),
              MilliSatoshi(0),MilliSatoshi(0),LN_ERROR_HTLC,rs.getBytes(4),0,0)
          addAuditEntry(a)
        }
      }
    }
  }
 // val balanceSql="SELECT max(localhtlcid),max(remotehtlcid),sum(amountmsat),sum(htlcmsat),sum(profitinmsat),sum(profitoutmsat),sum(feemsat),sum(btcamount),sum(btcfee), " + 
 //      "sum(penalty), sum(delayed), sum(CASE WHEN entrytype in ('"+LN_PAYMENT+"','"+LN_RELAY+"') THEN htlcmsat ELSE 0 END), sum(CASE WHEN entrytype in ('"+LN_RECEIPT+"','"+LN_RELAY+"') THEN amountmsat ELSE 0 END) " + 
 //       " , sum(roundingmsat) FROM audit WHERE channelid=? group by channelid"
  
  val balanceSql="SELECT max(localhtlcid),max(remotehtlcid),sum(amountmsat),sum(htlcmsat),sum(profitinmsat),sum(profitoutmsat),sum(feemsat),sum(btcamount),sum(btcfee), " + 
        "sum(penalty), sum(delayed), sum(CASE WHEN entrytype in ('"+LN_PAYMENT+"','"+LN_RELAY+"') THEN htlcmsat ELSE 0 END), sum(CASE WHEN entrytype in ('"+LN_RECEIPT+"','"+LN_RELAY+"') THEN amountmsat ELSE 0 END) " + 
        " , sum(roundingmsat), sum(CASE WHEN entrytype ='"+LN_RELAY+"' THEN htlcmsat ELSE 0 END), sum(CASE WHEN entrytype ='"+LN_RELAY+"' THEN amountmsat ELSE 0 END) " +
        " FROM audit WHERE channelid=? group by channelid"
        
  override def channelBalances(channelId: BinaryData): ChannelBalances = {
    using(sqlite.prepareStatement(balanceSql)) { statement =>
      statement.setBytes(1,channelId)
      val rs=statement.executeQuery();

      if (!rs.next() ) ChannelBalances(0,0,MilliSatoshi(0),MilliSatoshi(0),MilliSatoshi(0),MilliSatoshi(0),MilliSatoshi(0),0,0,0,0,MilliSatoshi(0),MilliSatoshi(0)) else 
        ChannelBalances(rs.getLong(1),rs.getLong(2),MilliSatoshi(rs.getLong(3)),MilliSatoshi(rs.getLong(4)),MilliSatoshi(rs.getLong(5)),
            MilliSatoshi(rs.getLong(6)),MilliSatoshi(rs.getLong(7)), rs.getLong(8), rs.getLong(9),rs.getLong(10),rs.getLong(11),
            MilliSatoshi(rs.getLong(12)), MilliSatoshi(rs.getLong(13)), MilliSatoshi(rs.getLong(14)), MilliSatoshi(rs.getLong(15)), MilliSatoshi(rs.getLong(16)) )
    }
  }
  def updateRounding(channelId:BinaryData) {
    val bal=channelBalances(channelId)

    val a=AuditEntry(channel=channelId,entryType=ROUNDING_CLOSE, htlcMsat = -bal.htlcAmount,roundingMsat= -bal.htlcAmount.amount)
    addAuditEntry(a)

    // this is for trimmed outputs where receive a payment but is trimmed and they publish the previous commit where it was in fees rather than in toLocal
    val a2=AuditEntry(channel=channelId,entryType=ROUNDING_CLOSE, amountMsat = -bal.amount,roundingMsat= -bal.amount.amount)
    addAuditEntry(a2)
    
  }

  val getDailyDataSql="""select timemsec/86400000,
sum(case when entrytype='LN_PAYMENT' then 1 else 0 end) as sent,
sum(case when entrytype='LN_RECEIPT' then 1 else 0 end) as receive,
sum(case when entrytype='LN_RELAY' then 1 else 0 end)/2 as relay,
sum(case when entrytype='OPEN_FUNDING_LOCKED' then 1 else 0 end) as channelopen,
sum(case when entrytype='MUTUAL_CLOSE' or entrytype='COMMIT_CLOSE' or entrytype='CLOSE_REVOKED_COMMIT' then 1 else 0 end) as channelclose,
sum(feemsat) as lnfeemsat,
sum(profitinmsat+profitoutmsat) as lnprofitmsat,
sum(btcfee) as btcfee
from audit
group by timemsec/86400000 order by timemsec/86400000"""

  override def dailyData(): Seq[DAILY_STATS] = {
    using(sqlite.prepareStatement(getDailyDataSql)) { statement =>
      val rs=statement.executeQuery();
      var s: Seq[DAILY_STATS] = Seq()
      while (rs.next()) {
        s = s :+ DAILY_STATS(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5),rs.getLong(6),rs.getLong(7),rs.getLong(8),rs.getLong(9))
      }
      s
    }
  }
}
