package fr.acinq.eclair.wire.protocol

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding
import fr.acinq.eclair.wire.protocol.EncryptedRecipientDataTlv._
import fr.acinq.eclair.{ShortChannelId, UInt64, randomKey}
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.HexStringSyntax

import scala.util.Success

class EncryptedRecipientDataSpec extends AnyFunSuiteLike {

  test("decode encrypted recipient data") {
    val sessionKey = randomKey()
    val nodePrivKeys = Seq(randomKey(), randomKey(), randomKey(), randomKey(), randomKey())
    val payloads = Seq(
      (TlvStream[EncryptedRecipientDataTlv](Padding(hex"000000"), OutgoingChannelId(ShortChannelId(561))), hex"0103000000 02080000000000000231"),
      (TlvStream[EncryptedRecipientDataTlv](OutgoingNodeId(PublicKey(hex"025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce1486"))), hex"0421025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce1486"),
      (TlvStream[EncryptedRecipientDataTlv](OutgoingNodeId(PublicKey(hex"025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce1486")), NextBlinding(PublicKey(hex"027710df7a1d7ad02e3572841a829d141d9f56b17de9ea124d2f83ea687b2e0461"))), hex"0421025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce1486 0c21027710df7a1d7ad02e3572841a829d141d9f56b17de9ea124d2f83ea687b2e0461"),
      (TlvStream[EncryptedRecipientDataTlv](PathId(hex"0101010101010101010101010101010101010101010101010101010101010101")), hex"06200101010101010101010101010101010101010101010101010101010101010101"),
      (TlvStream[EncryptedRecipientDataTlv](Seq(OutgoingChannelId(ShortChannelId(42))), Seq(GenericTlv(UInt64(65535), hex"06c1"))), hex"0208000000000000002a fdffff0206c1"),
    )

    val blindedRoute = RouteBlinding.create(sessionKey, nodePrivKeys.map(_.publicKey), payloads.map(_._2))
    val blinding0 = sessionKey.publicKey
    val Success((decryptedPayload0, blinding1)) = EncryptedRecipientDataCodecs.decode(nodePrivKeys(0), blinding0, blindedRoute.encryptedPayloads(0))
    val Success((decryptedPayload1, blinding2)) = EncryptedRecipientDataCodecs.decode(nodePrivKeys(1), blinding1, blindedRoute.encryptedPayloads(1))
    val Success((decryptedPayload2, blinding3)) = EncryptedRecipientDataCodecs.decode(nodePrivKeys(2), blinding2, blindedRoute.encryptedPayloads(2))
    val Success((decryptedPayload3, blinding4)) = EncryptedRecipientDataCodecs.decode(nodePrivKeys(3), blinding3, blindedRoute.encryptedPayloads(3))
    val Success((decryptedPayload4, _)) = EncryptedRecipientDataCodecs.decode(nodePrivKeys(4), blinding4, blindedRoute.encryptedPayloads(4))
    assert(Seq(decryptedPayload0, decryptedPayload1, decryptedPayload2, decryptedPayload3, decryptedPayload4) === payloads.map(_._1))
  }

  test("decode invalid encrypted recipient data") {
    val testCases = Seq(
      hex"02080000000000000231 ff", // additional trailing bytes after tlv stream
      hex"01040000 02080000000000000231", // invalid padding tlv
      hex"0420025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce14", // invalid public key length
      hex"0c20025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce14", // invalid next blinding length
      hex"02080000000000000231 0103000000", // invalid tlv stream ordering
      hex"02080000000000000231 10080000000000000231", // unknown even tlv field
    )

    for (testCase <- testCases) {
      val nodePrivKeys = Seq(randomKey(), randomKey())
      val payloads = Seq(hex"02080000000000000231", testCase)
      val blindingPrivKey = randomKey()
      val blindedRoute = RouteBlinding.create(blindingPrivKey, nodePrivKeys.map(_.publicKey), payloads)
      // The payload for the first node is valid.
      val blinding0 = blindingPrivKey.publicKey
      val Success((_, blinding1)) = EncryptedRecipientDataCodecs.decode(nodePrivKeys.head, blinding0, blindedRoute.encryptedPayloads.head)
      // If the first node is given invalid decryption material, it cannot decrypt recipient data.
      assert(EncryptedRecipientDataCodecs.decode(nodePrivKeys.last, blinding0, blindedRoute.encryptedPayloads.head).isFailure)
      assert(EncryptedRecipientDataCodecs.decode(nodePrivKeys.head, blinding1, blindedRoute.encryptedPayloads.head).isFailure)
      assert(EncryptedRecipientDataCodecs.decode(nodePrivKeys.head, blinding0, blindedRoute.encryptedPayloads.last).isFailure)
      // The payload for the last node is invalid, even with valid decryption material.
      assert(EncryptedRecipientDataCodecs.decode(nodePrivKeys.last, blinding1, blindedRoute.encryptedPayloads.last).isFailure)
    }
  }

}
