package fr.acinq.eclair.crypto

import fr.acinq.bitcoin.{BinaryData, Crypto}

object ShaChain {

  def flip(in: BinaryData, index: Int): BinaryData = in.data.updated(index / 8, (in.data(index / 8) ^ (1 << index % 8)).toByte)

  def canDerive(from: Long, to: Long) = (~from & to) == 0

  def derive(seed: BinaryData, from: Long, to: Long) : BinaryData = {
    require(canDerive(from, to))
    var hash = seed
    val branches = from ^ to
    for (i <- 63 to 0 by -1) {
			val foo = (branches >> i) & 1
      if ( foo != 0) {
        val hash1 = flip(hash, i)
        hash = Crypto.sha256(hash1)
      }
    }
    hash
  }

  def shaChainFromSeed(seed: BinaryData, index: Long) : BinaryData = derive(seed, 0xffffffffffffffffL, index)

  def init = ShaChain(0, Seq.empty[KnownHash])

  def addHash(chain: ShaChain, hash: BinaryData, index: Long) : ShaChain = {
    require(index == chain.maxIndex + 1 || (index == 0 && chain.knownHashes.isEmpty))

    def updateKnownHashes(knowHashes: Seq[KnownHash], acc: Seq[KnownHash] = Seq.empty[KnownHash]) : Seq[KnownHash] = knowHashes match {
      case Nil => acc :+ KnownHash(hash, index)
      case KnownHash(h, i) :: tail if canDerive(index, i) =>
        val expected: BinaryData = derive(hash, index, i)
        require(h == expected)
        acc :+ KnownHash(hash, index)
      case head :: tail => updateKnownHashes(tail, acc :+ head)
    }
    chain.copy(maxIndex = index, knownHashes = updateKnownHashes(chain.knownHashes))
  }

  def getHash(chain: ShaChain, index: Long) : Option[BinaryData] = {
    chain.knownHashes.find(k => canDerive(k.index, index)).map(k => derive(k.hash, k.index, index))
  }

  case class KnownHash(hash: BinaryData, index: Long)
}

case class ShaChain(maxIndex: Long, knownHashes: Seq[ShaChain.KnownHash])