package fr.acinq.eclair.db

import fr.acinq.bitcoin.BinaryData
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.channel.{Data, HasCommitments}
import fr.acinq.eclair.io.PeerRecord
import fr.acinq.eclair.wire.{ChannelCodecs, LightningMessage, LightningMessageCodecs}

/**
  * Created by PM on 28/02/2017.
  */
object Dbs {

  def makeChannelDb(db: SimpleDb): SimpleTypedDb[BinaryData, HasCommitments] = {
    def channelid2String(id: BinaryData) = s"channel-$id"

    def string2channelid(s: String) = if (s.startsWith("channel-")) Some(BinaryData(s.stripPrefix("channel-"))) else None

    new SimpleTypedDb[BinaryData, HasCommitments](
      channelid2String,
      string2channelid,
      ChannelCodecs.stateDataCodec,
      db
    )
  }

  def makeAnnouncementDb(db: SimpleDb): SimpleTypedDb[String, LightningMessage] = {
    // we use a single key: router.state
    new SimpleTypedDb[String, LightningMessage](
      s => s,
      s => if (s.startsWith("ann-")) Some(s) else None,
      LightningMessageCodecs.lightningMessageCodec,
      db
    )
  }

  def makePeerDb(db: SimpleDb): SimpleTypedDb[PublicKey, PeerRecord] = {
    def peerid2String(id: PublicKey) = s"peer-$id"

    def string2peerid(s: String) = if (s.startsWith("peer-")) Some(PublicKey(BinaryData(s.stripPrefix("peer-")))) else None

    new SimpleTypedDb[PublicKey, PeerRecord](
      peerid2String,
      string2peerid,
      ChannelCodecs.peerRecordCodec,
      db
    )
  }

}
