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

package fr.acinq.eclair.plugins.peerswap.wire.protocol

import fr.acinq.eclair.plugins.peerswap.PeerSwapSpec
import fr.acinq.eclair.plugins.peerswap.wire.protocol.PeerSwapMessageCodecs.peerSwapMessageCodecWithFallback
import scodec.bits.HexStringSyntax

class PeerSwapMessageCodecsSpec extends PeerSwapSpec {

  test("encode/decode SwapInRequest messages to/from binary") {
    val json = s"""{"protocol_version":$protocolVersion,"swap_id":"${swapId.toHex}","asset":"$asset","network":"$network","scid":"$shortId","amount":$amount,"pubkey":"$pubkey"}""".stripMargin
    val bin = hex"0xa4557b2270726f746f636f6c5f76657273696f6e223a322c22737761705f6964223a2264643635303734316565343566626164356466323039626662356165613935333765326536643934366363376563653362343439326262616530373332363334222c226173736574223a22222c226e6574776f726b223a2272656774657374222c2273636964223a22353339323638783834357831222c22616d6f756e74223a31303030302c227075626b6579223a22303331623834633535363762313236343430393935643365643561616261303536356437316531383334363034383139666639633137663565396435646430373866227d"
    val obj = SwapInRequest(protocolVersion, swapId.toHex, asset, network, shortId.toString, amount, pubkey.toString())
    val encoded = peerSwapMessageCodecWithFallback.encode(obj).require
    val decoded = peerSwapMessageCodecWithFallback.decode(encoded).require
    val decoded_bin = peerSwapMessageCodecWithFallback.decode(bin.bits).require
    assert(json === obj.json)
    assert(encoded.bytes === bin)
    assert(obj === decoded.value)
    assert(obj === decoded_bin.value)
  }

  test("encode/decode SwapOutRequest messages to/from binary") {
    val json = s"""{"protocol_version":$protocolVersion,"swap_id":"${swapId.toHex}","asset":"$asset","network":"$network","scid":"$shortId","amount":$amount,"pubkey":"$pubkey"}""".stripMargin
    val obj = SwapOutRequest(protocolVersion, swapId.toHex, asset, network, shortId.toString, amount, pubkey.toString())
    val bin = hex"a4577b2270726f746f636f6c5f76657273696f6e223a322c22737761705f6964223a2264643635303734316565343566626164356466323039626662356165613935333765326536643934366363376563653362343439326262616530373332363334222c226173736574223a22222c226e6574776f726b223a2272656774657374222c2273636964223a22353339323638783834357831222c22616d6f756e74223a31303030302c227075626b6579223a22303331623834633535363762313236343430393935643365643561616261303536356437316531383334363034383139666639633137663565396435646430373866227d"
    val encoded = peerSwapMessageCodecWithFallback.encode(obj).require
    val decoded = peerSwapMessageCodecWithFallback.decode(encoded).require
    val decoded_bin = peerSwapMessageCodecWithFallback.decode(bin.bits).require
    assert(json === obj.json)
    assert(encoded.bytes === bin)
    assert(obj === decoded.value)
    assert(obj === decoded_bin.value)
  }

  test("encode/decode SwapInAgreement messages to/from binary") {
    val json = s"""{"protocol_version":$protocolVersion,"swap_id":"${swapId.toHex}","pubkey":"$pubkey","premium":$premium}""".stripMargin
    val obj = SwapInAgreement(protocolVersion = protocolVersion, swapId = swapId.toHex, pubkey = pubkey.toString, premium = premium)
    val bin = hex"a4597b2270726f746f636f6c5f76657273696f6e223a322c22737761705f6964223a2264643635303734316565343566626164356466323039626662356165613935333765326536643934366363376563653362343439326262616530373332363334222c227075626b6579223a22303331623834633535363762313236343430393935643365643561616261303536356437316531383334363034383139666639633137663565396435646430373866222c227072656d69756d223a313030307d"
    val encoded = peerSwapMessageCodecWithFallback.encode(obj).require
    val decoded = peerSwapMessageCodecWithFallback.decode(encoded).require
    val decoded_bin = peerSwapMessageCodecWithFallback.decode(bin.bits).require
    assert(json === obj.json)
    assert(encoded.bytes === bin)
    assert(obj === decoded.value)
    assert(obj === decoded_bin.value)
  }

