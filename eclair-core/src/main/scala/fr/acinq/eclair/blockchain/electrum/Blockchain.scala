/*
 * Copyright 2018 ACINQ SAS
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package fr.acinq.eclair.blockchain.electrum

import java.math.BigInteger

import fr.acinq.bitcoin.{BinaryData, decodeCompact}
import grizzled.slf4j.Logging

import scala.annotation.tailrec


/**
  * Simple structure for storing a chain of block headers
  *
  * @param blocks    block headers, indexed by bloch hash
  * @param bestChain best chain
  * @param orphans   orphan blocks, i.e block for which we don't have the parent. they
  *                  are scanned every time a new block is added to the chain
  * @param keepLastN option to specify that we only keep the last N headers
  */
case class Blockchain(blocks: Map[BinaryData, Blockchain.BlockIndex], bestChain: Vector[Blockchain.BlockIndex], orphans: Set[ElectrumClient.Header], keepLastN: Option[Int]) {
  val first = bestChain.head

  val tip = bestChain.last

  val currentHeight = tip.height

  def addBlockHeader(header: ElectrumClient.Header) = Blockchain.addBlockHeader(this, header)

  def addBlockHeaders(headers: Seq[ElectrumClient.Header]): Blockchain = Blockchain.addBlockHeaders(this, headers)

  def indexAt(height: Long): Option[Blockchain.BlockIndex] = if (height < first.height || height > tip.height) None else Some(bestChain((height - first.height).toInt))

  def prune: Blockchain = keepLastN match {
    case None => this
    case Some(value) if bestChain.length <= value => this
    case Some(value) =>
      val (drop, keep) = bestChain.splitAt(bestChain.length - value)
      val blocks1 = blocks -- drop.map(_.hash)
      this.copy(blocks = blocks1, bestChain = keep)
  }
}


object Blockchain extends Logging {

  case class BlockIndex(header: ElectrumClient.Header, parent: Option[BlockIndex], chainwork: Double) {
    lazy val hash = header.block_hash

    lazy val blockId: BinaryData = header.block_id

    lazy val logwork = if (chainwork == 0.0) 0.0 else Math.log(chainwork) / Math.log(2.0)

    lazy val height = header.block_height

    override def toString = s"BlockIndex($blockId, ${header.block_height}, ${parent.map(_.blockId)}, $logwork)"
  }

  def blockProof(bits: Long): Double = {
    val (target, negative, overflow) = decodeCompact(bits)
    if (target == BigInteger.ZERO || negative || overflow) 0.0 else {
      val work = BigInteger.valueOf(2).pow(256).divide(target.add(BigInteger.ONE))
      work.doubleValue()
    }
  }

  def blockProof(header: ElectrumClient.Header): Double = blockProof(header.bits)

  /**
    * Proof of work: hash(header) <= target difficulty
    *
    * @param header block header
    * @return true if the input block header validates its expected proof of work
    */
  def checkProofOfWork(header: ElectrumClient.Header): Boolean = {
    val (target, _, _) = decodeCompact(header.bits)
    val hash = new BigInteger(1, header.block_hash.toArray)
    hash.compareTo(target) <= 0
  }

  def buildFromFirstHeader(firstHeader: ElectrumClient.Header, keepLastN: Option[Int]): Blockchain = {
    require(checkProofOfWork(firstHeader), "invalid proof of work")
    val blockIndex = BlockIndex(firstHeader, None, blockProof(firstHeader))
    Blockchain(Map(blockIndex.hash -> blockIndex), Vector(blockIndex), Set.empty[ElectrumClient.Header], keepLastN)
  }

  /**
    * build a chain of block indexes
    *
    * @param index last index of the chain
    * @param acc   accumulator
    * @return the chain that starts at the genesis block and ends at index
    */
  @tailrec
  def buildChain(index: BlockIndex, acc: Vector[BlockIndex] = Vector.empty[BlockIndex]): Vector[BlockIndex] = {
    index.parent match {
      case None => index +: acc
      case Some(parent) => buildChain(parent, index +: acc)
    }
  }

