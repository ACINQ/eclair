package fr.acinq.eclair


import java.util.BitSet
import java.util.function.IntPredicate

import fr.acinq.bitcoin.BinaryData


/**
  * Created by PM on 13/02/2017.
  */
object Features {
  val INITIAL_ROUTING_SYNC_BIT_MANDATORY = 0
  val INITIAL_ROUTING_SYNC_BIT_OPTIONAL = 3


  /**
    * Check that we understand their feature bits and that they are consistent with our own
    * @param localFeatures local feature bits
    * @param remoteFeatures remote feature bits
    * @return true if we must disconnect
    */
  def mustDisconnect(localFeatures: BinaryData, remoteFeatures: BinaryData) : Boolean = {
    val local = BitSet.valueOf(localFeatures.reverse.toArray)
    val remote = BitSet.valueOf(remoteFeatures.reverse.toArray)
    // both bits cannot be set
    !areSupported(remote)
  }

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
    if (bitset.get(INITIAL_ROUTING_SYNC_BIT_MANDATORY)) false
    else bitset.stream().noneMatch(new IntPredicate {
      override def test(value: Int) = value % 2 == 0 && value > INITIAL_ROUTING_SYNC_BIT_OPTIONAL
    })
  }

  /**
    * A feature set is supported if all even bits are supported.
    * We just ignore unknown odd bits.
    */
  def areSupported(features: BinaryData): Boolean = areSupported(BitSet.valueOf(features.reverse.toArray))
}
