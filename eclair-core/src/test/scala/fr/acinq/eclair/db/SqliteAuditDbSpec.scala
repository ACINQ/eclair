package fr.acinq.eclair.db

import java.sql.DriverManager

import fr.acinq.bitcoin.{BinaryData, MilliSatoshi}
import fr.acinq.eclair.db.sqlite.SqliteAuditDb
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner
import fr.acinq.eclair.payment._

@RunWith(classOf[JUnitRunner])
class SqliteAuditDbSpec extends FunSuite {

  def inmem = DriverManager.getConnection("jdbc:sqlite::memory:")

  test("init sqlite 2 times in a row") {
    val sqlite = inmem
    val db1 = new SqliteAuditDb(sqlite)
    val db2 = new SqliteAuditDb(sqlite)
  }

  test("add and check balances") {
    val sqlite = inmem
    val db = new SqliteAuditDb(sqlite)
    db.addAuditEntry(AuditEntry(channel="00"*32,otherChannel="01"*32,localHtlcId=0,remoteHtlcId=0,amountMsat=MilliSatoshi(1000000),
        profitInMsat=MilliSatoshi(100),profitOutMsat=MilliSatoshi(200), entryType=LN_RECEIPT,paymentTxHash="42"*32))

    db.addAuditEntry(AuditEntry(channel="00"*32,otherChannel="01"*32,localHtlcId=1,remoteHtlcId=0,amountMsat=MilliSatoshi(0)-MilliSatoshi(100000),entryType=LN_PAYMENT,paymentTxHash="42"*32))

    assert(db.channelBalances("00"*32) ==  ChannelBalances(1,0,MilliSatoshi(900000),MilliSatoshi(0),MilliSatoshi(100),MilliSatoshi(200),MilliSatoshi(0),0,0,0,0,MilliSatoshi(0),MilliSatoshi(1000000)))

    db.addAuditEntry(AuditEntry(channel="00"*32,otherChannel="01"*32,localHtlcId=2,remoteHtlcId=0,amountMsat=MilliSatoshi(0)-MilliSatoshi(100000),
        htlcMsat=MilliSatoshi(100000),entryType=LN_ADD_HTLC,paymentTxHash="42"*32))
    assert(db.channelBalances("00"*32) ==  ChannelBalances(2,0,MilliSatoshi(800000),MilliSatoshi(100000),MilliSatoshi(100),MilliSatoshi(200),MilliSatoshi(0),0,0,0,0,MilliSatoshi(0),MilliSatoshi(1000000))) //check entry added
    db.errorHtlc("00"*32,2)
    assert(db.channelBalances("00"*32) ==  ChannelBalances(2,0,MilliSatoshi(900000),MilliSatoshi(0),MilliSatoshi(100),MilliSatoshi(200),MilliSatoshi(0),0,0,0,0,MilliSatoshi(0),MilliSatoshi(1000000))) // check rolled back
    
  }
}
