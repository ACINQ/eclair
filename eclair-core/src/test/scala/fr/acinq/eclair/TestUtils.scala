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

import java.io.File
import java.net.ServerSocket

import fr.acinq.bitcoin.{Base58, Base58Check, Bech32, OP_0, OP_CHECKSIG, OP_DUP, OP_EQUAL, OP_EQUALVERIFY, OP_HASH160, OP_PUSHDATA, Script}
import scodec.bits.ByteVector

object TestUtils {

  /**
    * Get the module's target directory (works from command line and within intellij)
    */
  val BUILD_DIRECTORY = sys
    .props
    .get("buildDirectory") // this is defined if we run from maven
    .getOrElse(new File(sys.props("user.dir"), "target").getAbsolutePath) // otherwise we probably are in intellij, so we build it manually assuming that user.dir == path to the module

  def availablePort: Int = synchronized {
    var serverSocket: ServerSocket = null
    try {
      serverSocket = new ServerSocket(0)
      serverSocket.getLocalPort
    } finally {
      if (serverSocket != null) {
        serverSocket.close()
      }
    }
  }

  /**
    * We currently use p2pkh script Helpers.getFinalScriptPubKey
    */
  def scriptPubKeyToAddress(scriptPubKey: ByteVector) = Script.parse(scriptPubKey) match {
    case OP_DUP :: OP_HASH160 :: OP_PUSHDATA(pubKeyHash, _) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil =>
      Base58Check.encode(Base58.Prefix.PubkeyAddressTestnet, pubKeyHash)
    case OP_HASH160 :: OP_PUSHDATA(scriptHash, _) :: OP_EQUAL :: Nil =>
      Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, scriptHash)
    case OP_0 :: OP_PUSHDATA(pubKeyHash, _) :: Nil if pubKeyHash.length == 20 => Bech32.encodeWitnessAddress("bcrt", 0, pubKeyHash)
    case OP_0 :: OP_PUSHDATA(scriptHash, _) :: Nil if scriptHash.length == 32 => Bech32.encodeWitnessAddress("bcrt", 0, scriptHash)
    case _ => ???
  }


}
