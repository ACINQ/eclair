package fr.acinq.eclair.crypto

import fr.acinq.bitcoin.{BinaryData, Crypto, DeterministicWallet}
import fr.acinq.bitcoin.Crypto.{Point, PublicKey, Scalar}
import fr.acinq.bitcoin.DeterministicWallet.ExtendedPublicKey
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.transactions.Transactions.TransactionWithInputInfo

trait KeyManager {
  def nodeKey: DeterministicWallet.ExtendedPrivateKey

  def nodeId: PublicKey

  def fundingPublicKey(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPublicKey

  def revocationPoint(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPublicKey

  def paymentPoint(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPublicKey

  def delayedPaymentPoint(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPublicKey

  def htlcPoint(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPublicKey

  def commitmentSecret(channelKeyPath: DeterministicWallet.KeyPath, index: Long): Crypto.Scalar

  def commitmentPoint(channelKeyPath: DeterministicWallet.KeyPath, index: Long): Crypto.Point

  /**
    *
    * @param tx        input transaction
    * @param publicKey extended public key
    * @return a signature generated with the private key that matches the input
    *         extended public key
    */
  def sign(tx: TransactionWithInputInfo, publicKey: ExtendedPublicKey): BinaryData

  /**
    * This method is used to spend funds send to htlc keys/delayed keys
    *
    * @param tx          input transaction
    * @param publicKey   extended public key
    * @param remotePoint remote point
    * @return a signature generated with a private key generated from the input keys's matching
    *         private key and the remote point.
    */
  def sign(tx: TransactionWithInputInfo, publicKey: ExtendedPublicKey, remotePoint: Point): BinaryData

  /**
    * Ths method is used to spend revoked transactions, with the corresponding revocation key
    *
    * @param tx           input transaction
    * @param publicKey    extended public key
    * @param remoteSecret remote secret
    * @return a signature generated with a private key generated from the input keys's matching
    *         private key and the remote secret.
    */
  def sign(tx: TransactionWithInputInfo, publicKey: ExtendedPublicKey, remoteSecret: Scalar): BinaryData

  def signChannelAnnouncement(channelKeyPath: DeterministicWallet.KeyPath, chainHash: BinaryData, shortChannelId: ShortChannelId, remoteNodeId: PublicKey, remoteFundingKey: PublicKey, features: BinaryData): (BinaryData, BinaryData)
}
