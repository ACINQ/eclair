package fr.acinq.eclair.crypto.keymanager

import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.DeterministicWallet.KeyPath
import fr.acinq.eclair.TimestampSecond
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.Descriptors

import scala.util.Try

trait OnchainKeyManager {
  def wallet: String

  /**
   *
   * @param account account number (0 is used by most wallets)
   * @return the onchain pubkey for this account, which can then be imported into a BIP39-compatible wallet such as Electrum
   */
  def getOnchainMasterPubKey(account: Long): String

  /**
   *
   * @param keyPath BIP path
   * @return the (public key, address) pair for this BIP32 path
   */
  def getPublicKey(keyPath: KeyPath): (PublicKey, String)

  /**
   *
   * @return the creation time of the wallet managed by this key manager
   */
  def getWalletTimestamp(): TimestampSecond

  /**
   *
   * @param account account number
   * @return a pair of (main, change) wallet descriptors that can be imported into an onchain wallet
   */
  def getDescriptors(account: Long): Descriptors

  /**
   *
   * @param psbt       input psbt
   * @param ourInputs  index of inputs that belong to our onchain wallet and need to be signed
   * @param ourOutputs index of outputs that belong to our onchain wallet
   * @return a signed psbt, where all our inputs are signed
   */
  def signPsbt(psbt: Psbt, ourInputs: Seq[Int], ourOutputs: Seq[Int]): Try[Psbt]
}
