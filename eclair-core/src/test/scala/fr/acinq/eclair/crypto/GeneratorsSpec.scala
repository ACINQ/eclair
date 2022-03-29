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

package fr.acinq.eclair.crypto

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._


class GeneratorsSpec extends AnyFunSuite {
  val base_secret: PrivateKey = PrivateKey(hex"000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
  val per_commitment_secret: PrivateKey = PrivateKey(hex"1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100")
  val base_point = PublicKey(hex"036d6caac248af96f6afa7f904f550253a0f3ef3f5aa2fe6838a95b216691468e2")
  val per_commitment_point = PublicKey(hex"025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce1486")

  test("derivation of key from basepoint and per-commitment-point") {
    val localKey = Generators.derivePubKey(base_point, per_commitment_point)
    assert(localKey.value == hex"0235f2dbfaa89b57ec7b055afe29849ef7ddfeb1cefdb9ebdc43f5494984db29e5")
  }

  test("derivation of secret key from basepoint secret and per-commitment-secret") {
    val localprivkey = Generators.derivePrivKey(base_secret, per_commitment_point)
    assert(localprivkey.value == ByteVector32(hex"cbced912d3b21bf196a766651e436aff192362621ce317704ea2f75d87e7be0f"))
  }

  test("derivation of revocation key from basepoint and per-commitment-point") {
    val revocationkey = Generators.revocationPubKey(base_point, per_commitment_point)
    assert(revocationkey.value == hex"02916e326636d19c33f13e8c0c3a03dd157f332f3e99c317c141dd865eb01f8ff0")
  }

  test("derivation of revocation secret from basepoint-secret and per-commitment-secret") {
    val revocationprivkey = Generators.revocationPrivKey(base_secret, per_commitment_secret)
    assert(revocationprivkey.value == ByteVector32(hex"d09ffff62ddb2297ab000cc85bcb4283fdeb6aa052affbc9dddcf33b61078110"))
  }
}
