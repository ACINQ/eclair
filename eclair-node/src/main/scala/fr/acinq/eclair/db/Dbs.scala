package fr.acinq.eclair.db

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.channel.Data
import fr.acinq.eclair.crypto.TransportHandler.Serializer

/**
  * Created by PM on 28/02/2017.
  */
object Dbs {

  def makeChannelDb(db: SimpleDb): SimpleTypedDb[Long, Data] = {
    def channelid2String(id: Long) = s"channel-$id"

    def string2channelid(s: String) = if (s.startsWith("channel-")) Some(s.stripPrefix("channel-").toLong) else None

    new SimpleTypedDb[Long, Data](
      channelid2String,
      string2channelid,
      new Serializer[Data] {
        override def serialize(t: Data): BinaryData = JavaSerializer.serialize(t)

        override def deserialize(bin: BinaryData): Data = JavaSerializer.deserialize[Data](bin)
      },
      db
    )
  }

}
