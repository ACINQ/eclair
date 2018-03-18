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

import fr.acinq.bitcoin.{Hash, MerkleTree, Transaction, TxIn, TxOut}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

import scala.annotation.tailrec
import scala.util.Random

@RunWith(classOf[JUnitRunner])
class BlockchainSpec extends FunSuite {

  import Blockchain._

  val random = new Random()

  def block0(firstHeight: Long) = {
    val txs = Seq(Transaction(1, Seq.empty[TxIn], Seq.empty[TxOut], 0xffffffffL))
    val hashes = txs.map(_.hash)
    ElectrumClient.Header(firstHeight, 1, Hash.Zeroes, MerkleTree.computeRoot(hashes), timestamp = 0, bits = 0xffffffffL, nonce = 0)
  }

  def makeNextBlock(parent: ElectrumClient.Header) = parent.copy(block_height = parent.block_height + 1, prev_block_hash = parent.block_hash, nonce = random.nextLong())

  @tailrec
  final def makeBlocks(size: Int, acc: Seq[ElectrumClient.Header]): Seq[ElectrumClient.Header] = {
    if (acc.size == size) acc
    else {
      val block = makeNextBlock(acc.last)
      makeBlocks(size, acc :+ block)
    }
  }

  def makeBlocks(size: Int, firstHeight: Long = 0L): Seq[ElectrumClient.Header] = makeBlocks(size, Seq(block0(firstHeight)))


  def makeChain(blocks: Seq[ElectrumClient.Header], keepLastN: Option[Int]): Blockchain = blocks.tail.foldLeft(Blockchain.buildFromFirstHeader(blocks(0), keepLastN))(Blockchain.addBlockHeader)

  test("add blocks at the end") {
    val blocks = makeBlocks(4)
    val firstHeight = 100
    val chain0 = Blockchain.buildFromFirstHeader(blocks(0), None)
    assert(chain0.tip === BlockIndex(blocks(0), None, blockProof(blocks(0))))

    val chain1 = Blockchain.addBlockHeader(chain0, blocks(1))
    assert(chain1.tip === BlockIndex(blocks(1), Some(chain0.tip), chain0.tip.chainwork + blockProof(blocks(1))))
    assert(Blockchain.ancestor(chain1.tip, 1) == chain0.tip)

    val chain2 = Blockchain.addBlockHeader(chain1, blocks(2))
    assert(chain2.tip === BlockIndex(blocks(2), Some(chain1.tip), chain1.tip.chainwork + blockProof(blocks(2))))
    assert(Blockchain.ancestor(chain2.tip, 1) == chain1.tip)
    assert(Blockchain.ancestor(chain2.tip, 2) == chain0.tip)
  }

  test("add blocks at the beginning") {
    val firstHeight = 100
    val blocks = makeBlocks(5, firstHeight)
    val chain0 = Blockchain.buildFromFirstHeader(blocks(2), None)
    assert(chain0.tip === BlockIndex(blocks(2), None, blockProof(blocks(2))))

    val chain1 = Blockchain.addBlockHeaders(chain0, blocks(3) :: blocks(4) :: Nil)
    assert(chain1.tip.header === blocks(4))
    assert(chain1.tip.chainwork === chain0.tip.chainwork + blockProof(blocks(3)) + blockProof(blocks(4)))
    assert(Blockchain.ancestor(chain1.tip, 2) == chain0.tip)

    // now add a block at the beginning
    val chain2 = Blockchain.addBlockHeader(chain1, blocks(1))
    assert(chain2.tip === chain1.tip)
    assert(chain2.first.header === blocks(1))
    assert(chain2.bestChain(1).parent === Some(chain2.first))

    // and again
    val chain3 = Blockchain.addBlockHeader(chain2, blocks(0))
    assert(chain3.tip === chain1.tip)
    assert(chain3.first.header === blocks(0))
    assert(chain3.bestChain(1).parent === Some(chain3.first))
  }

  test("handle orphan blocks") {
    val blocks = makeBlocks(10, 100)
    // skip the first 2 blocks and add the next 4 blocks
    // chain contains blocks 2 to 5
    val chain = makeChain(blocks.drop(2).take(4), None)
    assert(chain.tip.header === blocks(5))
    assert(chain.orphans.isEmpty)

    // add block #8
    val chain1 = chain.addBlockHeader(blocks(8))
    assert(chain1.tip === chain.tip)
    assert(chain1.orphans.contains(blocks(8)))

    // add block #0
    val chain2 = chain1.addBlockHeader(blocks(0))
    assert(chain2.tip === chain.tip)
    assert(chain2.orphans.contains(blocks(8)) && chain2.orphans.contains(blocks(0)))

    // now add block #7
    val chain3 = chain2.addBlockHeader(blocks(7))
    assert(chain3.tip == chain2.tip)
    assert(chain3.orphans == Set(blocks(7), blocks(8), blocks(0)))

    // and block #1 which should also connect block #0
    val chain4 = chain3.addBlockHeader(blocks(1))
    assert(chain4.tip == chain3.tip)
    assert(chain4.first.header == blocks(0))
    assert(chain4.orphans == Set(blocks(7), blocks(8)))

    // and block #6 which should also connect block #7 and block #8
    val chain5 = chain4.addBlockHeader(blocks(6))
    assert(chain5.tip.header == blocks(8))
    assert(chain5.first.header == blocks(0))
    assert(chain5.orphans.isEmpty)
  }

  test("handle forks") {
    val blocks = makeBlocks(4, 10)
    val chain = makeChain(blocks, None)

    val block1 = makeNextBlock(blocks(2))
    val block2 = makeNextBlock(block1)
    val chain1 = Blockchain.addBlockHeader(chain, block1)
    assert(chain1.tip === chain.tip)

    val chain2 = Blockchain.addBlockHeader(chain1, block2)
    // blocks(0) <- blocks(1) <- blocks(2) <- blocks(3)
    //                                     <- block1 <- block2
    assert(chain2.tip.header === block2)

    assert(Blockchain.findForkInGlobalIndex(chain2, Seq(blocks(2).block_hash, blocks(3).block_hash)).header === blocks(2))
  }

  test("find ancestors") {
    val blocks = makeBlocks(4, 100)
    val chain = makeChain(blocks, None)
    assert(Blockchain.ancestor(chain.tip, 1).header === blocks(2))
    assert(Blockchain.ancestor(chain.tip, 2).header === blocks(1))
    assert(Blockchain.ancestor(chain.tip, 3).header === blocks(0))
  }

  test("prune old headers") {
    val blocks = makeBlocks(200, 100)
    val chain = makeChain(blocks, Some(100))
    assert(chain.bestChain.length == 100)
    assert(chain.tip.header == blocks.last)
  }
}
