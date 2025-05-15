package fr.acinq.eclair.crypto

import fr.acinq.bitcoin.crypto.musig2.KeyAggCache
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, Protocol, TxId}
import fr.acinq.eclair.randomBytes32
import fr.acinq.eclair.transactions.Transactions.LocalNonce

import java.nio.ByteOrder

object NonceGenerator {
  private def generateNonce(sessionId: ByteVector32, publicKey: PublicKey): LocalNonce = {
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._
    val keyAggCache = KeyAggCache.create(java.util.List.of(publicKey)).getSecond
    val nonces = fr.acinq.bitcoin.crypto.musig2.SecretNonce.generate(sessionId, null, publicKey, null, keyAggCache, null)
    LocalNonce(nonces.getFirst, nonces.getSecond)
  }

  private def generateNonce(sessionId: ByteVector32, privateKey: PrivateKey, extraInput: Option[ByteVector32] = None): LocalNonce = {
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._
    val keyAggCache = KeyAggCache.create(java.util.List.of(privateKey.publicKey)).getSecond
    val nonces = fr.acinq.bitcoin.crypto.musig2.SecretNonce.generate(sessionId, privateKey, privateKey.publicKey, null, keyAggCache, extraInput.map(scala2kmp).orNull)
    LocalNonce(nonces.getFirst, nonces.getSecond)
  }

  def verificationNonce(fundingTxId: TxId, fundingPrivKey: PrivateKey, index: Long): LocalNonce = {
    val sessionId = Crypto.sha256(fundingPrivKey.value.bytes ++ Protocol.writeUInt64(index, ByteOrder.BIG_ENDIAN))
    val nonce = generateNonce(sessionId, fundingPrivKey, Some(fundingTxId.value))
    println(s"verification nonce for fundingTxId = $fundingTxId fundingPrivKey = ${fundingPrivKey.publicKey} index = $index is $nonce")
    nonce
  }

  def signingNonce(fundingPubKey: PublicKey): LocalNonce = {
    val sessionId = randomBytes32()
    val nonce = generateNonce(sessionId, fundingPubKey)
    nonce
  }
}
