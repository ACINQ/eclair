package fr.acinq.eclair


import java.util.BitSet
import java.util.function.IntPredicate

import fr.acinq.bitcoin.BinaryData


/**
  * Created by PM on 13/02/2017.
  */
object Features {
  // reserved but not used as per lightningnetwork/lightning-rfc/pull/178
  val INITIAL_ROUTING_SYNC_BIT_MANDATORY = 2
  val INITIAL_ROUTING_SYNC_BIT_OPTIONAL = 3

  /**
    *
    * @param features feature bits
    * @return true if an initial dump of the routing table is requested
    */
  def initialRoutingSync(features: BitSet) : Boolean = features.get(INITIAL_ROUTING_SYNC_BIT_OPTIONAL)

  /**
    *
    * @param features feature bits
    * @return true if an initial dump of the routing table is requested
    */
  def initialRoutingSync(features: BinaryData) : Boolean = initialRoutingSync(BitSet.valueOf(features.reverse.toArray))

  /**
    * Check that the features that we understand are correctly specified, and that there are no mandatory features that
    * we don't understand (even bits)
    */
  def areSupported(bitset: BitSet): Boolean = {
    // for now there is no mandatory feature bit, so we don't support features with any even bit set
    bitset.stream().noneMatch(new IntPredicate {
      override def test(value: Int) = value % 2 == 0
    })
  }

  /**
    * A feature set is supported if all even bits are supported.
    * We just ignore unknown odd bits.
    */
  def areSupported(features: BinaryData): Boolean = areSupported(BitSet.valueOf(features.reverse.toArray))
}
