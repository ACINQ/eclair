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

package fr.acinq.eclair.blockchain.electrum

import java.math.BigInteger

import fr.acinq.bitcoin.{BinaryData, Block, BlockHeader, decodeCompact}
import fr.acinq.eclair.blockchain.electrum.db.HeaderDb
import grizzled.slf4j.Logging

import scala.annotation.tailrec

case class Blockchain(chainHash: BinaryData,
                      checkpoints: Vector[CheckPoint],
                      headersMap: Map[BinaryData, Blockchain.BlockIndex],
                      bestchain: Vector[Blockchain.BlockIndex],
                      orphans: Map[BinaryData, BlockHeader] = Map()) {

  import Blockchain._

  require(chainHash == Block.LivenetGenesisBlock.hash || chainHash == Block.TestnetGenesisBlock.hash || chainHash == Block.RegtestGenesisBlock.hash, s"invalid chain hash $chainHash")

  def tip = bestchain.last

  def height = if (bestchain.isEmpty) 0 else bestchain.last.height

  /**
    * Build a chain of block indexes
    *
    * This is used in case of reorg to rebuilt the new best chain
    *
    * @param index last index of the chain
    * @param acc   accumulator
    * @return the chain that starts at the genesis block and ends at index
    */
  @tailrec
  private def buildChain(index: BlockIndex, acc: Vector[BlockIndex] = Vector.empty[BlockIndex]): Vector[BlockIndex] = {
    index.parent match {
      case None => index +: acc
      case Some(parent) => buildChain(parent, index +: acc)
    }
  }

  /**
    *
    * @param height block height
    * @return the encoded difficulty that a block at this height should have
    */
  def getDifficulty(height: Int): Option[Long] = height match {
    case value if value < RETARGETING_PERIOD * (checkpoints.length + 1) =>
      // we're within our checkpoints
      val checkpoint = checkpoints(height / RETARGETING_PERIOD - 1)
      Some(checkpoint.nextBits)
    case value if value % RETARGETING_PERIOD != 0 =>
      // we're not at a retargeting height, difficulty is the same as for the previous block
      getHeader(height - 1).map(_.bits)
    case _ =>
      // difficulty retargeting
      for {
        previous <- getHeader(height - 1)
        firstBlock <- getHeader(height - RETARGETING_PERIOD)
      } yield BlockHeader.calculateNextWorkRequired(previous, firstBlock.time)
  }

  def getHeader(height: Int): Option[BlockHeader] = if (!bestchain.isEmpty && height >= bestchain.head.height && height - bestchain.head.height < bestchain.size)
    Some(bestchain(height - bestchain.head.height).header)
  else None
}

object Blockchain extends Logging {

  val RETARGETING_PERIOD = 2016 // on bitcoin, the difficulty re-targeting period is 2016 blocks
  val MAX_REORG = 500 // we assume that there won't be a reorg of more than 500 blocks

  /**
    *
    * @param header    block header
    * @param height    block height
    * @param parent    parent block
    * @param chainwork cumulative chain work up to and including this block
    */
  case class BlockIndex(header: BlockHeader, height: Int, parent: Option[BlockIndex], chainwork: BigInt) {
    lazy val hash = header.hash

    lazy val blockId = header.blockId

    lazy val logwork = if (chainwork == 0) 0.0 else Math.log(chainwork.doubleValue()) / Math.log(2.0)

    override def toString = s"BlockIndex($blockId, $height, ${parent.map(_.blockId)}, $logwork)"
  }

  /**
    * Build an empty blockchain from a series of checkpoints
    *
    * @param chainhash   chain we're on
    * @param checkpoints list of checkpoints
    * @return a blockchain instance
    */
  def fromCheckpoints(chainhash: BinaryData, checkpoints: Vector[CheckPoint]): Blockchain = {
    Blockchain(chainhash, checkpoints, Map(), Vector())
  }

  /**
    * Used in tests
    */
  def fromGenesisBlock(chainhash: BinaryData, genesis: BlockHeader): Blockchain = {
    require(chainhash == Block.RegtestGenesisBlock.hash)
    // the height of the genesis block is 0
    val blockIndex = BlockIndex(genesis, 0, None, decodeCompact(genesis.bits)._1)
    Blockchain(chainhash, Vector(), Map(blockIndex.hash -> blockIndex), Vector(blockIndex))
  }

  /**
    * load an em
    *
    * @param chainHash
    * @param headerDb
    * @return
    */
  def load(chainHash: BinaryData, headerDb: HeaderDb): Blockchain = {
    val checkpoints = CheckPoint.load(chainHash)
    val checkpoints1 = headerDb.getTip match {
      case Some((height, header)) =>
        val newcheckpoints = for {h <- checkpoints.size * RETARGETING_PERIOD - 1 + RETARGETING_PERIOD to height - RETARGETING_PERIOD by RETARGETING_PERIOD} yield {
          val cpheader = headerDb.getHeader(h).get
          val nextDiff = headerDb.getHeader(h + 1).get.bits
          CheckPoint(cpheader.hash, nextDiff)
        }
        checkpoints ++ newcheckpoints
      case None => checkpoints
    }
    Blockchain.fromCheckpoints(chainHash, checkpoints1)
  }

