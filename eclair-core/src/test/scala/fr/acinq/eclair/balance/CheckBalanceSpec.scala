package fr.acinq.eclair.balance

import fr.acinq.bitcoin.{ByteVector32, SatoshiLong}
import fr.acinq.eclair.balance.CheckBalance.{ClosingBalance, OffChainBalance, PossiblyPublishedMainAndHtlcBalance, PossiblyPublishedMainBalance}
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.db.jdbc.JdbcUtils.ExtendedResultSet._
import fr.acinq.eclair.db.pg.PgUtils.using
import fr.acinq.eclair.randomBytes32
import fr.acinq.eclair.wire.internal.channel.ChannelCodecs.stateDataCodec
import org.scalatest.funsuite.AnyFunSuite
import org.sqlite.SQLiteConfig

import java.io.File
import java.sql.DriverManager
import scala.collection.immutable.Queue
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

class CheckBalanceSpec extends AnyFunSuite {

  ignore("compute from eclair.sqlite") {
    val dbFile = new File("eclair.sqlite")
    val sqliteConfig = new SQLiteConfig()
    sqliteConfig.setReadOnly(true)
    val sqlite = DriverManager.getConnection(s"jdbc:sqlite:$dbFile", sqliteConfig.toProperties)
    val channels = using(sqlite.createStatement) { statement =>
      statement.executeQuery("SELECT data FROM local_channels WHERE is_closed=0")
        .mapCodec(stateDataCodec)
    }
    val knownPreimages: Set[(ByteVector32, Long)] = using(sqlite.prepareStatement("SELECT channel_id, htlc_id FROM pending_relay")) { statement =>
      val rs = statement.executeQuery()
      var q: Queue[(ByteVector32, Long)] = Queue()
      while (rs.next()) {
        q = q :+ (rs.getByteVector32("channel_id"), rs.getLong("htlc_id"))
      }
      q.toSet
    }
    val res = CheckBalance.computeOffChainBalance(channels, knownPreimages)
    println(res)
    println(res.total)
  }

  test("tx pruning") {

    val txids = (for (_ <- 0 until 20) yield randomBytes32()).toList
    val knownTxids = Set(txids(1), txids(3), txids(4), txids(6), txids(9), txids(12), txids(13))

    val bitcoinClient = new ExtendedBitcoinClient(null) {
      /** Get the number of confirmations of a given transaction. */
      override def getTxConfirmations(txid: ByteVector32)(implicit ec: ExecutionContext): Future[Option[Int]] =
        Future.successful(if (knownTxids.contains(txid)) Some(42) else None)
    }

    val bal1 = OffChainBalance(
      closing = ClosingBalance(
        localCloseBalance = PossiblyPublishedMainAndHtlcBalance(
          toLocal = Map(
            txids(0) -> 1000.sat,
            txids(1) -> 1000.sat,
            txids(2) -> 1000.sat),
          htlcs = Map(
            txids(3) -> 1000.sat,
            txids(4) -> 1000.sat,
            txids(5) -> 1000.sat)
        ),
        remoteCloseBalance = PossiblyPublishedMainAndHtlcBalance(
          toLocal = Map(
            txids(6) -> 1000.sat,
            txids(7) -> 1000.sat,
            txids(8) -> 1000.sat,
            txids(9) -> 1000.sat),
          htlcs = Map(
            txids(10) -> 1000.sat,
            txids(11) -> 1000.sat,
            txids(12) -> 1000.sat),
        ),
        mutualCloseBalance = PossiblyPublishedMainBalance(
          toLocal = Map(
            txids(13) -> 1000.sat,
            txids(14) -> 1000.sat
          )
        )
      )
    )

    val bal2 = Await.result(CheckBalance.prunePublishedTransactions(bal1, bitcoinClient)(ExecutionContext.Implicits.global), 10 seconds)


    assert(bal2 == OffChainBalance(
      closing = ClosingBalance(
        localCloseBalance = PossiblyPublishedMainAndHtlcBalance(
          toLocal = Map(
            txids(0) -> 1000.sat,
            txids(2) -> 1000.sat),
          htlcs = Map(
            txids(5) -> 1000.sat)
        ),
        remoteCloseBalance = PossiblyPublishedMainAndHtlcBalance(
          toLocal = Map(
            txids(7) -> 1000.sat,
            txids(8) -> 1000.sat),
          htlcs = Map(
            txids(10) -> 1000.sat,
            txids(11) -> 1000.sat),
        ),
        mutualCloseBalance = PossiblyPublishedMainBalance(
          toLocal = Map(
            txids(14) -> 1000.sat
          )
        )))
    )
  }

}
