/*
 * Copyright 2018 ACINQ SAS
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

import fr.acinq.bitcoin.BinaryData
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.wire._
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

import scala.util.Success

/**
  * Created by fabrice on 10/01/17.
  */
@RunWith(classOf[JUnitRunner])
class SphinxSpec extends FunSuite {

  import Sphinx._
  import SphinxSpec._

  /*
  hop_shared_secret[0] = 0x53eb63ea8a3fec3b3cd433b85cd62a4b145e1dda09391b348c4e1cd36a03ea66
  hop_blinding_factor[0] = 0x2ec2e5da605776054187180343287683aa6a51b4b1c04d6dd49c45d8cffb3c36
  hop_ephemeral_pubkey[0] = 0x02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619

  hop_shared_secret[1] = 0xa6519e98832a0b179f62123b3567c106db99ee37bef036e783263602f3488fae
  hop_blinding_factor[1] = 0xbf66c28bc22e598cfd574a1931a2bafbca09163df2261e6d0056b2610dab938f
  hop_ephemeral_pubkey[1] = 0x028f9438bfbf7feac2e108d677e3a82da596be706cc1cf342b75c7b7e22bf4e6e2

  hop_shared_secret[2] = 0x3a6b412548762f0dbccce5c7ae7bb8147d1caf9b5471c34120b30bc9c04891cc
  hop_blinding_factor[2] = 0xa1f2dadd184eb1627049673f18c6325814384facdee5bfd935d9cb031a1698a5
  hop_ephemeral_pubkey[2] = 0x03bfd8225241ea71cd0843db7709f4c222f62ff2d4516fd38b39914ab6b83e0da0

  hop_shared_secret[3] = 0x21e13c2d7cfe7e18836df50872466117a295783ab8aab0e7ecc8c725503ad02d
  hop_blinding_factor[3] = 0x7cfe0b699f35525029ae0fa437c69d0f20f7ed4e3916133f9cacbb13c82ff262
  hop_ephemeral_pubkey[3] = 0x031dde6926381289671300239ea8e57ffaf9bebd05b9a5b95beaf07af05cd43595

  hop_shared_secret[4] = 0xb5756b9b542727dbafc6765a49488b023a725d631af688fc031217e90770c328
  hop_blinding_factor[4] = 0xc96e00dddaf57e7edcd4fb5954be5b65b09f17cb6d20651b4e90315be5779205
  hop_ephemeral_pubkey[4] = 0x03a214ebd875aab6ddfd77f22c5e7311d7f77f17a169e599f157bbcdae8bf071f4
  */
  test("generate ephemeral keys and secrets") {
    val (ephkeys, sharedsecrets) = computeEphemeralPublicKeysAndSharedSecrets(sessionKey, publicKeys)
    assert(ephkeys(0) == PublicKey(BinaryData("0x02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619")))
    assert(sharedsecrets(0) == BinaryData("0x53eb63ea8a3fec3b3cd433b85cd62a4b145e1dda09391b348c4e1cd36a03ea66"))
    assert(ephkeys(1) == PublicKey(BinaryData("0x028f9438bfbf7feac2e108d677e3a82da596be706cc1cf342b75c7b7e22bf4e6e2")))
    assert(sharedsecrets(1) == BinaryData("0xa6519e98832a0b179f62123b3567c106db99ee37bef036e783263602f3488fae"))
    assert(ephkeys(2) == PublicKey(BinaryData("0x03bfd8225241ea71cd0843db7709f4c222f62ff2d4516fd38b39914ab6b83e0da0")))
    assert(sharedsecrets(2) == BinaryData("0x3a6b412548762f0dbccce5c7ae7bb8147d1caf9b5471c34120b30bc9c04891cc"))
    assert(ephkeys(3) == PublicKey(BinaryData("0x031dde6926381289671300239ea8e57ffaf9bebd05b9a5b95beaf07af05cd43595")))
    assert(sharedsecrets(3) == BinaryData("0x21e13c2d7cfe7e18836df50872466117a295783ab8aab0e7ecc8c725503ad02d"))
    assert(ephkeys(4) == PublicKey(BinaryData("0x03a214ebd875aab6ddfd77f22c5e7311d7f77f17a169e599f157bbcdae8bf071f4")))
    assert(sharedsecrets(4) == BinaryData("0xb5756b9b542727dbafc6765a49488b023a725d631af688fc031217e90770c328"))
  }

