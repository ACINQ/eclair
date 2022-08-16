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

import fr.acinq.bitcoin.scalacompat.Block
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.TestConstants.Alice
import fr.acinq.eclair.RealShortChannelId
import fr.acinq.eclair._
import fr.acinq.eclair.router.Announcements._
import fr.acinq.eclair.wire.protocol.ChannelUpdate.ChannelFlags
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs.nodeAnnouncementCodec
import fr.acinq.eclair.wire.protocol.NodeAddress
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._

/**
 * Created by PM on 31/05/2016.
 */

class AnnouncementsSpec extends AnyFunSuite {

  test("check nodeId1/nodeId2 lexical ordering") {
    val node1 = PublicKey(hex"027710df7a1d7ad02e3572841a829d141d9f56b17de9ea124d2f83ea687b2e0461")
    val node2 = PublicKey(hex"0306a730778d55deec162a74409e006034a24c46d541c67c6c45f89a2adde3d9b4")
    // NB: node1 < node2
    assert(isNode1(node1, node2))
    assert(!isNode1(node2, node1))
  }

  test("create valid signed channel announcement") {
    val (node_a, node_b, bitcoin_a, bitcoin_b) = (randomKey(), randomKey(), randomKey(), randomKey())
    val witness = Announcements.generateChannelAnnouncementWitness(Block.RegtestGenesisBlock.hash, RealShortChannelId(42), node_a.publicKey, node_b.publicKey, bitcoin_a.publicKey, bitcoin_b.publicKey, Features.empty)
    val node_a_sig = Announcements.signChannelAnnouncement(witness, node_a)
    val bitcoin_a_sig = Announcements.signChannelAnnouncement(witness, bitcoin_a)
    val node_b_sig = Announcements.signChannelAnnouncement(witness, node_b)
    val bitcoin_b_sig = Announcements.signChannelAnnouncement(witness, bitcoin_b)
    val ann = makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, RealShortChannelId(42), node_a.publicKey, node_b.publicKey, bitcoin_a.publicKey, bitcoin_b.publicKey, node_a_sig, node_b_sig, bitcoin_a_sig, bitcoin_b_sig)
    assert(checkSigs(ann))
    assert(checkSigs(ann.copy(nodeId1 = randomKey().publicKey)) == false)
  }

  test("create valid signed node announcement") {
    val features = Features(
      Features.DataLossProtect -> FeatureSupport.Optional,
      Features.InitialRoutingSync -> FeatureSupport.Optional,
      Features.ChannelRangeQueries -> FeatureSupport.Optional,
      Features.ChannelRangeQueriesExtended -> FeatureSupport.Optional,
      Features.VariableLengthOnion -> FeatureSupport.Mandatory,
      Features.PaymentSecret -> FeatureSupport.Mandatory,
      Features.BasicMultiPartPayment -> FeatureSupport.Optional,
      Features.PaymentMetadata -> FeatureSupport.Optional,
    )
    val ann = makeNodeAnnouncement(Alice.nodeParams.privateKey, Alice.nodeParams.alias, Alice.nodeParams.color, Alice.nodeParams.publicAddresses, features.nodeAnnouncementFeatures())
    // Features should be filtered to only include node_announcement related features.
    assert(ann.features == Features(
      Features.DataLossProtect -> FeatureSupport.Optional,
      Features.ChannelRangeQueries -> FeatureSupport.Optional,
      Features.ChannelRangeQueriesExtended -> FeatureSupport.Optional,
      Features.VariableLengthOnion -> FeatureSupport.Mandatory,
      Features.PaymentSecret -> FeatureSupport.Mandatory,
      Features.BasicMultiPartPayment -> FeatureSupport.Optional,
    ))
    assert(checkSig(ann))
    assert(checkSig(ann.copy(timestamp = 153 unixsec)) == false)
  }

  test("sort node announcement addresses") {
    val addresses = List(
      NodeAddress.fromParts("acinq.co", 9735).get,
      NodeAddress.fromParts("iq7zhmhck54vcax2vlrdcavq2m32wao7ekh6jyeglmnuuvv3js57r4id.onion", 9735).get,
      NodeAddress.fromParts("2620:1ec:c11:0:0:0:0:200", 9735).get,
      NodeAddress.fromParts("140.82.121.4", 9735).get,
    )
    val ann = makeNodeAnnouncement(Alice.nodeParams.privateKey, Alice.nodeParams.alias, Alice.nodeParams.color, addresses, Alice.nodeParams.features.nodeAnnouncementFeatures())
    assert(checkSig(ann))
    assert(ann.addresses == List(
      NodeAddress.fromParts("140.82.121.4", 9735).get,
      NodeAddress.fromParts("2620:1ec:c11:0:0:0:0:200", 9735).get,
      NodeAddress.fromParts("iq7zhmhck54vcax2vlrdcavq2m32wao7ekh6jyeglmnuuvv3js57r4id.onion", 9735).get,
      NodeAddress.fromParts("acinq.co", 9735).get,
    ))
  }

  test("filter invalid and deprecated node announcement addresses") {
    val addresses = List(
      NodeAddress.fromParts("140.82.121.5", 9735).get,
      NodeAddress.fromParts("140.82.121.4", 0).get, // ignore IPv4 with port 0
      NodeAddress.fromParts("140.82.121.4", 9735).get, // more than one IPv4 is OK
      NodeAddress.fromParts("2620:1ec:c11:0:0:0:0:201", 9735).get,
      NodeAddress.fromParts("2620:1ec:c11:0:0:0:0:200", 9735).get, // more than one IPv6 is OK
      NodeAddress.fromParts("2620:1ec:c11:0:0:0:0:200", 0).get, // ignore IPv6 with port 0
      NodeAddress.fromParts("hsmithsxurybd7uh.onion", 9735).get, // deprecate Torv2 addresses
      NodeAddress.fromParts("iq7zhmhck54vcax2vlrdcavq2m32wao7ekh6jyeglmnuuvv3js57r4id.onion", 0).get, // port zero for Tor is OK
      NodeAddress.fromParts("of7husrflx7sforh3fw6yqlpwstee3wg5imvvmkp4bz6rbjxtg5nljad.onion", 9735).get, // more than one Tor is OK
      NodeAddress.fromParts("acinq.co", 0).get, // ignore DNS hostname with port 0
      NodeAddress.fromParts("acinq.co", 9735).get,
      NodeAddress.fromParts("acinq.fr", 9735).get, // ignore more than one DNS hostnames
    )
    val ann = makeNodeAnnouncement(Alice.nodeParams.privateKey, Alice.nodeParams.alias, Alice.nodeParams.color, addresses, Alice.nodeParams.features.nodeAnnouncementFeatures())
    assert(checkSig(ann))
    assert(ann.validAddresses === List(
      NodeAddress.fromParts("140.82.121.5", 9735).get,
      NodeAddress.fromParts("140.82.121.4", 9735).get,
      NodeAddress.fromParts("2620:1ec:c11:0:0:0:0:201", 9735).get,
      NodeAddress.fromParts("2620:1ec:c11:0:0:0:0:200", 9735).get,
      NodeAddress.fromParts("iq7zhmhck54vcax2vlrdcavq2m32wao7ekh6jyeglmnuuvv3js57r4id.onion", 0).get,
      NodeAddress.fromParts("of7husrflx7sforh3fw6yqlpwstee3wg5imvvmkp4bz6rbjxtg5nljad.onion", 9735).get,
      NodeAddress.fromParts("acinq.co", 9735).get
    ))
  }

  test("nodeParams.nodeId equals nodeParams.privateKey.publicKey") {
    assert(Alice.nodeParams.nodeId == Alice.nodeParams.privateKey.publicKey)
  }

  test("create valid signed channel update announcement") {
    val ann = makeChannelUpdate(Block.RegtestGenesisBlock.hash, Alice.nodeParams.privateKey, randomKey().publicKey, ShortChannelId(45561L), Alice.nodeParams.channelConf.expiryDelta, Alice.nodeParams.channelConf.htlcMinimum, Alice.nodeParams.relayParams.publicChannelFees.feeBase, Alice.nodeParams.relayParams.publicChannelFees.feeProportionalMillionths, 500000000 msat)
    assert(checkSig(ann, Alice.nodeParams.nodeId))
    assert(checkSig(ann, randomKey().publicKey) == false)
  }

  test("check flags") {
    val node1_priv = PrivateKey(hex"5f447b05d86de82de6b245a65359d22f844ae764e2ae3824ac4ace7d8e1c749b01")
    val node2_priv = PrivateKey(hex"eff467c5b601fdcc07315933767013002cd0705223d8e526cbb0c1bc75ccb62901")
    // NB: node1 < node2 (public keys)
    assert(isNode1(node1_priv.publicKey, node2_priv.publicKey))
    assert(!isNode1(node2_priv.publicKey, node1_priv.publicKey))
    val channelUpdate1 = makeChannelUpdate(Block.RegtestGenesisBlock.hash, node1_priv, node2_priv.publicKey, ShortChannelId(0), CltvExpiryDelta(0), 0 msat, 0 msat, 0, 500000000 msat, enable = true)
    val channelUpdate1_disabled = makeChannelUpdate(Block.RegtestGenesisBlock.hash, node1_priv, node2_priv.publicKey, ShortChannelId(0), CltvExpiryDelta(0), 0 msat, 0 msat, 0, 500000000 msat, enable = false)
    val channelUpdate2 = makeChannelUpdate(Block.RegtestGenesisBlock.hash, node2_priv, node1_priv.publicKey, ShortChannelId(0), CltvExpiryDelta(0), 0 msat, 0 msat, 0, 500000000 msat, enable = true)
    val channelUpdate2_disabled = makeChannelUpdate(Block.RegtestGenesisBlock.hash, node2_priv, node1_priv.publicKey, ShortChannelId(0), CltvExpiryDelta(0), 0 msat, 0 msat, 0, 500000000 msat, enable = false)
    assert(channelUpdate1.channelFlags == ChannelFlags(isEnabled = true, isNode1 = true)) // ....00
    assert(channelUpdate1_disabled.channelFlags == ChannelFlags(isEnabled = false, isNode1 = true)) // ....10
    assert(channelUpdate2.channelFlags == ChannelFlags(isEnabled = true, isNode1 = false)) // ....01
    assert(channelUpdate2_disabled.channelFlags == ChannelFlags(isEnabled = false, isNode1 = false)) // ....11
    assert(channelUpdate1.channelFlags.isNode1)
    assert(channelUpdate1_disabled.channelFlags.isNode1)
    assert(!channelUpdate2.channelFlags.isNode1)
    assert(!channelUpdate2_disabled.channelFlags.isNode1)
    assert(channelUpdate1.channelFlags.isEnabled)
    assert(!channelUpdate1_disabled.channelFlags.isEnabled)
    assert(channelUpdate2.channelFlags.isEnabled)
    assert(!channelUpdate2_disabled.channelFlags.isEnabled)
  }

  test("announce irrelevant features") {
    // This announcement has option_payment_metadata which is not a node feature
    val encoded = hex"25d8bf19e2c6562a85b3109122118a50728d42c9054537b5a26c1e982407f0923a6f162cf0939bfaf58d7e7a7a7c86d7dfd02dda9b0d2036e8fd35a7afc78daa00070100000000000061fcfc1d039093816bb908c043341f3ee47ea86179627d26aa00704593503e684a0b9a38cb01020374657374206e6f646500000000000000000000000000000000000000000000000007018c5279042607"
    val ann = nodeAnnouncementCodec.decode(encoded.bits).require.value
    assert(ann.features.hasFeature(Features.PaymentMetadata))
    assert(checkSig(ann))
    assert(nodeAnnouncementCodec.encode(ann).require.bytes == encoded)
  }
}
