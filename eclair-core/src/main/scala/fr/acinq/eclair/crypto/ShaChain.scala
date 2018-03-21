/*
 * Copyright 2018 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.crypto

import fr.acinq.bitcoin._
import fr.acinq.eclair.wire.LightningMessageCodecs
import scodec.Codec

import scala.annotation.tailrec

/**
  * see https://github.com/rustyrussell/lightning-rfc/blob/master/early-drafts/shachain.txt
  */
object ShaChain {

  case class Node(value: BinaryData, height: Int, parent: Option[Node]) {
    require(value.length == 32)
  }

  def flip(in: BinaryData, index: Int): BinaryData = in.data.updated(index / 8, (in.data(index / 8) ^ (1 << index % 8)).toByte)

  /**
    *
    * @param index 64-bit integer
    * @return a binary representation of index as a sequence of 64 booleans. Each bool represents a move down the tree
    */
  def moves(index: Long): Vector[Boolean] = (for (i <- 63 to 0 by -1) yield (index & (1L << i)) != 0).toVector

  /**
    *
    * @param node      initial node
    * @param direction false means left, true means right
    * @return the child of our node in the specified direction
    */
  def derive(node: Node, direction: Boolean) = direction match {
    case false => Node(node.value, node.height + 1, Some(node))
    case true => Node(Crypto.sha256(flip(node.value, 63 - node.height)), node.height + 1, Some(node))
  }

  def derive(node: Node, directions: Seq[Boolean]): Node = directions.foldLeft(node)(derive)

  def derive(node: Node, directions: Long): Node = derive(node, moves(directions))

  def shaChainFromSeed(hash: BinaryData, index: Long) = derive(Node(hash, 0, None), index).value

  type Index = Vector[Boolean]

  val empty = ShaChain(Map.empty[Index, BinaryData])

  val init = empty

  @tailrec
  def addHash(receiver: ShaChain, hash: BinaryData, index: Index): ShaChain = {
    index.last match {
      case true => ShaChain(receiver.knownHashes + (index -> hash))
      case false =>
        val parentIndex = index.dropRight(1)
        // hashes are supposed to be received in reverse order so we already have parent :+ true
        // which we should be able to recompute (it's a left node so its hash is the same as its parent's hash)
        require(getHash(receiver, parentIndex :+ true) == Some(derive(Node(hash, parentIndex.length, None), true).value), "invalid hash")
        val nodes1 = receiver.knownHashes - (parentIndex :+ false) - (parentIndex :+ true)
        addHash(receiver.copy(knownHashes = nodes1), hash, parentIndex)
    }
  }

  def addHash(receiver: ShaChain, hash: BinaryData, index: Long): ShaChain = {
    receiver.lastIndex.map(value => require(index == value - 1L))
    addHash(receiver, hash, moves(index)).copy(lastIndex = Some(index))
  }

  def getHash(receiver: ShaChain, index: Index): Option[BinaryData] = {
    receiver.knownHashes.keys.find(key => index.startsWith(key)).map(key => {
      val root = Node(receiver.knownHashes(key), key.length, None)
      derive(root, index.drop(key.length)).value
    })
  }

  def getHash(receiver: ShaChain, index: Long): Option[BinaryData] = {
    receiver.lastIndex match {
      case None => None
      case Some(value) if value > index => None
      case _ => getHash(receiver, moves(index))
    }
  }

  def iterator(chain: ShaChain): Iterator[BinaryData] = chain.lastIndex match {
    case None => Iterator.empty
    case Some(index) => new Iterator[BinaryData] {
      var pos = index

      override def hasNext: Boolean = pos >= index && pos <= 0xffffffffffffffffL

      override def next(): BinaryData = {
        val value = chain.getHash(pos).get
        pos = pos + 1
        value
      }
    }
  }


  val shaChainCodec: Codec[ShaChain] = {
    import scodec.Codec
    import scodec.bits.BitVector
    import scodec.codecs._

    // codec for a single map entry (i.e. Vector[Boolean] -> BinaryData
    val entryCodec = vectorOfN(uint16, bool) ~ LightningMessageCodecs.varsizebinarydata

    // codec for a Map[Vector[Boolean], BinaryData]: write all k ->v pairs using the codec defined above
    val mapCodec: Codec[Map[Vector[Boolean], BinaryData]] = Codec[Map[Vector[Boolean], BinaryData]](
      (m: Map[Vector[Boolean], BinaryData]) => vectorOfN(uint16, entryCodec).encode(m.toVector),
      (b: BitVector) => vectorOfN(uint16, entryCodec).decode(b).map(_.map(_.toMap))
    )

    // our shachain codec
    (("knownHashes" | mapCodec) :: ("lastIndex" | optional(bool, int64))).as[ShaChain]
  }

}

/**
  * Structure used to intelligently store unguessable hashes.
  *
  * @param knownHashes know hashes
  * @param lastIndex   index of the last known hash. Hashes are supposed to be added in reverse order i.e.
  *                    from 0xFFFFFFFFFFFFFFFF down to 0
  */
case class ShaChain(knownHashes: Map[Vector[Boolean], BinaryData], lastIndex: Option[Long] = None) {
  def addHash(hash: BinaryData, index: Long): ShaChain = ShaChain.addHash(this, hash, index)

  def getHash(index: Long) = ShaChain.getHash(this, index)

  def iterator = ShaChain.iterator(this)

  override def toString = s"ShaChain(lastIndex = $lastIndex)"
}