  /*
  filler = 0x10032728b17453b91699003c62027964f74c6948df8798b011a555e09f49c36e6af1f9216514be5f8b81032281ab6bc8baefa2293c08d94c30e1ddda8b57ec074c9d895eaae2bddd0967f399904ca9d81efeecebc38a931362a49adfe99b71294e0d91b256fa6e843385a6af905dcc76b8cd21aad19b0f5fa8b44cb62078bbab9bf140013639e923df9402bfcb15558b33b0a99d57f4075143fd4fd60b7ffbd99def8e6b725109c38aad3ef244fc28092e64b179d0feb430261822ce52f5bc23c3c52c45364f732e2076b697162203176439510296c6c0f0119f39799bc1740a033cfc44aba80b74c9f3d823ee9844b6a31839bc0298f0253b19911441d6085fca875132b2cc9dcc4d0c23c29c6279ce4012200456ccd4866c07bcd803ba51435ac1b262
   */
  test("generate filler") {
    val (_, sharedsecrets) = computeEphemeralPublicKeysAndSharedSecrets(sessionKey, publicKeys)
    val filler = generateFiller("rho", sharedsecrets.dropRight(1), PayloadLength + MacLength, 20)
    assert(filler == BinaryData("0x10032728b17453b91699003c62027964f74c6948df8798b011a555e09f49c36e6af1f9216514be5f8b81032281ab6bc8baefa2293c08d94c30e1ddda8b57ec074c9d895eaae2bddd0967f399904ca9d81efeecebc38a931362a49adfe99b71294e0d91b256fa6e843385a6af905dcc76b8cd21aad19b0f5fa8b44cb62078bbab9bf140013639e923df9402bfcb15558b33b0a99d57f4075143fd4fd60b7ffbd99def8e6b725109c38aad3ef244fc28092e64b179d0feb430261822ce52f5bc23c3c52c45364f732e2076b697162203176439510296c6c0f0119f39799bc1740a033cfc44aba80b74c9f3d823ee9844b6a31839bc0298f0253b19911441d6085fca875132b2cc9dcc4d0c23c29c6279ce4012200456ccd4866c07bcd803ba51435ac1b262"))
  }

