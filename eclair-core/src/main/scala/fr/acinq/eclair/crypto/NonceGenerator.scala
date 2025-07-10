package fr.acinq.eclair.crypto

import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{Musig2, TxId}
import fr.acinq.eclair.randomBytes32
import fr.acinq.eclair.transactions.Transactions.LocalNonce
import grizzled.slf4j.Logging

object NonceGenerator extends Logging {
  /**
   * Deterministic nonce, used to sign our commit tx. Its public part is sent to our peer
   *
   * @param fundingTxId    funding transaction id
   * @param fundingPrivKey funding private key
   * @param index          commitment index
   * @return a deterministic nonce
   */
  def verificationNonce(fundingTxId: TxId, fundingPrivKey: PrivateKey, index: Long): LocalNonce = {
    val nonces = Musig2.generateNonceWithCounter(index, fundingPrivKey, Seq(fundingPrivKey.publicKey), None, Some(fundingTxId.value))
    LocalNonce(nonces._1, nonces._2)
  }

  /**
   * Random nonce used to sign our peer's commit tx.
   *
   * @param fundingPubKey funding public key which matches the private that will be used with this nonce
   * @return a random nonce
   */
  def signingNonce(fundingPubKey: PublicKey): LocalNonce = {
    val sessionId = randomBytes32()
    val nonces = Musig2.generateNonce(sessionId, Right(fundingPubKey), Seq(fundingPubKey), None, None)
    LocalNonce(nonces._1, nonces._2)
  }
}
