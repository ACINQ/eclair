package fr.acinq.eclair.crypto

import fr.acinq.bitcoin.Crypto.{PrivateKey, Scalar}
import fr.acinq.bitcoin.DeterministicWallet._
import fr.acinq.bitcoin.{BinaryData, Crypto, DeterministicWallet}
import DeterministicWallet.derivePrivateKey

object KeyManagement {
  object NodeKeys {
    def masterKey(seed: BinaryData): ExtendedPrivateKey = DeterministicWallet.generate(seed)

    val keyPath = DeterministicWallet.hardened(46) :: DeterministicWallet.hardened(0) :: Nil

    def extendedPrivateKey(master: ExtendedPrivateKey): ExtendedPrivateKey = DeterministicWallet.derivePrivateKey(master, keyPath)

    def extendedPrivateKey(seed: BinaryData): ExtendedPrivateKey = DeterministicWallet.derivePrivateKey(masterKey(seed), keyPath)
  }

  class ChannelKeys(val nodeKey: ExtendedPrivateKey, val channelNumber: Long) {
    val channelKey = derivePrivateKey(nodeKey, channelNumber)
    val fundingKey: PrivateKey = derivePrivateKey(channelKey, 0).privateKey
    val revocationSecret: Scalar = derivePrivateKey(channelKey, 1).privateKey
    val paymentKey: PrivateKey = derivePrivateKey(channelKey, 2).privateKey
    val delayedPaymentKey: PrivateKey = derivePrivateKey(channelKey, 3).privateKey
    val htlcKey: PrivateKey = derivePrivateKey(channelKey, 4).privateKey
    val shaSeed: BinaryData = Crypto.sha256(derivePrivateKey(channelKey, 5).privateKey.toBin)
  }
}
