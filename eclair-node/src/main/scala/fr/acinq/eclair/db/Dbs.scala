package fr.acinq.eclair.db

import fr.acinq.bitcoin.BinaryData
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.channel.Data
import fr.acinq.eclair.crypto.TransportHandler.Serializer
import fr.acinq.eclair.io.PeerRecord
import fr.acinq.eclair.router.Router.State

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

  def makeRouterDb(db: SimpleDb): SimpleTypedDb[String, State] = {
    // we use a single key: router.state
    new SimpleTypedDb[String, State](
      _ => "router.state",
      s => if (s == "router.state") Some("router.state") else None,
      new Serializer[State] {
        override def serialize(t: State): BinaryData = JavaSerializer.serialize(t.fixme)

        override def deserialize(bin: BinaryData): State = JavaSerializer.deserialize[State](bin)
      },
      db
    )

  }

  def makePeerDb(db: SimpleDb): SimpleTypedDb[PublicKey, PeerRecord] = {
    def peerid2String(id: PublicKey) = s"peer-$id"

    def string2peerid(s: String) = if (s.startsWith("peer-")) Some(PublicKey(BinaryData(s.stripPrefix("peer-")))) else None

    new SimpleTypedDb[PublicKey, PeerRecord](
      peerid2String,
      string2peerid,
      new Serializer[PeerRecord] {
        override def serialize(t: PeerRecord): BinaryData = JavaSerializer.serialize(t)

        override def deserialize(bin: BinaryData): PeerRecord = JavaSerializer.deserialize[PeerRecord](bin)
      },
      db
    )
  }

}
