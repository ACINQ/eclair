package fr.acinq.eclair.db.migration

import fr.acinq.eclair.BlockHeight
import fr.acinq.eclair.channel.{DATA_CLOSING, DATA_WAIT_FOR_FUNDING_CONFIRMED}
import fr.acinq.eclair.db.migration.CompareDb._
import fr.acinq.eclair.wire.internal.channel.ChannelCodecs.channelDataCodec
import scodec.bits.ByteVector

import java.sql.{Connection, ResultSet}

object CompareChannelsDb {

  private def compareChannelsTable(conn1: Connection, conn2: Connection): Boolean = {
    val table1 = "local_channels"
    val table2 = "local.channels"

    def hash1(rs: ResultSet): ByteVector = {
      val data = ByteVector(rs.getBytes("data"))
      val data_modified = channelDataCodec.decode(data.bits).require.value match {
        case c: DATA_WAIT_FOR_FUNDING_CONFIRMED => channelDataCodec.encode(c.copy(waitingSince = BlockHeight(0))).require.toByteVector
        case c: DATA_CLOSING => channelDataCodec.encode(c.copy(waitingSince = BlockHeight(0))).require.toByteVector
        case _ => data
      }
      bytes(rs, "channel_id") ++
        data_modified ++
        bool(rs, "is_closed") ++
        longtsnull(rs, "created_timestamp") ++
        longtsnull(rs, "last_payment_sent_timestamp") ++
        longtsnull(rs, "last_payment_received_timestamp") ++
        longtsnull(rs, "last_connected_timestamp") ++
        longtsnull(rs, "closed_timestamp")
    }

    def hash2(rs: ResultSet): ByteVector = {
      val data = ByteVector(rs.getBytes("data"))
      val data_modified = channelDataCodec.decode(data.bits).require.value match {
        case c: DATA_WAIT_FOR_FUNDING_CONFIRMED => channelDataCodec.encode(c.copy(waitingSince = BlockHeight(0))).require.toByteVector
        case c: DATA_CLOSING => channelDataCodec.encode(c.copy(waitingSince = BlockHeight(0))).require.toByteVector
        case _ => data
      }
      hex(rs, "channel_id") ++
        data_modified ++
        bool(rs, "is_closed") ++
        tsnull(rs, "created_timestamp") ++
        tsnull(rs, "last_payment_sent_timestamp") ++
        tsnull(rs, "last_payment_received_timestamp") ++
        tsnull(rs, "last_connected_timestamp") ++
        tsnull(rs, "closed_timestamp")
    }

    compareTable(conn1, conn2, table1, table2, hash1, hash2)
  }

  private def compareHtlcInfosTable(conn1: Connection, conn2: Connection): Boolean = {
    val table1 = "htlc_infos"
    val table2 = "local.htlc_infos"

    def hash1(rs: ResultSet): ByteVector = {
      bytes(rs, "channel_id") ++
        long(rs, "commitment_number") ++
        bytes(rs, "payment_hash") ++
        long(rs, "cltv_expiry")
    }

    def hash2(rs: ResultSet): ByteVector = {
      hex(rs, "channel_id") ++
        long(rs, "commitment_number") ++
        hex(rs, "payment_hash") ++
        long(rs, "cltv_expiry")
    }

    compareTable(conn1, conn2, table1, table2, hash1, hash2)
  }

  def compareAllTables(conn1: Connection, conn2: Connection): Boolean = {
    compareChannelsTable(conn1, conn2) &&
      compareHtlcInfosTable(conn1, conn2)
  }

}