  test("create packet (reference test vector)") {
    val Sphinx.PacketAndSecrets(onion, sharedSecrets) = Sphinx.makePacket(sessionKey, publicKeys, payloads, associatedData)
    assert(onion.serialize == BinaryData("0x0002eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619e5f14350c2a76fc232b5e46d421e9615471ab9e0bc887beff8c95fdb878f7b3a7141453e5f8d22b635df72babb30ab0d8599464c6d77e1c21034fc0152ddd22067c3c97e565ec5aa399017aba3b2fd4cb5b4ec0d3fb97e8104974398ace3fff68778257a3cc1002d414794d9336c0b36979a435ce0ec919049c6d3dea5501ff810846b89c9480e1b52c9e2b6536c6229912f94785542c27af60d057054053ac91cced5693271d844743f6d21d388c60f563c0ab3c7a148a031a72dab181a7de4eba044315eaa5616b7634d5f3a268b10e5e81a81f8e4bba070078966d663a8bb86d2ad6f7c242721108e82ab0fad9bab939cde9a2b73139a4a110ddbb31b17f20a4bb741635db37386c9566b2d39d11cf78cb9cc6c1c7726212cbfcdcdd7b9b334522e2376ce351220bc967f72fafebab6aca804b1c05d138be868f7b07a3f7117c6ddf7855af4c4cb2d85b73b59f1cfd878b5bc17af7ad50a8d2b3e716f3f3d373292a4653bcc46f681b2d42a2fb9801d61da14f01f450710e14844da4d3c4f4d772d738561e145ac5d5c3dac8ff92586df6e4a75831857c191231e0a538b1784ec5288abe807ae40d4a6330ac8634c8a672440840f92847f760db21a476faec50611b48ac983f8748881d85272fc2096700891ad113592d3a419e94f953d0d486f58d4561f2b8eb25477c16a4f98acf87787e63d60b22911cbc8a64633269d1fde831f2682c6de251ccccb36892e03348d962bb30667932b907b4ee592c5337653005d90dd6a60f4fe0359f21ece6d86bca1bee963ede61428b7d639882528b4abce8adea2913a8497fd654fd21e0bde37d70b0c1b432acedb21b7c3f9a44ed8fc5139968f237b9055ee260694c535ef76cffbd8025fd6e6d5c4778551129e0cc5500c1d70c48e63d8111822e0c5b18e3a22c7ecc4184f522058a7f3d5e048f09e6d56784be32bcaedef4daf61fa258e66128146fb93f9915d166eabba47556fa5632de43d3707b761a940fb9c1290cedd247d257230bcc4aa5129622e0c4c18b23403654bdb0e25d1c6a792adfde96f65e2d451398b0baedaa449468893d40f2f64f2723359faa3952e37b744219a3b19e52e7f4029e54e7830b902bf81d70501f392583575e0505d862c7e40a8153262276e3f1d46dd9350ab1d9963aa12d291afb5d480ac7c3ebd23e56a90084a309316b5adc2c373eb18d2a68e79a4e7c17e8f1d3bc7e2f3dff59a9594c32e32feabfac8789db7279486ca07fcea0036d6615dc765ef0cff5e9b14438cf25d66f44a617475c9e1c65405da5b8becbfed48d670c8e3435e5bc454d92093bf5e2df6a80b326ab832b2e28958c14a894b92eea616191e47a20ae12dd137532e753b9c67e0337af237bc74316d4e874ed1bd6eb42999fb8debe831614329c02c67d05806fd7c5bb3e41a90aca471c60a636aded28f82d65061a7697fedf227eeebc41882ee10a857607313a3aca09829a473273194edec67d173c541ef81a003a6e8b50a355e8ae4d1699b58ac54f4283420b0cdef7b6cefad6e6039d6125be2483c7dab846f2d88a9f2bf461119ce0a4335ce51938c48b91b23e5116ef0b26ebfb8e1c065d74639377f5d8ffe89457e940f663cda8e6faeb55ae9556a0d5fd1515da372d2d1d6337d0e464ac1400e3fb8efc36a989e3b2dba172ea818c01f8ba979c7750fd2abbd863b645c7f1733a782213ee9be03d42f1411db84e722996cf8ad94d1d031e477a10ac887a865c208399c5b45fdaefc69c3d8c95e0b8c9a54f5c3e22ac2c543fe3a11ea13c170b63a167869a22e77e89bda6f9ff4dc10e414d12b139387cb7b14bde7ba74751af4d02d197c34d1b360e0c778a462945174ed8fcfac4504b9dbb4534f26d3633f554e2241d9d8a66861534739734070a41ec151b13e19a84f2dda191a307666f39b2bb1be1eda4a64f81e41feff3a0b1b97f59c7f6f3b690f83a043ca393586afc4b6fd6cca7f554b9700352f2697afb2bbec52d5635033ba8822ed7e3c07a33fbccd68756152eb1b81da4c7c0429e787e62b9235e96a35fee4d5121e7c2b8ad0d9e5f70c9c17b916d5e2ba02ceb14343620b5d0df0d9d765"))

    val Success(Sphinx.ParsedPacket(payload0, nextPacket0, sharedSecret0)) = Sphinx.parsePacket(privKeys(0), associatedData, onion.serialize)
    val Success(Sphinx.ParsedPacket(payload1, nextPacket1, sharedSecret1)) = Sphinx.parsePacket(privKeys(1), associatedData, nextPacket0.serialize)
    val Success(Sphinx.ParsedPacket(payload2, nextPacket2, sharedSecret2)) = Sphinx.parsePacket(privKeys(2), associatedData, nextPacket1.serialize)
    val Success(Sphinx.ParsedPacket(payload3, nextPacket3, sharedSecret3)) = Sphinx.parsePacket(privKeys(3), associatedData, nextPacket2.serialize)
    val Success(Sphinx.ParsedPacket(payload4, nextPacket4, sharedSecret4)) = Sphinx.parsePacket(privKeys(4), associatedData, nextPacket3.serialize)
    assert(Seq(payload0, payload1, payload2, payload3, payload4) == payloads)

    val packets = Seq(nextPacket0, nextPacket1, nextPacket2, nextPacket3, nextPacket4)
    assert(packets(0).hmac == BinaryData("0xc76214ef2c4f9425020cd1f2f730464cbd9cc90d1b03881c118252b2c7e723b7"))
    assert(packets(1).hmac == BinaryData("0x65c1f1f190c77a19ce6c3fb27e3464b5f19b62fc5d27624327e55c49af6e23f2"))
    assert(packets(2).hmac == BinaryData("0xf0e9ef26e3ca781b8d2b71e8ad94c78d06602a933d7ae465931dcd251d4d032d"))
    assert(packets(3).hmac == BinaryData("0xa955eba86eeb5f22f8eafcbbe99a1cb11808410f1958301973dbe15092dc2957"))
    // this means that node #4 is the last node
    assert(packets(4).hmac == BinaryData("0x0000000000000000000000000000000000000000000000000000000000000000"))
  }

