package fr.acinq.eclair.db.sqlite

import java.sql.Connection

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.db.PreimagesDb

class SqlitePreimagesDb(sqlite: Connection) extends PreimagesDb {

  {
    val statement = sqlite.createStatement
    // note: should we use a foreign key to local_channels table here?
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS preimages (channel_id BLOB NOT NULL, htlc_id INTEGER NOT NULL, preimage BLOB NOT NULL, PRIMARY KEY(channel_id, htlc_id))")
  }

  override def addPreimage(channelId: BinaryData, htlcId: Long, paymentPreimage: BinaryData): Unit = {
    val statement = sqlite.prepareStatement("INSERT OR IGNORE INTO preimages VALUES (?, ?, ?)")
    statement.setBytes(1, channelId)
    statement.setLong(2, htlcId)
    statement.setBytes(3, paymentPreimage)
    statement.executeUpdate()
  }

  override def removePreimage(channelId: BinaryData, htlcId: Long): Unit = {
    val statement = sqlite.prepareStatement("DELETE FROM preimages WHERE channel_id=? AND htlc_id=?")
    statement.setBytes(1, channelId)
    statement.setLong(2, htlcId)
    statement.executeUpdate()
  }

  override def listPreimages(channelId: BinaryData): List[(BinaryData, Long, BinaryData)] = {
    val statement = sqlite.prepareStatement("SELECT htlc_id, preimage FROM preimages WHERE channel_id=?")
    statement.setBytes(1, channelId)
    val rs = statement.executeQuery()
    var l: List[(BinaryData, Long, BinaryData)] = Nil
    while (rs.next()) {
      l = l :+ (channelId, rs.getLong("htlc_id"), BinaryData(rs.getBytes("preimage")))
    }
    l
  }
}
