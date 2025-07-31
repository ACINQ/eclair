package fr.acinq.eclair.crypto

import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Musig2, TxId}
import fr.acinq.eclair.randomBytes32
import fr.acinq.eclair.transactions.Transactions.LocalNonce
import grizzled.slf4j.Logging

object NonceGenerator extends Logging {

  // When using single-funding, we don't have access to the funding tx and remote funding key when creating our first
  // verification nonce, so we use placeholder values instead. Note that this is fixed with dual-funding.
  val dummyFundingTxId: TxId = TxId(ByteVector32.Zeroes)
  val dummyRemoteFundingPubKey: PublicKey = PrivateKey(ByteVector32.One.bytes).publicKey

  /**
   * @return a deterministic nonce used to sign our local commit tx: its public part is sent to our peer.
   */
  def verificationNonce(fundingTxId: TxId, fundingPrivKey: PrivateKey, remoteFundingPubKey: PublicKey, commitIndex: Long): LocalNonce = {
    val nonces = Musig2.generateNonceWithCounter(commitIndex, fundingPrivKey, Seq(fundingPrivKey.publicKey, remoteFundingPubKey), None, Some(fundingTxId.value))
    LocalNonce(nonces._1, nonces._2)
  }

  /**
   * @return a random nonce used to sign our peer's commit tx.
   */
  def signingNonce(localFundingPubKey: PublicKey, remoteFundingPubKey: PublicKey, fundingTxId: TxId): LocalNonce = {
    val sessionId = randomBytes32()
    val nonces = Musig2.generateNonce(sessionId, Right(localFundingPubKey), Seq(localFundingPubKey, remoteFundingPubKey), None, Some(fundingTxId.value))
    LocalNonce(nonces._1, nonces._2)
  }

}