  test("last node replies with an error message") {
    // route: origin -> node #0 -> node #1 -> node #2 -> node #3 -> node #4

    // origin build the onion packet
    val PacketAndSecrets(packet, sharedSecrets) = makePacket(sessionKey, publicKeys, payloads, associatedData)

    // each node parses and forwards the packet
    // node #0
    val Success(ParsedPacket(payload0, packet1, sharedSecret0)) = parsePacket(privKeys(0), associatedData, packet.serialize)
    // node #1
    val Success(ParsedPacket(payload1, packet2, sharedSecret1)) = parsePacket(privKeys(1), associatedData, packet1.serialize)
    // node #2
    val Success(ParsedPacket(payload2, packet3, sharedSecret2)) = parsePacket(privKeys(2), associatedData, packet2.serialize)
    // node #3
    val Success(ParsedPacket(payload3, packet4, sharedSecret3)) = parsePacket(privKeys(3), associatedData, packet3.serialize)
    // node #4
    val Success(ParsedPacket(payload4, packet5, sharedSecret4)) = parsePacket(privKeys(4), associatedData, packet4.serialize)
    assert(packet5.isLastPacket)

    // node #4 want to reply with an error message
    val error = createErrorPacket(sharedSecret4, TemporaryNodeFailure)
    assert(error == BinaryData("a5e6bd0c74cb347f10cce367f949098f2457d14c046fd8a22cb96efb30b0fdcda8cb9168b50f2fd45edd73c1b0c8b33002df376801ff58aaa94000bf8a86f92620f343baef38a580102395ae3abf9128d1047a0736ff9b83d456740ebbb4aeb3aa9737f18fb4afb4aa074fb26c4d702f42968888550a3bded8c05247e045b866baef0499f079fdaeef6538f31d44deafffdfd3afa2fb4ca9082b8f1c465371a9894dd8c243fb4847e004f5256b3e90e2edde4c9fb3082ddfe4d1e734cacd96ef0706bf63c9984e22dc98851bcccd1c3494351feb458c9c6af41c0044bea3c47552b1d992ae542b17a2d0bba1a096c78d169034ecb55b6e3a7263c26017f033031228833c1daefc0dedb8cf7c3e37c9c37ebfe42f3225c326e8bcfd338804c145b16e34e4"))
    //    assert(error == BinaryData("69b1e5a3e05a7b5478e6529cd1749fdd8c66da6f6db42078ff8497ac4e117e91a8cb9168b58f2fd45edd73c1b0c8b33002df376801ff58aaa94000bf8a86f92620f343baef38a580102395ae3abf9128d1047a0736ff9b83d456740ebbb4aeb3aa9737f18fb4afb4aa074fb26c4d702f42968888550a3bded8c05247e045b866baef0499f079fdaeef6538f31d44deafffdfd3afa2fb4ca9082b8f1c465371a9894dd8c2"))
    // error sent back to 3, 2, 1 and 0
    val error1 = forwardErrorPacket(error, sharedSecret3)
    assert(error1 == BinaryData("c49a1ce81680f78f5f2000cda36268de34a3f0a0662f55b4e837c83a8773c22aa081bab1616a0011585323930fa5b9fae0c85770a2279ff59ec427ad1bbff9001c0cd1497004bd2a0f68b50704cf6d6a4bf3c8b6a0833399a24b3456961ba00736785112594f65b6b2d44d9f5ea4e49b5e1ec2af978cbe31c67114440ac51a62081df0ed46d4a3df295da0b0fe25c0115019f03f15ec86fabb4c852f83449e812f141a9395b3f70b766ebbd4ec2fae2b6955bd8f32684c15abfe8fd3a6261e52650e8807a92158d9f1463261a925e4bfba44bd20b166d532f0017185c3a6ac7957adefe45559e3072c8dc35abeba835a8cb01a71a15c736911126f27d46a36168ca5ef7dccd4e2886212602b181463e0dd30185c96348f9743a02aca8ec27c0b90dca270"))
    //    assert(error1 == BinaryData("08cd44478211b8a4370ab1368b5ffe8c9c92fb830ff4ad6e3b0a316df9d24176a081bab161ea0011585323930fa5b9fae0c85770a2279ff59ec427ad1bbff9001c0cd1497004bd2a0f68b50704cf6d6a4bf3c8b6a0833399a24b3456961ba00736785112594f65b6b2d44d9f5ea4e49b5e1ec2af978cbe31c67114440ac51a62081df0ed46d4a3df295da0b0fe25c0115019f03f15ec86fabb4c852f83449e812f141a93"))

    val error2 = forwardErrorPacket(error1, sharedSecret2)
    assert(error2 == BinaryData("a5d3e8634cfe78b2307d87c6d90be6fe7855b4f2cc9b1dfb19e92e4b79103f61ff9ac25f412ddfb7466e74f81b3e545563cdd8f5524dae873de61d7bdfccd496af2584930d2b566b4f8d3881f8c043df92224f38cf094cfc09d92655989531524593ec6d6caec1863bdfaa79229b5020acc034cd6deeea1021c50586947b9b8e6faa83b81fbfa6133c0af5d6b07c017f7158fa94f0d206baf12dda6b68f785b773b360fd0497e16cc402d779c8d48d0fa6315536ef0660f3f4e1865f5b38ea49c7da4fd959de4e83ff3ab686f059a45c65ba2af4a6a79166aa0f496bf04d06987b6d2ea205bdb0d347718b9aeff5b61dfff344993a275b79717cd815b6ad4c0beb568c4ac9c36ff1c315ec1119a1993c4b61e6eaa0375e0aaf738ac691abd3263bf937e3"))
    //    assert(error2 == BinaryData("6984b0ccd86f37995857363df13670acd064bfd1a540e521cad4d71c07b1bc3dff9ac25f41addfb7466e74f81b3e545563cdd8f5524dae873de61d7bdfccd496af2584930d2b566b4f8d3881f8c043df92224f38cf094cfc09d92655989531524593ec6d6caec1863bdfaa79229b5020acc034cd6deeea1021c50586947b9b8e6faa83b81fbfa6133c0af5d6b07c017f7158fa94f0d206baf12dda6b68f785b773b360fd"))

    val error3 = forwardErrorPacket(error2, sharedSecret1)
    assert(error3 == BinaryData("aac3200c4968f56b21f53e5e374e3a2383ad2b1b6501bbcc45abc31e59b26881b7dfadbb56ec8dae8857add94e6702fb4c3a4de22e2e669e1ed926b04447fc73034bb730f4932acd62727b75348a648a1128744657ca6a4e713b9b646c3ca66cac02cdab44dd3439890ef3aaf61708714f7375349b8da541b2548d452d84de7084bb95b3ac2345201d624d31f4d52078aa0fa05a88b4e20202bd2b86ac5b52919ea305a8949de95e935eed0319cf3cf19ebea61d76ba92532497fcdc9411d06bcd4275094d0a4a3c5d3a945e43305a5a9256e333e1f64dbca5fcd4e03a39b9012d197506e06f29339dfee3331995b21615337ae060233d39befea925cc262873e0530408e6990f1cbd233a150ef7b004ff6166c70c68d9f8c853c1abca640b8660db2921"))
    //    assert(error3 == BinaryData("669478a3ddf9ba4049df8fa51f73ac712b9c20380cda431696963a492713ebddb7dfadbb566c8dae8857add94e6702fb4c3a4de22e2e669e1ed926b04447fc73034bb730f4932acd62727b75348a648a1128744657ca6a4e713b9b646c3ca66cac02cdab44dd3439890ef3aaf61708714f7375349b8da541b2548d452d84de7084bb95b3ac2345201d624d31f4d52078aa0fa05a88b4e20202bd2b86ac5b52919ea305a8"))

    val error4 = forwardErrorPacket(error3, sharedSecret0)
    assert(error4 == BinaryData("9c5add3963fc7f6ed7f148623c84134b5647e1306419dbe2174e523fa9e2fbed3a06a19f899145610741c83ad40b7712aefaddec8c6baf7325d92ea4ca4d1df8bce517f7e54554608bf2bd8071a4f52a7a2f7ffbb1413edad81eeea5785aa9d990f2865dc23b4bc3c301a94eec4eabebca66be5cf638f693ec256aec514620cc28ee4a94bd9565bc4d4962b9d3641d4278fb319ed2b84de5b665f307a2db0f7fbb757366067d88c50f7e829138fde4f78d39b5b5802f1b92a8a820865af5cc79f9f30bc3f461c66af95d13e5e1f0381c184572a91dee1c849048a647a1158cf884064deddbf1b0b88dfe2f791428d0ba0f6fb2f04e14081f69165ae66d9297c118f0907705c9c4954a199bae0bb96fad763d690e7daa6cfda59ba7f2c8d11448b604d12d"))
    //    assert(error4 == BinaryData("500d8596f76d3045bfdbf99914b98519fe76ea130dc22338c473ab68d74378b13a06a19f891145610741c83ad40b7712aefaddec8c6baf7325d92ea4ca4d1df8bce517f7e54554608bf2bd8071a4f52a7a2f7ffbb1413edad81eeea5785aa9d990f2865dc23b4bc3c301a94eec4eabebca66be5cf638f693ec256aec514620cc28ee4a94bd9565bc4d4962b9d3641d4278fb319ed2b84de5b665f307a2db0f7fbb757366"))


    // origin parses error packet and can see that it comes from node #4
    val Success(ErrorPacket(pubkey, failure)) = parseErrorPacket(error4, sharedSecrets)
    assert(pubkey == publicKeys(4))
    assert(failure == TemporaryNodeFailure)
  }

