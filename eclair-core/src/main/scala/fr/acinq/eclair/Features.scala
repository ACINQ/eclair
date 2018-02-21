package fr.acinq.eclair

import java.util.BitSet

import fr.acinq.bitcoin.BinaryData


/**
  * Created by PM on 13/02/2017.
  */
object Features {
  // reserved but not used as per lightningnetwork/lightning-rfc/pull/178
  val INITIAL_ROUTING_SYNC_BIT_MANDATORY = 2
  val INITIAL_ROUTING_SYNC_BIT_OPTIONAL = 3
  val CHANNEL_RANGE_QUERIES_BIT_OPTIONAL = 7

  /**
    *
    * @param features feature bits
    * @return true if an initial dump of the routing table is requested
    */
  def initialRoutingSync(features: BitSet): Boolean = hasFeature(features, INITIAL_ROUTING_SYNC_BIT_OPTIONAL)

  /**
    *
    * @param features feature bits
    * @return true if an initial dump of the routing table is requested
    */
  def initialRoutingSync(features: BinaryData): Boolean = initialRoutingSync(BitSet.valueOf(features.reverse.toArray))

  def hasFeature(features: BitSet, bit: Int): Boolean = features.get(bit)

  def hasFeature(features: BinaryData, bit: Int): Boolean = hasFeature(BitSet.valueOf(features.reverse.toArray), bit)

  /**
    * Check that the features that we understand are correctly specified, and that there are no mandatory features that
    * we don't understand (even bits)
    */
  def areSupported(bitset: BitSet): Boolean = {
    for (i <- 0 until bitset.length() by 2) {
      if (bitset.get(i)) return false
    }
    return true
  }

  /**
    * A feature set is supported if all even bits are supported.
    * We just ignore unknown odd bits.
    */
  def areSupported(features: BinaryData): Boolean = areSupported(BitSet.valueOf(features.reverse.toArray))
}
