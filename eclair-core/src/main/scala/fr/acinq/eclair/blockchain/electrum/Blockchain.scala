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

import fr.acinq.bitcoin.{BinaryData, Block, BlockHeader, decodeCompact, encodeCompact}
import grizzled.slf4j.Logging

import scala.annotation.tailrec
import scala.util.control.TailCalls
import scala.util.control.TailCalls.TailRec

/**
  *
  * @param chainhash   chain we're on
  * @param headers     map of hash -> headers
  * @param bestChain   the current best chain
  * @param orphans     headers for which we don't have a parent yet
  * @param checkpoints series of checkpoints
  */
case class Blockchain(chainhash: BinaryData, headers: Map[BinaryData, Blockchain.BlockIndex], bestChain: Vector[Blockchain.BlockIndex], orphans: Map[BinaryData, BlockHeader], checkpoints: Vector[CheckPoint]) {
  require(chainhash == Block.RegtestGenesisBlock.hash || chainhash == Block.TestnetGenesisBlock.hash || chainhash == Block.LivenetGenesisBlock.hash, s"invalid chain hash $chainhash")

  def first = bestChain.head

  def tip = bestChain.last

  /**
    *
    * @param height blockchain height
    * @return the header at this height in our best chain
    */
  def getHeader(height: Int) = bestChain(height - first.height).header

  /**
    *
    * @param height block height
    * @return the encoded difficulty that a block at this height should have
    */
  def getDifficulty(height: Int): Long = height match {
    case value if value < 2016 * (checkpoints.length + 1) =>
      // we're within our checkpoints
      val checkpoint = checkpoints(height / 2016 - 1)
      encodeCompact(checkpoint.target.bigInteger)
    case value if value % 2016 != 0 =>
      // we're not at a retargeting height, difficulty is the same as for the previous block
      getHeader(height - 1).bits
    case _ =>
      // difficulty retargeting
      val previous = getHeader(height - 1)
      val firstBlock = getHeader(height - 2016)
      BlockHeader.calculateNextWorkRequired(previous, firstBlock.time)
  }
}

object Blockchain extends Logging {

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
    * Build a blockchain from a series of checkpoints and a header. We check that the header is valid i.e. its hash and
    * difficulty match the last checkpoint
    *
    * @param chainhash        chain we're on
    * @param checkpoints      list of checkpoints
    * @param checkPointHeader first header after the checkpoint
    * @return a blockchain instance
    */
  def fromCheckpoints(chainhash: BinaryData, checkpoints: Vector[CheckPoint], checkPointHeader: BlockHeader): Blockchain = {
    require(checkPointHeader.hashPreviousBlock == checkpoints.last.hash, "header hash does not match last checkpoint")
    if (chainhash == Block.LivenetGenesisBlock.hash) {
      require(checkPointHeader.bits == encodeCompact(checkpoints.last.target.bigInteger), "header difficulty does not match last checkpoint")
    }
    val checkpointHeight = checkpoints.size * 2016 - 1
    val chainwork = checkpoints.dropRight(1).map(t => BigInt(2016) * Blockchain.chainWork(t.target)).sum
    val blockIndex = BlockIndex(checkPointHeader, checkpointHeight + 1, None, chainwork + chainWork(checkPointHeader))
    Blockchain(chainhash, Map(blockIndex.hash -> blockIndex), Vector(blockIndex), Map.empty[BinaryData, BlockHeader], checkpoints)
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
    *
    * @param blockchain
    * @param header
    * @return an updated blockchain
    */
  def addHeader(blockchain: Blockchain, header: BlockHeader): Blockchain = {
    addHeaderInternal(blockchain, header).result
  }
  
  private def addHeaderInternal(blockchain: Blockchain, header: BlockHeader): TailRec[Blockchain] = {
    if (blockchain.headers.contains(header.hash)) {
      logger.debug(s"already have block ${header.blockId}")
      TailCalls.done(blockchain)
    } else {
      // check that the header hash does match its difficulty field
      BlockHeader.checkProofOfWork(header)

      // check that the header difficulty is consistent with its supposed height
      def checkDifficulty(parent: BlockIndex) = blockchain.chainhash match {
        case Block.LivenetGenesisBlock.hash => require(header.bits == blockchain.getDifficulty(parent.height + 1))
        case _ => () // we don't check difficulty on regtest or testnet
      }

      blockchain.headers.get(header.hashPreviousBlock) match {
        case None if blockchain.headers.isEmpty && header.hashPreviousBlock == blockchain.checkpoints.last.hash =>
          val blockchain1 = Blockchain.fromCheckpoints(blockchain.chainhash, blockchain.checkpoints, header)
          TailCalls.tailcall(addOrphans(blockchain1.copy(orphans = blockchain.orphans)))
        case None =>
          // no parent found
          logger.debug(s"adding block ${header.blockId} to orphans")
          TailCalls.done(blockchain.copy(orphans = blockchain.orphans + (header.hash -> header)))
        case Some(parent) if parent == blockchain.tip =>
          // adding to the best chain
          checkDifficulty(parent)
          val index = BlockIndex(header, parent.height + 1, Some(parent), parent.chainwork + chainWork(header.bits))
          val blocks1 = blockchain.headers + (header.hash -> index)
          val bestchain1 = blockchain.bestChain :+ index
          logger.info(s"tip is now $index")
          TailCalls.tailcall(addOrphans(blockchain.copy(headers = blocks1, bestChain = bestchain1)))
        case Some(parent) if parent.height == blockchain.tip.height =>
          // we have a new best chain
          checkDifficulty(parent)
          val index = BlockIndex(header, parent.height + 1, Some(parent), parent.chainwork + chainWork(header.bits))
          logger.info(s"fork, tip is now $index")
          val blocks1 = blockchain.headers + (header.hash -> index)
          val bestchain1 = buildChain(index)
          TailCalls.tailcall(addOrphans(blockchain.copy(headers = blocks1, bestChain = bestchain1)))
        case Some(parent) =>
          // best chain remains the same
          checkDifficulty(parent)
          val index = BlockIndex(header, parent.height + 1, Some(parent), parent.chainwork + chainWork(header.bits))
          val blocks1 = blockchain.headers + (header.hash -> index)
          TailCalls.tailcall(addOrphans(blockchain.copy(headers = blocks1)))
      }
    }
  }

  def addHeaders(blockchain: Blockchain, headers: Seq[BlockHeader]): Blockchain = headers.foldLeft(blockchain)(addHeader)

  /**
    *
    * @param blockchain input blockchain
    * @return an updated blockchain where orphans have been added to the chain whenever possible
    */
  private def addOrphans(blockchain: Blockchain): TailRec[Blockchain] = {
    blockchain.orphans.values.find(header => blockchain.headers.contains(header.hashPreviousBlock)) match {
      case Some(header) =>
        val blockchain1 = blockchain.copy(orphans = blockchain.orphans - header.hash)
        val blockchain2 = addHeaderInternal(blockchain1, header)
        blockchain2
      case None => TailCalls.done(blockchain)
    }
  }

  def chainWork(target: BigInt): BigInt = BigInt(2).pow(256) / (target + BigInt(1))

  def chainWork(bits: Long): BigInt = {
    val (target, negative, overflow) = decodeCompact(bits)
    if (target == BigInteger.ZERO || negative || overflow) BigInt(0) else chainWork(target)
  }

  def chainWork(header: BlockHeader): BigInt = chainWork(header.bits)
}