  test("intermediate node replies with an error message") {
    // route: origin -> node #0 -> node #1 -> node #2 -> node #3 -> node #4

    // origin build the onion packet
    val PacketAndSecrets(packet, sharedSecrets) = makePacket(sessionKey, publicKeys, payloads, associatedData)

    // each node parses and forwards the packet
    // node #0
    val Success(ParsedPacket(payload0, packet1, sharedSecret0)) = parsePacket(privKeys(0), associatedData, packet.serialize)
    // node #1
    val Success(ParsedPacket(payload1, packet2, sharedSecret1)) = parsePacket(privKeys(1), associatedData, packet1.serialize)
    // node #2
    val Success(ParsedPacket(payload2, packet3, sharedSecret2)) = parsePacket(privKeys(2), associatedData, packet2.serialize)

    // node #2 want to reply with an error message
    val error = createErrorPacket(sharedSecret2, InvalidRealm)

    // error sent back to 1 and 0
    val error1 = forwardErrorPacket(error, sharedSecret1)
    val error2 = forwardErrorPacket(error1, sharedSecret0)

    // origin parses error packet and can see that it comes from node #2
    val Success(ErrorPacket(pubkey, failure)) = parseErrorPacket(error2, sharedSecrets)
    assert(pubkey == publicKeys(2))
    assert(failure == InvalidRealm)
  }
}

