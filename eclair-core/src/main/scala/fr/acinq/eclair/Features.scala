package fr.acinq.eclair


import fr.acinq.bitcoin.BinaryData
import scodec.bits.BitVector

import scala.collection.immutable.BitSet


/**
  * Created by PM on 13/02/2017.
  */
object Features {

  val CHANNELS_PUBLIC_BIT = 0
  val INITIAL_ROUTING_SYNC_BIT = 2

  def isSet(features: BinaryData, bitIndex: Int): Boolean = {
    val bitset = BitVector(features.data)
    // NB: for some reason BitVector bits are reversed
    bitIndex < bitset.size && bitset.get(bitset.size - 1 - bitIndex)
  }

  /**
    * A feature set is supported if all even bits are supported.
    * We just ignore unknown odd bits.
    */
  def areSupported(features: BinaryData): Boolean = {
    val bitset = BitVector(features.data)
    // we look for an unknown even bit set (NB: for some reason BitVector bits are reversed)
    val unsupported = bitset.toIndexedSeq.reverse.zipWithIndex.exists { case (b, idx) => b && idx % 2 == 0 && idx > INITIAL_ROUTING_SYNC_BIT}
    !unsupported
  }

}
