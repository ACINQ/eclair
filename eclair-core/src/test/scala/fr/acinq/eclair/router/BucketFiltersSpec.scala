package fr.acinq.eclair.router

import fr.acinq.bitcoin.{BinaryData, Block}
import fr.acinq.eclair.TestConstants
import fr.acinq.eclair._
import fr.acinq.eclair.channel.Helpers
import fr.acinq.eclair.wire.ChannelAnnouncement
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class BucketFiltersSpec extends FunSuite {
  import BucketFiltersSpec._

  val announcements = Seq(
    makeAnnoucement(1, 0, 0),
    makeAnnoucement(2, 0, 0),
    makeAnnoucement(3, 0, 0),
    makeAnnoucement(144, 0, 0),
    makeAnnoucement(144 + 1, 1, 0),
    makeAnnoucement(2 * 144, 0, 0),
    makeAnnoucement(2 * 144 + 1, 0, 0),
    makeAnnoucement(2 * 144 + 2, 0, 0),
    makeAnnoucement(4 * 144, 0, 0),
    makeAnnoucement(5  *144, 0, 0),
    makeAnnoucement(5  *144 + 1, 0, 0),
    makeAnnoucement(5  *144 + 2, 0, 0),
    makeAnnoucement(5  *144 + 2, 1, 0)
  )
  val heights = announcements.map(ca => fromShortId(ca.shortChannelId)._1)

  test("compute bucket hashes") {
    val hashes = Router.makeBucketFilters(announcements, 5  *144 + 1)
    println(hashes)
  }

  test("check consistency between our hashes and their hashes (same hashes)") {
    val hashes = Router.makeBucketFilters(announcements, 5  *144 + 1)
    heights.map(h => assert(Router.checkBucketFilters(h, hashes, hashes)))
    assert(!Router.checkBucketFilters(heights.min - 1, hashes, hashes))
  }

  test("check consistency between our counters and their counters (different counters)") {
    // group by blocks
    val us = Router.makeBucketFilters(announcements, 5  *144 + 1)
    val them = Router.makeBucketFilters(announcements :+ makeAnnoucement(144 + 1, 2, 0), 5  *144 + 1)
    heights.filterNot(_ == 145).foreach(h => assert(Router.checkBucketFilters(h, us, them)))
    assert(!Router.checkBucketFilters(145, us, them))
  }

  test("check consistency between our counters and their counters (different counters #2)") {
    // group by blocks
    val us = Router.makeBucketFilters(announcements, 5  *144 + 1)
    val them = Router.makeBucketFilters(announcements :+ makeAnnoucement(200, 2, 0), 5  *144 + 1)
    heights.filterNot(_ == 145).foreach(h => assert(Router.checkBucketFilters(h, us, them)))
    assert(!Router.checkBucketFilters(145, us, them))
  }
}

object BucketFiltersSpec {
  def makeAnnoucement(height: Int, txIndex: Int, outputIndex: Int): ChannelAnnouncement = {
    val (localNodeSig, localBitcoinSig) = Announcements.signChannelAnnouncement(Block.RegtestGenesisBlock.hash, toShortId(height, txIndex, outputIndex),
      TestConstants.Alice.extendedPrivateKey.privateKey, TestConstants.Bob.id,
      TestConstants.Alice.extendedPrivateKey.privateKey, TestConstants.Bob.id,
      BinaryData("03"))
    val (remoteNodeSig, remoteBitcoinSig) = Announcements.signChannelAnnouncement(Block.RegtestGenesisBlock.hash, toShortId(height, txIndex, outputIndex),
      TestConstants.Bob.extendedPrivateKey.privateKey, TestConstants.Alice.id,
      TestConstants.Bob.extendedPrivateKey.privateKey, TestConstants.Alice.id,
      BinaryData("03"))

    Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, toShortId(height, txIndex, outputIndex),
      TestConstants.Alice.id, TestConstants.Bob.id,
      TestConstants.Alice.id, TestConstants.Bob.id,
      localNodeSig, remoteNodeSig,
      localBitcoinSig, remoteBitcoinSig)
  }
}
