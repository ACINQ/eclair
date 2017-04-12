package fr.acinq.eclair.crypto

import fr.acinq.bitcoin.BinaryData
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

/**
  * Created by fabrice on 12/04/17.
  */
@RunWith(classOf[JUnitRunner])
class SphinxPR145Spec extends FunSuite {
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
    BinaryData("0x414141414141414141414141414141414100000000000000000000000000000000"),
    BinaryData("0x414141414141414141414141414141414100000000000000000000000000000000"),
    BinaryData("0x414141414141414141414141414141414100000000000000000000000000000000"),
    BinaryData("0x414141414141414141414141414141414100000000000000000000000000000000"),
    BinaryData("0x414141414141414141414141414141414100000000000000000000000000000000"))

  val associatedData: BinaryData = "0x4242424242424242424242424242424242424242424242424242424242424242"

  test("parse 1-hop packet") {
    val raw = BinaryData("0102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f2836866194c4fbecf0a2a21892c45d9b5aa509a365d7d022ca4b0021183e62e8373f4a52c035fd754061ab9e0bc887beff8c95fdb878f7b3a7141453e5f8d22b6351810ae541ce499a09b4a9d9f80d1845c8960c85fc6d1a87bd24b2ce49922898e9353fa268086c00ae8b7f718405b72ad380cdbb38c85e02a00427eb4bdbda8fcd42b44708a9efde49cf753b75ebb389bf84d0bfbf58590e510e034572a01e409c30939e2e4a090ecc89c371820af54e06e4ad5495d4e58718385cca5414552e078fedf284fdc2cc5c070cba21a6a8d4b77525ddbc9a9fca9b2f29aac5783ee8badd709f81c73ff60556cf2ee623af073b5a84799acc1ca46b764f74b97068c7826cc0579794a540d7a55e49eac26a6930340132e946a983240b0cd1b732e305c1042f590c4b26f140fc1cab3ee6f620958e0979f85eddf586c410ce42e93a4d7c803ead45fc47cf4396d284632314d789e73cf3f534126c63fe244069d9e8a7c4f98e7e530fc588e648ef4e641364981b5377542d5e7a4aaab6d35f6df7d3a9d7ca715213599ee02c4dbea4dc78860febe1d29259c64b59b3333ffdaebbaff4e7b31c27a3791f6bf848a58df7c69bb2b1852d2ad357b9919ffdae570b27dc709fba087273d3a4de9e6a6be66db647fb6a8d1a503b3f481befb96745abf5cc4a6bba0f780d5c7759b9e303a2a6b17eb05b6e660f4c474959db183e1cae060e1639227ee0bca03978a238dc4352ed764da7d4f3ed5337f6d0376dff72615beeeeaaeef79ab93e4bcbf18cd8424eb2b6ad7f33d2b4ffd5ea08372e6ed1d984152df17e04c6f73540988d7dd979e020424a163c271151a255966be7edef42167b8facca633649739bab97572b485658cde409e5d4a0f653f1a5911141634e3d2b6079b19347df66f9820755fd517092dae62fb278b0bafcc7ad682f7921b3a455e0c6369988779e26f0458b31bffd7e4e5bfb31944e80f100b2553c3b616e75be18328dc430f6618d55cd7d0962bb916d26ed4b117c46fa29e0a112c02c36020b34a96762db628fa3490828ec2079962ad816ef20ea0bca78fb2b7f7aedd4c47e375e64294d151ff03083730336dea64934003a27730cc1c7dec5049ddba8188123dd191aa71390d43a49fb792a3da7082efa6cced73f00eccea18145fbc84925349f7b552314ab8ed4c491e392aed3b1f03eb79474c294b42e2eba1528da26450aa592cba7ea22e965c54dff0fd6fdfd6b52b9a0f5f762e27fb0e6c3cd326a1ca1c5973de9be881439f702830affeb0c034c18ac8d5c2f135c964bf69de50d6e99bde88e90321ba843d9753c8f83666105d25fafb1a11ea22d62ef6f1fc34ca4e60c35d69773a104d9a44728c08c20b6314327301a2c400a71e1424c12628cf9f4a67990ade8a2203b0edb96c6082d4673b7309cd52c4b32b02951db2f66c6c72bd6c7eac2b50b83830c75cdfc3d6e9c2b592c45ed5fa5f6ec0da85710b7e1562aea363e28665835791dc574d9a70b2e5e2b9973ab590d45b94d244fc")
    val SphinxPR145.ParsedPacket(payload, nextPacket, sharedSecret) = SphinxPR145.parsePacket(privKeys(0), associatedData, raw)
    assert(nextPacket == BinaryData("01028f9438bfbf7feac2e108d677e3a82da596be706cc1cf342b75c7b7e22bf4e6e2000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000004256926c5a55b01cd0aca21fe5f9c907691fb026d0c56788b03ca3f08db0abb9f901098dde2ec4003568bc3ca27475ff86a7cb0aab"))
  }

  test("parse 2-hop packet") {
    val raw = BinaryData("0102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619a84f87d4cf02e4f0387456161c04017b7e5e3a72a4b0021183e62e8373f4a52c035fd754061ab9e0bc887beff8c95fdb878f7b3a71d07f2d00f9c77994251e861f162dde3da7bda32dc2d59599fb5b04a0dacb131d6b2f9e0c766dca91fc29ec8bf8e1c482940a72a5d3e5eeefdf9910c08a542e8c43f8111c9aed119dc3cf18e1abe4db1802a58b8e177752763920dca5e06ff30e3337ba365c0972bf5efb526ad11a3baf39843c89331ffb6a4d0ee8f0b95c59b5a33e3e0464ab184f10860bb582c6a5760ec04680f6b1bbde967158fda50d1f580c36634a5d7abe0185f776b89bce245e8777fb2b843979406056c302ccffd69a88ce6b2a52dc6f32272e25d91bf72f055667873fa9a7aaec4eb90d432ed1c40a921feb844886f413f4083f1b7de79bd72e236e671cc623df4b8a5c5cebe2656d1cf8d43685d8ceb3a2c0c99f69265c887e618633e00e172a38db887c8c4b368c681a154afcdf399d755c442babcf03d1ab7aa3d28622639ea9596e7fa7774348b9880d02459819633c5669d80b58a8c3761812930887d88a17fe57234242dc592720eb17d408c0db18b164613bc0c0f77cf36e8bd27a573d7f204d2eb6cf4a72675499a4c426b3c9c484f1c24f007bbe91b25b1d0133b912deb9b9c2f5fa087dcec26d9c4624c9575afd80ff6be481c55d864c4a85801adf2a499e0a51846d954eabcd50de52b0248360e3a54fbb4f7ced9cb7cbb5a987003c622fb269d1517eddc9142c3ffa87e0973173eff9d5f24105ca0b6d50acdef393c8ee70f6d221853e2fc265a9a2792cc7b61f26823ec2d3db1bd2287c5d665aa3143e1f1216fef8f7236801e860925567bdb2868ef80bdc9b5b83b1cdbf17c4cc740bbec1db724e9cb5ea2b33442e5ac1624236c3be33dc8c9d7bd5415bfeb72f4f44fbf9a88bd63e585ff5572dca1cce859defa6dcc115f1ec71d38cf1547e6dc07ffb0dc156b81acadd91a72386a4b809f05999ab277204e115f7ca54245774338ce027f3e11f24561412e742e64854d6df45a1dc109b0bfa0ac7bfb70ffb93801099f6262e807d1cd8526f64a6683a3d624fcbd5dfdc23599e9015f84926bc66fa29c9003308e58500da4cd52b89f4d287b1e03838b13ce9d9b16a3c3e560937dcb3aa7e02b6b628297ca4ec4ee5181dd987908a9f38966c8eece3024476273f9ab222825ed1523ad6624c213ab83124590a32ad4abb63b2bace9563c559cf7ab5d5320ca199890265c9f0495b4714f24438c16e12b55bae6059aeab65ef87dec58ebfcfb81ca8413f4fe3dff01037f48e68b7d5bc745f4873e054af9645e40b9dbc042b312c5949e876faf22da920a546043644d688356f185b624df2908e344765ea0e4036db041532983496ec34a0865150d71319c89bc86a1ff216f87ce478dd6b264990eceea7eb135eede449d88afc87df0c64dbf40bbdaa817ce0fd5a7eb7b0b0053b9cc01f2a16b63ef5bfb8baf1c6c3feada8a0866f197e60bb43eac5b59fc9d55adcda2e")
    val SphinxPR145.ParsedPacket(payload, nextPacket, sharedSecret) = SphinxPR145.parsePacket(privKeys(0), associatedData, raw)
    val SphinxPR145.ParsedPacket(payload1, nextPacket1, sharedSecret1) = SphinxPR145.parsePacket(privKeys(1), associatedData, nextPacket)
    assert(Seq(payload, payload1) == payloads.take(2))
    val SphinxPR145.Packet(_, _, hmac, _) = SphinxPR145.Packet.read(nextPacket1)
    assert(hmac == SphinxPR145.zeroes(SphinxPR145.MacLength))
  }

  test("create 1-hop packet") {
    val SphinxPR145.OnionPacket(onion, _) = SphinxPR145.makePacket(sessionKey, publicKeys.take(1), payloads.take(1), associatedData)
    val SphinxPR145.ParsedPacket(payload, nextPacket, sharedSecret) = SphinxPR145.parsePacket(privKeys(0), associatedData, onion)
    assert(onion == BinaryData("0102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f2836866194c4fbecf0a2a21892c45d9b5aa509a365d7d022ca4b0021183e62e8373f4a52c035fd754061ab9e0bc887beff8c95fdb878f7b3a7141453e5f8d22b6351810ae541ce499a09b4a9d9f80d1845c8960c85fc6d1a87bd24b2ce49922898e9353fa268086c00ae8b7f718405b72ad380cdbb38c85e02a00427eb4bdbda8fcd42b44708a9efde49cf753b75ebb389bf84d0bfbf58590e510e034572a01e409c30939e2e4a090ecc89c371820af54e06e4ad5495d4e58718385cca5414552e078fedf284fdc2cc5c070cba21a6a8d4b77525ddbc9a9fca9b2f29aac5783ee8badd709f81c73ff60556cf2ee623af073b5a84799acc1ca46b764f74b97068c7826cc0579794a540d7a55e49eac26a6930340132e946a983240b0cd1b732e305c1042f590c4b26f140fc1cab3ee6f620958e0979f85eddf586c410ce42e93a4d7c803ead45fc47cf4396d284632314d789e73cf3f534126c63fe244069d9e8a7c4f98e7e530fc588e648ef4e641364981b5377542d5e7a4aaab6d35f6df7d3a9d7ca715213599ee02c4dbea4dc78860febe1d29259c64b59b3333ffdaebbaff4e7b31c27a3791f6bf848a58df7c69bb2b1852d2ad357b9919ffdae570b27dc709fba087273d3a4de9e6a6be66db647fb6a8d1a503b3f481befb96745abf5cc4a6bba0f780d5c7759b9e303a2a6b17eb05b6e660f4c474959db183e1cae060e1639227ee0bca03978a238dc4352ed764da7d4f3ed5337f6d0376dff72615beeeeaaeef79ab93e4bcbf18cd8424eb2b6ad7f33d2b4ffd5ea08372e6ed1d984152df17e04c6f73540988d7dd979e020424a163c271151a255966be7edef42167b8facca633649739bab97572b485658cde409e5d4a0f653f1a5911141634e3d2b6079b19347df66f9820755fd517092dae62fb278b0bafcc7ad682f7921b3a455e0c6369988779e26f0458b31bffd7e4e5bfb31944e80f100b2553c3b616e75be18328dc430f6618d55cd7d0962bb916d26ed4b117c46fa29e0a112c02c36020b34a96762db628fa3490828ec2079962ad816ef20ea0bca78fb2b7f7aedd4c47e375e64294d151ff03083730336dea64934003a27730cc1c7dec5049ddba8188123dd191aa71390d43a49fb792a3da7082efa6cced73f00eccea18145fbc84925349f7b552314ab8ed4c491e392aed3b1f03eb79474c294b42e2eba1528da26450aa592cba7ea22e965c54dff0fd6fdfd6b52b9a0f5f762e27fb0e6c3cd326a1ca1c5973de9be881439f702830affeb0c034c18ac8d5c2f135c964bf69de50d6e99bde88e90321ba843d9753c8f83666105d25fafb1a11ea22d62ef6f1fc34ca4e60c35d69773a104d9a44728c08c20b6314327301a2c400a71e1424c12628cf9f4a67990ade8a2203b0edb96c6082d4673b7309cd52c4b32b02951db2f66c6c72bd6c7eac2b50b83830c75cdfc3d6e9c2b592c45ed5fa5f6ec0da85710b7e1562aea363e28665835791dc574d9a70b2e5e2b9973ab590d45b94d244fc"))
  }

  test("create 2-hop packet") {
    val SphinxPR145.OnionPacket(onion, _) = SphinxPR145.makePacket(sessionKey, publicKeys.take(2), payloads.take(2), associatedData)
    val SphinxPR145.ParsedPacket(payload, nextPacket, sharedSecret) = SphinxPR145.parsePacket(privKeys(0), associatedData, onion)
    val SphinxPR145.ParsedPacket(payload1, nextPacket1, sharedSecret1) = SphinxPR145.parsePacket(privKeys(1), associatedData, nextPacket)
    assert(onion == BinaryData("0102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619a84f87d4cf02e4f0387456161c04017b7e5e3a72a4b0021183e62e8373f4a52c035fd754061ab9e0bc887beff8c95fdb878f7b3a71d07f2d00f9c77994251e861f162dde3da7bda32dc2d59599fb5b04a0dacb131d6b2f9e0c766dca91fc29ec8bf8e1c482940a72a5d3e5eeefdf9910c08a542e8c43f8111c9aed119dc3cf18e1abe4db1802a58b8e177752763920dca5e06ff30e3337ba365c0972bf5efb526ad11a3baf39843c89331ffb6a4d0ee8f0b95c59b5a33e3e0464ab184f10860bb582c6a5760ec04680f6b1bbde967158fda50d1f580c36634a5d7abe0185f776b89bce245e8777fb2b843979406056c302ccffd69a88ce6b2a52dc6f32272e25d91bf72f055667873fa9a7aaec4eb90d432ed1c40a921feb844886f413f4083f1b7de79bd72e236e671cc623df4b8a5c5cebe2656d1cf8d43685d8ceb3a2c0c99f69265c887e618633e00e172a38db887c8c4b368c681a154afcdf399d755c442babcf03d1ab7aa3d28622639ea9596e7fa7774348b9880d02459819633c5669d80b58a8c3761812930887d88a17fe57234242dc592720eb17d408c0db18b164613bc0c0f77cf36e8bd27a573d7f204d2eb6cf4a72675499a4c426b3c9c484f1c24f007bbe91b25b1d0133b912deb9b9c2f5fa087dcec26d9c4624c9575afd80ff6be481c55d864c4a85801adf2a499e0a51846d954eabcd50de52b0248360e3a54fbb4f7ced9cb7cbb5a987003c622fb269d1517eddc9142c3ffa87e0973173eff9d5f24105ca0b6d50acdef393c8ee70f6d221853e2fc265a9a2792cc7b61f26823ec2d3db1bd2287c5d665aa3143e1f1216fef8f7236801e860925567bdb2868ef80bdc9b5b83b1cdbf17c4cc740bbec1db724e9cb5ea2b33442e5ac1624236c3be33dc8c9d7bd5415bfeb72f4f44fbf9a88bd63e585ff5572dca1cce859defa6dcc115f1ec71d38cf1547e6dc07ffb0dc156b81acadd91a72386a4b809f05999ab277204e115f7ca54245774338ce027f3e11f24561412e742e64854d6df45a1dc109b0bfa0ac7bfb70ffb93801099f6262e807d1cd8526f64a6683a3d624fcbd5dfdc23599e9015f84926bc66fa29c9003308e58500da4cd52b89f4d287b1e03838b13ce9d9b16a3c3e560937dcb3aa7e02b6b628297ca4ec4ee5181dd987908a9f38966c8eece3024476273f9ab222825ed1523ad6624c213ab83124590a32ad4abb63b2bace9563c559cf7ab5d5320ca199890265c9f0495b4714f24438c16e12b55bae6059aeab65ef87dec58ebfcfb81ca8413f4fe3dff01037f48e68b7d5bc745f4873e054af9645e40b9dbc042b312c5949e876faf22da920a546043644d688356f185b624df2908e344765ea0e4036db041532983496ec34a0865150d71319c89bc86a1ff216f87ce478dd6b264990eceea7eb135eede449d88afc87df0c64dbf40bbdaa817ce0fd5a7eb7b0b0053b9cc01f2a16b63ef5bfb8baf1c6c3feada8a0866f197e60bb43eac5b59fc9d55adcda2e"))
  }
}