  /**
    * Validate a chunk of 2016 headers
    *
    * Used during initial sync to batch validate
    *
    * @param height  height of the first header; must be a multiple of 2016
    * @param headers headers.
    * @throws Exception if this chunk is not valid and consistent with our checkpoints
    */
  def validateHeadersChunk(blockchain: Blockchain, height: Int, headers: Seq[BlockHeader]): Unit = {
    if (headers.isEmpty) return

    require(height % RETARGETING_PERIOD == 0, s"header chunk height $height not a multiple of 2016")
    require(BlockHeader.checkProofOfWork(headers.head))
    headers.tail.foldLeft(headers.head) {
      case (previous, current) =>
        require(BlockHeader.checkProofOfWork(current))
        require(current.hashPreviousBlock == previous.hash)
        // on mainnet all blocks with a re-targeting window have the same difficulty target
        // on testnet it doesn't hold, there can be a drop in difficulty if there are no blocks for 20 minutes
        blockchain.chainHash match {
          case Block.LivenetGenesisBlock | Block.RegtestGenesisBlock.hash => require(current.bits == previous.bits)
          case _ => ()
        }
        current
    }

    val cpindex = (height / RETARGETING_PERIOD) - 1
    if (cpindex < blockchain.checkpoints.length) {
      // check that the first header in the chunk matches our checkpoint
      val checkpoint = blockchain.checkpoints(cpindex)
      require(headers(0).hashPreviousBlock == checkpoint.hash)
      blockchain.chainHash match {
        case Block.LivenetGenesisBlock.hash => require(headers(0).bits == checkpoint.nextBits)
        case _ => ()
      }
    }

    // if we have a checkpoint after this chunk, check that it is also satisfied
    if (cpindex < blockchain.checkpoints.length - 1) {
      require(headers.length == RETARGETING_PERIOD)
      val nextCheckpoint = blockchain.checkpoints(cpindex + 1)
      require(headers.last.hash == nextCheckpoint.hash)
      blockchain.chainHash match {
        case Block.LivenetGenesisBlock.hash =>
          val diff = BlockHeader.calculateNextWorkRequired(headers.last, headers.head.time)
          require(diff == nextCheckpoint.nextBits)
        case _ => ()
      }
    }
  }

  def addHeadersChunk(blockchain: Blockchain, height: Int, headers: Seq[BlockHeader]): Blockchain = {
    if (headers.length > RETARGETING_PERIOD) {
      val blockchain1 = Blockchain.addHeadersChunk(blockchain, height, headers.take(RETARGETING_PERIOD))
      return Blockchain.addHeadersChunk(blockchain1, height + RETARGETING_PERIOD, headers.drop(RETARGETING_PERIOD))
    }
    if (headers.isEmpty) return blockchain
    validateHeadersChunk(blockchain, height, headers)

    height match {
      case _ if height == blockchain.checkpoints.length * RETARGETING_PERIOD =>
        // append after our last checkpoint

        // checkpoints are (block hash, * next * difficulty target), this is why:
        // - we duplicate the first checkpoints because all headers in the first chunks on mainnet had the same difficulty target
        // - we drop the last checkpoint
        val chainwork = (blockchain.checkpoints(0) +: blockchain.checkpoints.dropRight(1)).map(t => BigInt(RETARGETING_PERIOD) * Blockchain.chainWork(t.nextBits)).sum
        val blockIndex = BlockIndex(headers.head, height, None, chainwork + Blockchain.chainWork(headers.head))
        val bestchain1 = headers.tail.foldLeft(Vector(blockIndex)) {
          case (indexes, header) => indexes :+ BlockIndex(header, indexes.last.height + 1, Some(indexes.last), indexes.last.chainwork + Blockchain.chainWork(header))
        }
        val headersMap1 = blockchain.headersMap ++ bestchain1.map(bi => bi.hash -> bi)
        blockchain.copy(bestchain = bestchain1, headersMap = headersMap1)
      case _ if height < blockchain.checkpoints.length * RETARGETING_PERIOD =>
        blockchain
      case _ if height == blockchain.height + 1 =>
        // attach at our best chain
        require(headers.head.hashPreviousBlock == blockchain.bestchain.last.hash)
        val blockIndex = BlockIndex(headers.head, height, None, blockchain.bestchain.last.chainwork + Blockchain.chainWork(headers.head))
        val indexes = headers.tail.foldLeft(Vector(blockIndex)) {
          case (indexes, header) => indexes :+ BlockIndex(header, indexes.last.height + 1, Some(indexes.last), indexes.last.chainwork + Blockchain.chainWork(header))
        }
        val bestchain1 = blockchain.bestchain ++ indexes
        val headersMap1 = blockchain.headersMap ++ indexes.map(bi => bi.hash -> bi)
        blockchain.copy(bestchain = bestchain1, headersMap = headersMap1)
      // do nothing; headers have been validated
      case _ => throw new IllegalArgumentException(s"cannot add headers chunk to an empty blockchain: not within our checkpoint")
    }
  }

