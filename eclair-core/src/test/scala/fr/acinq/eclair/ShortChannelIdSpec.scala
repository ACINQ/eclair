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

package fr.acinq.eclair

import org.scalatest.funsuite.AnyFunSuite

import scala.util.Success

class ShortChannelIdSpec extends AnyFunSuite {

  test("handle real short channel ids from 0 to 0xffffffffffff") {
    val expected = Map(
      TxCoordinates(BlockHeight(0), 0, 0) -> RealShortChannelId(0),
      TxCoordinates(BlockHeight(42000), 27, 3) -> RealShortChannelId(0x0000a41000001b0003L),
      TxCoordinates(BlockHeight(1258612), 63, 0) -> RealShortChannelId(0x13347400003f0000L),
      TxCoordinates(BlockHeight(0xffffff), 0x000000, 0xffff) -> RealShortChannelId(0xffffff000000ffffL),
      TxCoordinates(BlockHeight(0x000000), 0xffffff, 0xffff) -> RealShortChannelId(0x000000ffffffffffL),
      TxCoordinates(BlockHeight(0xffffff), 0xffffff, 0x0000) -> RealShortChannelId(0xffffffffffff0000L),
      TxCoordinates(BlockHeight(0xffffff), 0xffffff, 0xffff) -> RealShortChannelId(0xffffffffffffffffL)
    )
    for ((coord, shortChannelId) <- expected) {
      assert(shortChannelId == RealShortChannelId(coord.blockHeight, coord.txIndex, coord.outputIndex))
      assert(coord == ShortChannelId.coordinates(shortChannelId))
    }
  }

  test("human readable format as per spec") {
    assert(RealShortChannelId(0x0000a41000001b0003L).toString == "42000x27x3")
  }

  test("parse a short channel id") {
    assert(ShortChannelId.fromCoordinates("42000x27x3").map(_.toLong) == Success(0x0000a41000001b0003L))
  }

  test("fail parsing a short channel id if not in the required form") {
    assert(ShortChannelId.fromCoordinates("42000x27x3.1").isFailure)
    assert(ShortChannelId.fromCoordinates("4200aa0x27x3").isFailure)
    assert(ShortChannelId.fromCoordinates("4200027x3").isFailure)
    assert(ShortChannelId.fromCoordinates("42000x27ax3").isFailure)
    assert(ShortChannelId.fromCoordinates("42000x27x").isFailure)
    assert(ShortChannelId.fromCoordinates("42000x27").isFailure)
    assert(ShortChannelId.fromCoordinates("42000x").isFailure)
  }

  test("compare different types of short channel ids") {
    val id = 123456
    val alias = Alias(id)
    val realScid = RealShortChannelId(id)
    val scid = ShortChannelId(id)
    assert(alias == realScid)
    assert(realScid == scid)
    val m = Map(alias -> "alias", realScid -> "real", scid -> "unknown")
    // all scids are in the same key space
    assert(m.size == 1)
    // Values outside of the range [0;0xffffffffffff] can be used for aliases.
    Seq(-561L, 0xffffffffffffffffL, 0x2affffffffffffffL).foreach(id => assert(Alias(id) == UnspecifiedShortChannelId(id)))
  }

  test("basic check on random alias generation") {
    (0 until 1000).foldLeft(Set.empty[Alias]) {
      case (aliases, _) =>
        val alias = ShortChannelId.generateLocalAlias()
        assert(alias.toLong >= 0 && alias.toLong <= 384_829_069_721_665_536L && !aliases.contains(alias))
        aliases + alias
    }
  }

}
