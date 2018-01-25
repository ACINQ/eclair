package fr.acinq.eclair.router

import fr.acinq.eclair.wire.{BucketCounter, BucketCounters}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class BucketCountersSpec extends FunSuite {
  val heights = Seq(
    1,
    2,
    3,
    144 + 1,
    144 + 2,
    2 * 144 + 1,
    2 * 144 + 2, 2 * 144 + 2,
    2 * 144 + 3,
    4 * 144 + 1,
    5 * 144,
    5 * 144 + 1, 5 * 144 + 1,
    5 * 144 + 5,
    5 * 144 + 7
  )

  test("compute bucket counters (group by blocks)") {
    // we're less than one week ahead -> group by blocks
    val counter = Router.makeBucketCountersFromHeights(heights, 5 * 144 + 1)
    assert(counter == BucketCounters(
      List(
        BucketCounter(1, 1),
        BucketCounter(2, 1),
        BucketCounter(3, 1),
        BucketCounter(145, 1),
        BucketCounter(146, 1),
        BucketCounter(289, 1),
        BucketCounter(290, 2),
        BucketCounter(291, 1),
        BucketCounter(577, 1),
        BucketCounter(720, 1),
        BucketCounter(721, 2),
        BucketCounter(725, 1),
        BucketCounter(727, 1))
    ))

    // group by day
    val counter1 = Router.makeBucketCountersFromHeights(heights, 13 * 144 + 1)
    assert(counter1 == BucketCounters(List(BucketCounter(0,3), BucketCounter(144,2), BucketCounter(288,4), BucketCounter(576,1), BucketCounter(720,5))))

    // mix both: from day 5 on we count by block, before that we count by groups of 144 blocks
    val counter2 = Router.makeBucketCountersFromHeights(heights, 12 * 144 + 1)
    assert(counter2 == BucketCounters(List(BucketCounter(0,3), BucketCounter(144,2), BucketCounter(288,4), BucketCounter(576,1), BucketCounter(720,1), BucketCounter(721,2), BucketCounter(725,1), BucketCounter(727,1))))
  }

  test("compute bucket counters (group by days)") {
    val counter = Router.makeBucketCountersFromHeights(heights, 13 * 144 + 1)
    assert(counter == BucketCounters(List(BucketCounter(0,3), BucketCounter(144,2), BucketCounter(288,4), BucketCounter(576,1), BucketCounter(720,5))))
  }

  test("compute bucket counters (mix blocks and days)") {
    // mix both: from day 5 on we count by block, before that we count by groups of 144 blocks
    val counter = Router.makeBucketCountersFromHeights(heights, 12 * 144 + 1)
    assert(counter == BucketCounters(List(BucketCounter(0,3), BucketCounter(144,2), BucketCounter(288,4), BucketCounter(576,1), BucketCounter(720,1), BucketCounter(721,2), BucketCounter(725,1), BucketCounter(727,1))))
  }

  test("check consistency between our counters and their counters (same counters)") {
    // group by blocks
    val counter = Router.makeBucketCountersFromHeights(heights, 5 * 144 + 1)
    heights.foreach(h => assert(Router.checkBucketCounters(h, counter, counter)))

    assert(!Router.checkBucketCounters(heights.min - 1, counter, counter))
  }

  test("check consistency between our counters and their counters (different counters)") {
    // group by blocks
    val us = Router.makeBucketCountersFromHeights(heights, 5 * 144 + 1)
    val them = Router.makeBucketCountersFromHeights(heights :+ 145, 5 * 144 + 1)
    heights.filterNot(_ == 145).foreach(h => assert(Router.checkBucketCounters(h, us, them)))
    assert(!Router.checkBucketCounters(145, us, them))
  }
}
