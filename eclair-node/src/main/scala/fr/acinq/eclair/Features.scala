package fr.acinq.eclair


import java.util.BitSet
import java.util.function.IntPredicate

import fr.acinq.bitcoin.BinaryData


/**
  * Created by PM on 13/02/2017.
  */
object Features {

  val CHANNELS_PUBLIC_BIT = 0
  val INITIAL_ROUTING_SYNC_BIT = 2

  // NB: BitSet operates on little endian, hence the reverse

  def isSet(features: BinaryData, bitIndex: Int): Boolean = BitSet.valueOf(features.reverse.toArray).get(bitIndex)

  /**
    * A feature set is supported if all even bits are supported.
    * We just ignore unknown odd bits.
    */
  def areSupported(features: BinaryData): Boolean = {
    val bitset = BitSet.valueOf(features.reverse.toArray)
    bitset.stream().noneMatch(new IntPredicate {
      override def test(value: Int) = value % 2 == 0 && value > INITIAL_ROUTING_SYNC_BIT
    })
  }

}
