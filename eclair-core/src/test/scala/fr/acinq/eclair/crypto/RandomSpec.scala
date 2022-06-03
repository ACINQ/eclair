/*
 * Copyright 2021 ACINQ SAS
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

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.crypto.RandomSpec.entropyScore
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.params.{KeyParameter, ParametersWithIV}
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.BitVector

class RandomSpec extends AnyFunSuiteLike {

  test("random long generation") {
    for (rng <- Seq(new WeakRandom(), new StrongRandom())) {
      val randomNumbers = (1 to 1000).map(_ => rng.nextLong())
      assert(randomNumbers.toSet.size == 1000)
      val entropy = randomNumbers.foldLeft(0.0) { case (current, next) => current + entropyScore(next) } / 1000
      assert(entropy >= 0.98)
    }
  }

  test("random bytes generation (small length)") {
    for (rng <- Seq(new WeakRandom(), new StrongRandom())) {
      val b1 = new Array[Byte](32)
      rng.nextBytes(b1)
      val b2 = new Array[Byte](32)
      rng.nextBytes(b2)
      val b3 = new Array[Byte](32)
      rng.nextBytes(b3)
      assert(!b1.sameElements(b2))
      assert(!b1.sameElements(b3))
      assert(!b2.sameElements(b3))
    }
  }

  test("random bytes generation (same length)") {
    for (rng <- Seq(new WeakRandom(), new StrongRandom())) {
      var randomBytes = new Array[Byte](0)
      for (_ <- 1 to 1000) {
        val buffer = new Array[Byte](64)
        rng.nextBytes(buffer)
        randomBytes = randomBytes ++ buffer
      }
      val entropy = entropyScore(randomBytes)
      assert(entropy >= 0.99)
    }
  }

  test("random bytes generation (variable length)") {
    for (rng <- Seq(new WeakRandom(), new StrongRandom())) {
      var randomBytes = new Array[Byte](0)
      for (i <- 10 to 500) {
        val b = new Array[Byte](i)
        rng.nextBytes(b)
        randomBytes = randomBytes ++ b
      }
      val entropy = entropyScore(randomBytes)
      assert(entropy >= 0.99)
    }
  }

  // This test shows that we can do in-place encryption with ChaCha20 (no need to allocate another array for the
  // ciphertext, we can directly write in the plaintext array).
  test("chacha20 in-place stream encryption") {
    val noExtraBuffer = new Array[Byte](512)
    val withExtraBuffer = new Array[Byte](512)

    {
      val stream = new ChaCha7539Engine()
      stream.init(true, new ParametersWithIV(new KeyParameter(ByteVector32.One.toArray), new Array[Byte](12)))
      stream.processBytes(noExtraBuffer, 0, noExtraBuffer.length, noExtraBuffer, 0)
    }
    {
      val stream = new ChaCha7539Engine()
      stream.init(true, new ParametersWithIV(new KeyParameter(ByteVector32.One.toArray), new Array[Byte](12)))
      val ciphertext = new Array[Byte](withExtraBuffer.length)
      stream.processBytes(withExtraBuffer, 0, withExtraBuffer.length, ciphertext, 0)
      ciphertext.copyToArray(withExtraBuffer)
    }

    assert(noExtraBuffer.sameElements(withExtraBuffer))
  }

}

object RandomSpec {

  // See https://en.wikipedia.org/wiki/Binary_entropy_function
  def entropyScore(bits: BitVector): Double = {
    val p = bits.toIndexedSeq.count(b => b).toDouble / bits.size
    (-p) * math.log(p) / math.log(2) - (1 - p) * math.log(1 - p) / math.log(2)
  }

  def entropyScore(l: Long): Double = {
    entropyScore(BitVector.fromLong(l))
  }

  def entropyScore(bytes: Array[Byte]): Double = {
    entropyScore(BitVector(bytes))
  }

}