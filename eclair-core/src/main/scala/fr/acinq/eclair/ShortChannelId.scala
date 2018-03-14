package fr.acinq.eclair

/**
  * A short channel id uniquely identifies a channel by the coordinates of its funding tx output in the blockchain.
  *
  * See BOLT 7: https://github.com/lightningnetwork/lightning-rfc/blob/master/07-routing-gossip.md#requirements
  *
  */
case class ShortChannelId(private val id: Long) {

  def toLong: Long = id

  override def toString: String = id.toHexString
}

object ShortChannelId {

  def apply(s: String): ShortChannelId = ShortChannelId(java.lang.Long.parseLong(s, 16))

  def apply(blockHeight: Int, txIndex: Int, outputIndex: Int): ShortChannelId = ShortChannelId(toShortId(blockHeight, txIndex, outputIndex))

  def toShortId(blockHeight: Int, txIndex: Int, outputIndex: Int): Long = ((blockHeight & 0xFFFFFFL) << 40) | ((txIndex & 0xFFFFFFL) << 16) | (outputIndex & 0xFFFFL)

  def coordinates(shortChannelId: ShortChannelId): TxCoordinates = TxCoordinates(((shortChannelId.id >> 40) & 0xFFFFFF).toInt, ((shortChannelId.id >> 16) & 0xFFFFFF).toInt, (shortChannelId.id & 0xFFFF).toInt)
}

case class TxCoordinates(blockHeight: Int, txIndex: Int, outputIndex: Int)