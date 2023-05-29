package fr.acinq.eclair.crypto.keymanager

import fr.acinq.bitcoin.psbt.Psbt

trait OnchainKeyManager {
  /**
   *
   * @param account account number (0 is used by most wallets)
   * @return the onchain pubkey for this account, which can then be imported into a BIP39-compatible wallet such as Electrum
   */
  def getOnchainMasterPubKey(account: Long): String

  def getOnchainMasterMasterFingerprint: Long

  def getOnchainMasterMasterFingerprintHex = String.format("%8s", getOnchainMasterMasterFingerprint.toHexString).replace(' ', '0')

  /**
   *
   * @param fingerprint onchain wallet fingerprint
   * @param chain_opt   chain hash
   * @param account     account number
   * @return a pair of (main, change) wallet descriptors that can be imported into an onchain wallet
   */
  def getDescriptors(fingerprint: Long, chain_opt: Option[String], account: Long): (List[String], List[String])

  /**
   *
   * @param psbt       input psbt
   * @param ourInputs  index of inputs that belong to our onchain wallet and need to be signed
   * @param ourOutputs index of outputs that belong to our onchain wallet
   * @return a signed psbt, where all our inputs are signed
   */
  def signPsbt(psbt: Psbt, ourInputs: Seq[Int], ourOutputs: Seq[Int]): Psbt
}
