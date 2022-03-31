/*
 * Copyright 2022 ACINQ SAS
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

package fr.acinq.eclair.swap

import fr.acinq.bitcoin.scalacompat.Crypto
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.ShortChannelId
import org.scalatest.TryValues.convertTryToSuccessOrFailure
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._

/**
 * Created by remyers on 04/04/2022.
 */

class PeerSwapSpec extends AnyFunSuite {
  val protocolVersion = 2
  val swapId = hex"dd650741ee45fbad5df209bfb5aea9537e2e6d946cc7ece3b4492bbae0732634"
  val asset = ""
  val network = "regtest"
  val shortId: ShortChannelId = ShortChannelId.fromCoordinates("539268x845x1").success.get
  val amount = 10000
  val pubkey: PublicKey = dummyKey(1).publicKey
  val premium = 1000
  val payreq = "invoice here"
  val txid = "38b854c569ff4b8b25e6eeec31d21ce4a1ee6dbc2afc7efdb44c81d513b4bffc"
  val scriptOut = 0
  val blindingKey = ""
  val message = "a message"
  val privkey: PrivateKey = dummyKey(1)

  def dummyKey(fill: Byte): Crypto.PrivateKey = PrivateKey(ByteVector.fill(32)(fill))
}
