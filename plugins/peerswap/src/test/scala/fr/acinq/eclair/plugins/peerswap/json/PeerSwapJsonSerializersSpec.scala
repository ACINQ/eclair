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

package fr.acinq.eclair.plugins.peerswap.json

import fr.acinq.eclair.plugins.peerswap.PeerSwapSpec
import fr.acinq.eclair.plugins.peerswap.json.PeerSwapJsonSerializers.formats
import fr.acinq.eclair.plugins.peerswap.wire.protocol._
import org.json4s.jackson.JsonMethods.{compact, parse, render}
import org.json4s.jackson.Serialization

class PeerSwapJsonSerializersSpec extends PeerSwapSpec {
  test("encode/decode SwapInRequest to/from json") {
    val json = s"""{"protocol_version":$protocolVersion,"swap_id":"${swapId.toHex}","asset":"$asset","network":"$network","scid":"$shortId","amount":$amount,"pubkey":"$pubkey"}""".stripMargin
    val obj = SwapInRequest(protocolVersion = protocolVersion, swapId = swapId.toHex, asset = asset, network = network, scid = shortId.toString, amount = amount, pubkey = pubkey.toString)
    val encoded = compact(render(parse(Serialization.write(obj)).snakizeKeys))
    val decoded = Serialization.read[SwapInRequest](compact(render(parse(json).camelizeKeys)))
    assert(decoded === obj)
    assert(encoded === json)
  }

  test("encode/decode SwapOutRequest to/from json") {
    val json = s"""{"protocol_version":$protocolVersion,"swap_id":"${swapId.toHex}","asset":"$asset","network":"$network","scid":"$shortId","amount":$amount,"pubkey":"$pubkey"}""".stripMargin
    val obj = SwapOutRequest(protocolVersion = protocolVersion, swapId = swapId.toHex, asset = asset, network = network, scid = shortId.toString, amount = amount, pubkey = pubkey.toString)
    val encoded = compact(render(parse(Serialization.write(obj)).snakizeKeys))
    val decoded = Serialization.read[SwapOutRequest](compact(render(parse(json).camelizeKeys)))
    assert(encoded === json)
    assert(decoded === obj)
  }

  test("encode/decode SwapInAgreement to/from json") {
    val json = s"""{"protocol_version":$protocolVersion,"swap_id":"${swapId.toHex}","pubkey":"$pubkey","premium":$premium}""".stripMargin
    val obj = SwapInAgreement(protocolVersion = protocolVersion, swapId = swapId.toHex, pubkey = pubkey.toString, premium = premium)
    val encoded = compact(render(parse(Serialization.write(obj)).snakizeKeys))
    val decoded = Serialization.read[SwapInAgreement](compact(render(parse(json).camelizeKeys)))
    assert(encoded === json)
    assert(decoded === obj)
  }

  test("encode/decode SwapOutAgreement json") {
    val json = s"""{"protocol_version":$protocolVersion,"swap_id":"${swapId.toHex}","pubkey":"$pubkey","payreq":"$payreq"}""".stripMargin
    val obj = SwapOutAgreement(protocolVersion = protocolVersion, swapId = swapId.toHex, pubkey = pubkey.toString, payreq = payreq)
    val encoded = compact(render(parse(Serialization.write(obj)).snakizeKeys))
    val decoded = Serialization.read[SwapOutAgreement](compact(render(parse(json).camelizeKeys)))
    assert(encoded === json)
    assert(decoded === obj)
  }

  test("encode/decode OpeningTxBroadcasted to/from json") {
    val json = s"""{"swap_id":"${swapId.toHex}","payreq":"$payreq","tx_id":"$txid","script_out":$scriptOut,"blinding_key":"$blindingKey"}""".stripMargin
    val obj = OpeningTxBroadcasted(swapId = swapId.toHex, txId = txid, payreq = payreq, scriptOut = scriptOut, blindingKey = blindingKey)
    val encoded = compact(render(parse(Serialization.write(obj)).snakizeKeys))
    val decoded = Serialization.read[OpeningTxBroadcasted](compact(render(parse(json).camelizeKeys)))
    assert(encoded === json)
    assert(decoded === obj)
  }

  test("encode/decode Cancel to/from json") {
    val json = s"""{"swap_id":"${swapId.toHex}","message":"$message"}""".stripMargin
    val obj = CancelSwap(swapId = swapId.toHex, message = message)
    val encoded = compact(render(parse(Serialization.write(obj)).snakizeKeys))
    val decoded = Serialization.read[CancelSwap](compact(render(parse(json).camelizeKeys)))
    assert(encoded === json)
    assert(decoded === obj)
  }

  test("encode/decode CoopClose to/from json") {
    val json = s"""{"swap_id":"${swapId.toHex}","message":"$message","privkey":"$privkey"}""".stripMargin
    val obj = CoopClose(swapId = swapId.toHex, message = message, privkey = privkey.toString)
    val encoded = compact(render(parse(Serialization.write(obj)).snakizeKeys))
    val decoded = Serialization.read[CoopClose](compact(render(parse(json).camelizeKeys)))
    assert(encoded === json)
    assert(decoded === obj)
  }

}
