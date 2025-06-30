/*
 * Copyright 2025 ACINQ SAS
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

import fr.acinq.eclair.TestUtils.randomTxId
import fr.acinq.eclair.randomKey
import org.scalatest.funsuite.AnyFunSuite

class NonceGeneratorSpec extends AnyFunSuite {

  test("generate deterministic commitment verification nonces") {
    val fundingTxId1 = randomTxId()
    val fundingKey1 = randomKey()
    val remoteFundingKey1 = randomKey().publicKey
    val fundingTxId2 = randomTxId()
    val fundingKey2 = randomKey()
    val remoteFundingKey2 = randomKey().publicKey
    // The verification nonce changes for each commitment.
    val nonces1 = (0 until 15).map(commitIndex => NonceGenerator.verificationNonce(fundingTxId1, fundingKey1, remoteFundingKey1, commitIndex))
    assert(nonces1.toSet.size == 15)
    // We can re-compute verification nonces deterministically.
    (0 until 15).foreach(i => assert(nonces1(i) == NonceGenerator.verificationNonce(fundingTxId1, fundingKey1, remoteFundingKey1, i)))
    // Nonces for different splices are different.
    val nonces2 = (0 until 15).map(commitIndex => NonceGenerator.verificationNonce(fundingTxId2, fundingKey2, remoteFundingKey2, commitIndex))
    assert((nonces1 ++ nonces2).toSet.size == 30)
    // Changing any of the parameters changes the nonce value.
    assert(!nonces1.contains(NonceGenerator.verificationNonce(fundingTxId2, fundingKey1, remoteFundingKey1, 3)))
    assert(!nonces1.contains(NonceGenerator.verificationNonce(fundingTxId1, fundingKey2, remoteFundingKey1, 11)))
    assert(!nonces1.contains(NonceGenerator.verificationNonce(fundingTxId1, fundingKey1, remoteFundingKey2, 7)))
  }

  test("generate random signing nonces") {
    val fundingTxId = randomTxId()
    val localFundingKey = randomKey().publicKey
    val remoteFundingKey = randomKey().publicKey
    // Signing nonces are random and different every time, even if the parameters are the same.
    val nonce1 = NonceGenerator.signingNonce(localFundingKey, remoteFundingKey, fundingTxId)
    val nonce2 = NonceGenerator.signingNonce(localFundingKey, remoteFundingKey, fundingTxId)
    assert(nonce1 != nonce2)
    val nonce3 = NonceGenerator.signingNonce(localFundingKey, remoteFundingKey, randomTxId())
    assert(nonce3 != nonce1)
    assert(nonce3 != nonce2)
  }

}
