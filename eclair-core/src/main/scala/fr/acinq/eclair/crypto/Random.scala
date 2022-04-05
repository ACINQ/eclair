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

import fr.acinq.bitcoin.scalacompat.Protocol
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.params.{KeyParameter, ParametersWithIV}

import java.lang.management.ManagementFactory
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * Created by t-bast on 19/04/2021.
 */

sealed trait EntropyCollector {
  /** External components may inject additional entropy to be added to the entropy pool. */
  def addEntropy(entropy: Array[Byte]): Unit
}

sealed trait RandomGenerator {
  // @formatter:off
  def nextBytes(bytes: Array[Byte]): Unit
  def nextLong(): Long
  // @formatter:on
}

sealed trait RandomGeneratorWithInit extends RandomGenerator {
  def init(): Unit
}

/**
 * A weak pseudo-random number generator that regularly samples a few entropy sources to build a hash chain.
 * This should never be used alone but can be xor-ed with the OS random number generator in case it completely breaks.
 */
private class WeakRandom() extends RandomGenerator {

  private val stream = new ChaCha7539Engine()
  private val seed = new Array[Byte](32)
  private var lastByte: Byte = 0
  private var opsSinceLastSample: Int = 0

  private val memoryMXBean = ManagementFactory.getMemoryMXBean
  private val runtimeMXBean = ManagementFactory.getRuntimeMXBean
  private val threadMXBean = ManagementFactory.getThreadMXBean

  // sample some initial entropy
  sampleEntropy()

  private def feedDigest(sha: SHA256Digest, i: Int): Unit = {
    sha.update(i.toByte)
    sha.update((i >> 8).toByte)
    sha.update((i >> 16).toByte)
    sha.update((i >> 24).toByte)
  }

  private def feedDigest(sha: SHA256Digest, l: Long): Unit = {
    sha.update(l.toByte)
    sha.update((l >> 8).toByte)
    sha.update((l >> 16).toByte)
    sha.update((l >> 24).toByte)
    sha.update((l >> 32).toByte)
    sha.update((l >> 40).toByte)
  }

  /** The entropy pool is regularly enriched with newly sampled entropy. */
  private def sampleEntropy(): Unit = {
    opsSinceLastSample = 0

    val sha = new SHA256Digest()
    sha.update(seed, 0, 32)
    feedDigest(sha, System.currentTimeMillis())
    feedDigest(sha, System.identityHashCode(new Array[Int](1)))
    feedDigest(sha, memoryMXBean.getHeapMemoryUsage.getUsed)
    feedDigest(sha, memoryMXBean.getNonHeapMemoryUsage.getUsed)
    feedDigest(sha, runtimeMXBean.getPid)
    feedDigest(sha, runtimeMXBean.getUptime)
    feedDigest(sha, threadMXBean.getCurrentThreadCpuTime)
    feedDigest(sha, threadMXBean.getCurrentThreadUserTime)
    feedDigest(sha, threadMXBean.getPeakThreadCount)

    sha.doFinal(seed, 0)
    // NB: init internally resets the engine, no need to reset it explicitly ourselves.
    stream.init(true, new ParametersWithIV(new KeyParameter(seed), new Array[Byte](12)))
  }

  /** We sample new entropy approximately every 32 operations and at most every 64 operations. */
  private def shouldSample(): Boolean = {
    opsSinceLastSample += 1
    val condition1 = -4 <= lastByte && lastByte <= 4
    val condition2 = opsSinceLastSample >= 64
    condition1 || condition2
  }

  def addEntropy(entropy: Array[Byte]): Unit = synchronized {
    if (entropy.nonEmpty) {
      val sha = new SHA256Digest()
      sha.update(seed, 0, 32)
      sha.update(entropy, 0, entropy.length)
      sha.doFinal(seed, 0)
      // NB: init internally resets the engine, no need to reset it explicitly ourselves.
      stream.init(true, new ParametersWithIV(new KeyParameter(seed), new Array[Byte](12)))
    }
  }

  def nextBytes(bytes: Array[Byte]): Unit = synchronized {
    if (shouldSample()) {
      sampleEntropy()
    }
    stream.processBytes(bytes, 0, bytes.length, bytes, 0)
    lastByte = bytes.last
  }

  def nextLong(): Long = {
    val bytes = new Array[Byte](8)
    nextBytes(bytes)
    Protocol.uint64(bytes, ByteOrder.BIG_ENDIAN)
  }

}

class StrongRandom() extends RandomGeneratorWithInit with EntropyCollector {

  /**
   * We are using 'new SecureRandom()' instead of 'SecureRandom.getInstanceStrong()' because the latter can hang on Linux
   * See http://bugs.java.com/view_bug.do?bug_id=6521844 and https://tersesystems.com/2015/12/17/the-right-way-to-use-securerandom/
   */
  private val secureRandom = new SecureRandom()

  /**
   * We're using an additional, weaker randomness source to protect against catastrophic failures of the SecureRandom
   * instance.
   */
  private val weakRandom = new WeakRandom()

  override def init(): Unit = {
    // this will force the secure random instance to initialize itself right now, making sure it doesn't hang later
    secureRandom.nextInt()
  }

  override def addEntropy(entropy: Array[Byte]): Unit = {
    weakRandom.addEntropy(entropy)
  }

  override def nextBytes(bytes: Array[Byte]): Unit = {
    secureRandom.nextBytes(bytes)
    val buffer = new Array[Byte](bytes.length)
    weakRandom.nextBytes(buffer)
    for (i <- bytes.indices) {
      bytes(i) = (bytes(i) ^ buffer(i)).toByte
    }
  }

  override def nextLong(): Long = secureRandom.nextLong() ^ weakRandom.nextLong()

}