  def addHeader(blockchain: Blockchain, height: Int, header: BlockHeader): Blockchain = {
    require(BlockHeader.checkProofOfWork(header), s"invalid proof of work for $header")
    blockchain.headersMap.get(header.hashPreviousBlock) match {
      case Some(parent) if parent.height == height - 1 =>
        if (height % RETARGETING_PERIOD != 0 && (blockchain.chainHash == Block.LivenetGenesisBlock.hash || blockchain.chainHash == Block.RegtestGenesisBlock.hash)) {
          // check difficulty target, which should be the same as for the parent block
          // we only check this on mainnet, on testnet rules are much more lax
          require(header.bits == parent.header.bits, s"header invalid difficulty target for ${header}, it should be ${parent.header.bits}")
        }
        val blockIndex = BlockIndex(header, height, Some(parent), parent.chainwork + Blockchain.chainWork(header))
        val headersMap1 = blockchain.headersMap + (blockIndex.hash -> blockIndex)
        val bestChain1 = if (parent == blockchain.bestchain.last) {
          // simplest case: we add to our current best chain
          logger.info(s"new tip at $blockIndex")
          blockchain.bestchain :+ blockIndex
        } else if (blockIndex.chainwork > blockchain.bestchain.last.chainwork) {
          logger.info(s"new best chain at $blockIndex")
          // we have a new best chain
          buildChain(blockIndex)
        } else {
          logger.info(s"received header $blockIndex which is not on the best chain")
          blockchain.bestchain
        }
        blockchain.copy(headersMap = headersMap1, bestchain = bestChain1)
      case Some(parent) => throw new IllegalArgumentException(s"parent for $header at $height is not valid: $parent ")
      case None if height < blockchain.height - 1000 => blockchain
      case None => throw new IllegalArgumentException(s"cannot find parent for $header at $height")
    }
  }

  def addHeaders(blockchain: Blockchain, height: Int, headers: Seq[BlockHeader]): Blockchain = {
    if (headers.isEmpty) blockchain
    else if (height % RETARGETING_PERIOD == 0) addHeadersChunk(blockchain, height, headers)
    else {
      @tailrec
      def loop(bc: Blockchain, h: Int, hs: Seq[BlockHeader]): Blockchain = if (hs.isEmpty) bc else {
        loop(Blockchain.addHeader(bc, h, hs.head), h + 1, hs.tail)
      }

      loop(blockchain, height, headers)
    }
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

  def chainWork(target: BigInt): BigInt = BigInt(2).pow(256) / (target + BigInt(1))

  def chainWork(bits: Long): BigInt = {
    val (target, negative, overflow) = decodeCompact(bits)
    if (target == BigInteger.ZERO || negative || overflow) BigInt(0) else chainWork(target)
  }

  def chainWork(header: BlockHeader): BigInt = chainWork(header.bits)

  /**
    * Optimize blockchain
    *
    * @param blockchain
    * @param acc internal accumulator
    * @return a (blockchain, indexes) tuple where headers that are old enough have been removed and new checkpoints added,
    *         and indexes is the list of header indexes that have been optimized out and must be persisted
    */
  @tailrec
  def optimize(blockchain: Blockchain, acc: Vector[BlockIndex] = Vector.empty[BlockIndex]) : (Blockchain, Vector[BlockIndex]) = {
    if (blockchain.bestchain.size >= RETARGETING_PERIOD + MAX_REORG) {
      val saveme = blockchain.bestchain.take(RETARGETING_PERIOD)
      val headersMap1 = blockchain.headersMap -- saveme.map(_.hash)
      val bestchain1 = blockchain.bestchain.drop(RETARGETING_PERIOD)
      val checkpoints1 = blockchain.checkpoints :+ CheckPoint(saveme.last.hash, bestchain1.head.header.bits)
      optimize(blockchain.copy(headersMap = headersMap1, bestchain = bestchain1, checkpoints = checkpoints1), acc ++ saveme)
    } else {
      (blockchain, acc)
    }
  }

  /**
    * Computes the difficulty target at a given height.
    *
    * @param blockchain blockchain
    * @param height     height for which we want the difficulty target
    * @param headerDb   header database
    * @return the difficulty target for this height
    */
  def getDifficulty(blockchain: Blockchain, height: Int, headerDb: HeaderDb): Option[Long] = {
    blockchain.chainHash match {
      case Block.LivenetGenesisBlock.hash | Block.RegtestGenesisBlock.hash =>
        (height % RETARGETING_PERIOD) match {
          case 0 =>
            for {
              parent <- blockchain.getHeader(height - 1) orElse headerDb.getHeader(height - 1)
              previous <- blockchain.getHeader(height - 2016) orElse headerDb.getHeader(height - 2016)
              target = BlockHeader.calculateNextWorkRequired(parent, previous.time)
            } yield target
          case _ => blockchain.getHeader(height - 1) orElse headerDb.getHeader(height - 1) map (_.bits)
        }
      case _ => None // no difficulty check on testnet
    }
  }
}