  test("encode/decode SwapOutAgreement messages to/from binary") {
    val json = s"""{"protocol_version":$protocolVersion,"swap_id":"${swapId.toHex}","pubkey":"$pubkey","payreq":"$payreq"}""".stripMargin
    val obj = SwapOutAgreement(protocolVersion = protocolVersion, swapId = swapId.toHex, pubkey = pubkey.toString, payreq = payreq)
    val bin = hex"a45b7b2270726f746f636f6c5f76657273696f6e223a322c22737761705f6964223a2264643635303734316565343566626164356466323039626662356165613935333765326536643934366363376563653362343439326262616530373332363334222c227075626b6579223a22303331623834633535363762313236343430393935643365643561616261303536356437316531383334363034383139666639633137663565396435646430373866222c22706179726571223a22696e766f6963652068657265227d"
    val encoded = peerSwapMessageCodecWithFallback.encode(obj).require
    val decoded = peerSwapMessageCodecWithFallback.decode(encoded).require
    val decoded_bin = peerSwapMessageCodecWithFallback.decode(bin.bits).require
    assert(json === obj.json)
    assert(encoded.bytes === bin)
    assert(obj === decoded.value)
    assert(obj === decoded_bin.value)
  }

  test("encode/decode OpeningTxBroadcasted messages to/from binary") {
    val json = s"""{"swap_id":"${swapId.toHex}","payreq":"$payreq","tx_id":"$txid","script_out":$scriptOut,"blinding_key":"$blindingKey"}""".stripMargin
    val obj = OpeningTxBroadcasted(swapId = swapId.toHex, payreq = payreq, txId = txid,  scriptOut = scriptOut, blindingKey = blindingKey)
    val bin = hex"a45d7b22737761705f6964223a2264643635303734316565343566626164356466323039626662356165613935333765326536643934366363376563653362343439326262616530373332363334222c22706179726571223a22696e766f6963652068657265222c2274785f6964223a2233386238353463353639666634623862323565366565656333316432316365346131656536646263326166633765666462343463383164353133623462666663222c227363726970745f6f7574223a302c22626c696e64696e675f6b6579223a22227d"
    val encoded = peerSwapMessageCodecWithFallback.encode(obj).require
    val decoded = peerSwapMessageCodecWithFallback.decode(encoded).require
    val decoded_bin = peerSwapMessageCodecWithFallback.decode(bin.bits).require
    assert(json === obj.json)
    assert(encoded.bytes === bin)
    assert(obj === decoded.value)
    assert(obj === decoded_bin.value)
  }

  test("encode/decode Cancel messages to/from binary") {
    val json = s"""{"swap_id":"${swapId.toHex}","message":"$message"}""".stripMargin
    val obj = CancelSwap(swapId = swapId.toHex, message = message)
    val bin = hex"a45f7b22737761705f6964223a2264643635303734316565343566626164356466323039626662356165613935333765326536643934366363376563653362343439326262616530373332363334222c226d657373616765223a2261206d657373616765227d"
    val encoded = peerSwapMessageCodecWithFallback.encode(obj).require
    val decoded = peerSwapMessageCodecWithFallback.decode(encoded).require
    val decoded_bin = peerSwapMessageCodecWithFallback.decode(bin.bits).require
    assert(json === obj.json)
    assert(encoded.bytes === bin)
    assert(obj === decoded.value)
    assert(obj === decoded_bin.value)
  }

  test("encode/decode CoopClose messages to/from binary") {
    val json = s"""{"swap_id":"${swapId.toHex}","message":"$message","privkey":"$privkey"}""".stripMargin
    val obj = CoopClose(swapId = swapId.toHex, message = message, privkey = privkey.toString)
    val bin = hex"a4617b22737761705f6964223a2264643635303734316565343566626164356466323039626662356165613935333765326536643934366363376563653362343439326262616530373332363334222c226d657373616765223a2261206d657373616765222c22707269766b6579223a223c707269766174655f6b65793e227d"
    val encoded = peerSwapMessageCodecWithFallback.encode(obj).require
    val decoded = peerSwapMessageCodecWithFallback.decode(encoded).require
    val decoded_bin = peerSwapMessageCodecWithFallback.decode(bin.bits).require
    assert(json === obj.json)
    assert(encoded.bytes === bin)
    assert(obj === decoded.value)
    assert(obj === decoded_bin.value)
  }

}