  /**
    * Add a new header to a blockchain
    * @param blockchain blockchain
    * @param header header
    * @return an updated blockchain
    */
  def addBlockHeader(blockchain: Blockchain, header: ElectrumClient.Header): Blockchain = {
    if (blockchain.blocks.contains(header.block_hash)) {
      logger.debug(s"already have block ${header.block_hash}")
      blockchain
    }
    else {
      require(checkProofOfWork(header), "invalid proof of work")
      // is this the parent of one of our blocks ?
      val blockchain1 = blockchain.blocks.get(header.prev_block_hash) match {
         case None if header.block_hash == blockchain.first.header.prev_block_hash =>
          // new header is the parent of our first block
          val index = BlockIndex(header, None, blockchain.first.chainwork - blockProof(header.bits))
          // uodate our first block
          val first1 = blockchain.first.copy(parent = Some(index))
          val blocks1 = blockchain.blocks + (header.block_hash -> index) + (first1.hash -> first1)
          val bestchain1 = index +: blockchain.bestChain.updated(0, first1)
          addOrphans(blockchain.copy(blocks = blocks1, bestChain = bestchain1))
        case None =>
          // no parent found
          logger.debug(s"adding block ${header.block_hash} to orphans")
          blockchain.copy(orphans = blockchain.orphans + header)
        case Some(parent) if parent == blockchain.tip =>
          // adding to the best chain
          val index = BlockIndex(header, Some(parent), parent.chainwork + blockProof(header.bits))
          val blocks1 = blockchain.blocks + (header.block_hash -> index)
          val bestchain1 = blockchain.bestChain :+ index
          logger.info(s"tip is now $index")
          addOrphans(blockchain.copy(blocks = blocks1, bestChain = bestchain1))
        case Some(parent) if parent.height == blockchain.tip.height =>
          // we have a new best chain
          val index = BlockIndex(header, Some(parent), parent.chainwork + blockProof(header.bits))
          logger.info(s"fork, tip is now $index")
          val blocks1 = blockchain.blocks + (header.block_hash -> index)
          val bestchain1 = buildChain(index)
          addOrphans(blockchain.copy(blocks = blocks1, bestChain = bestchain1))
        case Some(parent) =>
          // best chain remains the same
          val index = BlockIndex(header, Some(parent), parent.chainwork + blockProof(header.bits))
          val blocks1 = blockchain.blocks + (header.block_hash -> index)
          addOrphans(blockchain.copy(blocks = blocks1))
      }
      blockchain1.prune
    }
  }

  def addBlockHeaders(blockchain: Blockchain, blocks: Seq[ElectrumClient.Header]): Blockchain = blocks.foldLeft(blockchain)(addBlockHeader)

  /**
    *
    * @param blockchain input blockchain
    * @return an updated blockchain where orphans have been added to the chain whenever possible
    */
  def addOrphans(blockchain: Blockchain): Blockchain = {
    blockchain.orphans.find(header => blockchain.blocks.contains(header.prev_block_hash) || header.block_hash == blockchain.first.header.prev_block_hash) match {
      case Some(header) =>
        val blockchain1 = blockchain.copy(orphans = blockchain.orphans - header)
        val blockchain2 = addBlockHeader(blockchain1, header)
        addOrphans(blockchain2)
      case None => blockchain
    }
  }

  def contains(chain: Vector[BlockIndex], blockIndex: BlockIndex) = blockIndex.height >= chain.head.height && blockIndex.height <= chain.last.height && chain((blockIndex.height - chain.head.height).toInt) == blockIndex

  def next(chain: Vector[BlockIndex], blockIndex: BlockIndex): Option[BlockIndex] =
    if (contains(chain, blockIndex) && blockIndex.height < chain.length - 1)
      Some(chain((blockIndex.height + 1).toInt))
    else None

  def height(chain: Vector[BlockIndex]) = chain.length - 1

  def subchain(chain: Vector[BlockIndex], start: BlockIndex, count: Int, stopHash: BinaryData): Vector[BlockIndex] = subchain(chain, start, count, stopHash, Vector(start))

  @tailrec
  def subchain(chain: Vector[BlockIndex], start: BlockIndex, count: Int, stopHash: BinaryData, acc: Vector[BlockIndex]): Vector[BlockIndex] = {
    if (count == 0 || start.hash == stopHash)
      acc
    else next(chain, start) match {
      case None => acc
      case Some(n) => subchain(chain, n, count - 1, stopHash, acc :+ n)
    }
  }

  @tailrec
  def ancestor(index: BlockIndex, height: Int): BlockIndex = {
    if (height == 0) index else index.parent match {
      case Some(parent) => ancestor(parent, height - 1)
      case None => throw new RuntimeException(s"cannot find ancestor($index, $height)")
    }
  }

  def findForkInGlobalIndex(blockchain: Blockchain, locator: Seq[BinaryData]): BlockIndex = {
    locator.find(hash => blockchain.blocks.contains(hash) && Blockchain.contains(blockchain.bestChain, blockchain.blocks(hash))) match {
      case Some(hash) => blockchain.blocks(hash)
      case None => blockchain.first
    }
  }
}

