/*
 * Copyright 2019 ACINQ SAS
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

package fr.acinq.eclair.router

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate}
import fr.acinq.eclair.{CltvExpiryDelta, LongToBtcAmount, MilliSatoshi, ShortChannelId, randomBytes32, randomBytes64, randomKey}
import org.scalatest.FunSuite

import scala.util.Random

/**
 * Created by t-bast on 30/08/2019.
 */

class NetworkStatsSpec extends FunSuite {

  import NetworkStatsSpec._

  test("network data missing") {
    assert(NetworkStats.computeStats(Nil) === None)
    assert(NetworkStats.computeStats(Seq(
      PublicChannel(fakeChannelAnnouncement(randomKey.publicKey, randomKey.publicKey), randomBytes32, 10 sat, None, None),
      PublicChannel(fakeChannelAnnouncement(randomKey.publicKey, randomKey.publicKey), randomBytes32, 15 sat, None, None)
    )) === None)
  }

  test("small network") {
    val nodes = Seq.fill(6)(randomKey.publicKey)
    val channels = Seq(
      PublicChannel(fakeChannelAnnouncement(nodes(0), nodes(1)), randomBytes32, 10 sat, Some(fakeChannelUpdate1(CltvExpiryDelta(10), 10 msat, 10)), Some(fakeChannelUpdate2(CltvExpiryDelta(15), 15 msat, 15))),
      PublicChannel(fakeChannelAnnouncement(nodes(1), nodes(2)), randomBytes32, 20 sat, None, Some(fakeChannelUpdate2(CltvExpiryDelta(25), 25 msat, 25))),
      PublicChannel(fakeChannelAnnouncement(nodes(2), nodes(3)), randomBytes32, 30 sat, Some(fakeChannelUpdate1(CltvExpiryDelta(30), 30 msat, 30)), Some(fakeChannelUpdate2(CltvExpiryDelta(35), 35 msat, 35))),
      PublicChannel(fakeChannelAnnouncement(nodes(3), nodes(4)), randomBytes32, 40 sat, Some(fakeChannelUpdate1(CltvExpiryDelta(40), 40 msat, 40)), None),
      PublicChannel(fakeChannelAnnouncement(nodes(4), nodes(5)), randomBytes32, 50 sat, Some(fakeChannelUpdate1(CltvExpiryDelta(50), 50 msat, 50)), Some(fakeChannelUpdate2(CltvExpiryDelta(55), 55 msat, 55)))
    )
    val Some(stats) = NetworkStats.computeStats(channels)
    assert(stats.channels === 5)
    assert(stats.nodes === 6)
    assert(stats.capacity === Stats(30 sat, 12 sat, 14 sat, 20 sat, 40 sat, 46 sat, 48 sat))
    assert(stats.cltvExpiryDelta === Stats(CltvExpiryDelta(32), CltvExpiryDelta(11), CltvExpiryDelta(13), CltvExpiryDelta(22), CltvExpiryDelta(42), CltvExpiryDelta(51), CltvExpiryDelta(53)))
    assert(stats.feeBase === Stats(32 msat, 11 msat, 13 msat, 22 msat, 42 msat, 51 msat, 53 msat))
    assert(stats.feeProportional === Stats(32, 11, 13, 22, 42, 51, 53))
  }

  test("intermediate network") {
    val rand = new Random()
    val nodes = Seq.fill(100)(randomKey.publicKey)
    val channels = Seq.fill(500)(PublicChannel(
      fakeChannelAnnouncement(nodes(rand.nextInt(nodes.size)), nodes(rand.nextInt(nodes.size))),
      randomBytes32,
      Satoshi(1000 + rand.nextInt(10000)),
      Some(fakeChannelUpdate1(CltvExpiryDelta(12 + rand.nextInt(144)), MilliSatoshi(21000 + rand.nextInt(79000)), rand.nextInt(1000))),
      Some(fakeChannelUpdate2(CltvExpiryDelta(12 + rand.nextInt(144)), MilliSatoshi(21000 + rand.nextInt(79000)), rand.nextInt(1000)))
    ))
    val Some(stats) = NetworkStats.computeStats(channels)
    assert(stats.channels === 500)
    assert(stats.nodes <= 100)
    assert(1000.sat <= stats.capacity.median && stats.capacity.median <= 11000.sat)
    assert(stats.feeBase.percentile10 >= 21000.msat)
    assert(stats.feeProportional.median <= 1000)
  }

}

object NetworkStatsSpec {

  def fakeChannelAnnouncement(local: PublicKey, remote: PublicKey): ChannelAnnouncement = {
    Announcements.makeChannelAnnouncement(randomBytes32, ShortChannelId(42), local, remote, randomKey.publicKey, randomKey.publicKey, randomBytes64, randomBytes64, randomBytes64, randomBytes64)
  }

  def fakeChannelUpdate1(cltv: CltvExpiryDelta, feeBase: MilliSatoshi, feeProportional: Long): ChannelUpdate = {
    ChannelUpdate(randomBytes64, randomBytes32, ShortChannelId(42), 0, 0, 0, cltv, 1 msat, feeBase, feeProportional, None)
  }

  def fakeChannelUpdate2(cltv: CltvExpiryDelta, feeBase: MilliSatoshi, feeProportional: Long): ChannelUpdate = {
    ChannelUpdate(randomBytes64, randomBytes32, ShortChannelId(42), 0, 0, 1, cltv, 1 msat, feeBase, feeProportional, None)
  }

}