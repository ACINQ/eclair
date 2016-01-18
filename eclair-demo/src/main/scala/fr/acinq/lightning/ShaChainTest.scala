package fr.acinq.lightning

import fr.acinq.bitcoin.{Crypto, BinaryData}

/**
  * Created by PM on 18/01/2016.
  */
object ShaChainTest extends App {

  val ourSeed = "a0b1c2d3e4f5".getBytes()

  val pre0 = ShaChain.shaChainFromSeed(ourSeed, 0); val hash0 = BinaryData(Crypto.sha256(pre0))
  val pre1 = ShaChain.shaChainFromSeed(ourSeed, 1); val hash1 = BinaryData(Crypto.sha256(pre1))
  val pre2 = ShaChain.shaChainFromSeed(ourSeed, 2); val hash2 = BinaryData(Crypto.sha256(pre2))

  println(s"pre0=$pre0   hash0=$hash0")
  println(s"pre1=$pre1   hash1=$hash1")
  println(s"pre2=$pre2   hash2=$hash2")


  val theirs = ShaChain.init
  val theirs0 = ShaChain.addHash(theirs, pre0, 0)
  val theirs1 = ShaChain.addHash(theirs0, pre1, 1)
  val theirs2 = ShaChain.addHash(theirs1, pre2, 2)

  val theirsPre2 = ShaChain.getHash(theirs2, 2)
  println(s"theirsPre2=$theirsPre2")
}