object SphinxSpec {
  val privKeys = Seq(
    PrivateKey(BinaryData("0x4141414141414141414141414141414141414141414141414141414141414141"), compressed = true),
    PrivateKey(BinaryData("0x4242424242424242424242424242424242424242424242424242424242424242"), compressed = true),
    PrivateKey(BinaryData("0x4343434343434343434343434343434343434343434343434343434343434343"), compressed = true),
    PrivateKey(BinaryData("0x4444444444444444444444444444444444444444444444444444444444444444"), compressed = true),
    PrivateKey(BinaryData("0x4545454545454545454545454545454545454545454545454545454545454545"), compressed = true)
  )
  val publicKeys = privKeys.map(_.publicKey)
  assert(publicKeys == Seq(
    PublicKey(BinaryData("0x02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619")),
    PublicKey(BinaryData("0x0324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c")),
    PublicKey(BinaryData("0x027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007")),
    PublicKey(BinaryData("0x032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991")),
    PublicKey(BinaryData("0x02edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145"))
  ))

  val sessionKey: PrivateKey = PrivateKey(BinaryData("0x4141414141414141414141414141414141414141414141414141414141414141"), compressed = true)
  val payloads = Seq(
    BinaryData("0x0000000000000000000000000000000000000000000000000000000000000000000000000000000000"),
    BinaryData("0x0001010101010101010000000100000001000000000000000000000000000000000101010100000000"),
    BinaryData("0x0002020202020202020000000200000002000000000000000000000000000000000202020000000000"),
    BinaryData("0x0003030303030303030000000300000003000000000000000000000000000000000303030000000000"),
    BinaryData("0x0004040404040404040000000400000004000000000000000000000000000000000000000000000000"))

  val associatedData: BinaryData = "0x4242424242424242424242424242424242424242424242424242424242424242"
}
