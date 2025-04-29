package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.crypto.keymanager.LocalCommitmentKeys
import fr.acinq.eclair.transactions.Scripts.{RipemdOfPaymentHash, extractHtlcInfoFromHtlcOfferedScript, extractHtlcInfoFromHtlcReceived, htlcOffered, htlcReceived}
import fr.acinq.eclair.transactions.Transactions.{DefaultCommitmentFormat, UnsafeLegacyAnchorOutputsCommitmentFormat, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat}
import fr.acinq.eclair.{CltvExpiry, randomKey}
import org.scalatest.funsuite.AnyFunSuite

class ScriptsSpec extends AnyFunSuite {
  private val localRevocationPriv = randomKey()
  private val localPaymentBasePoint = randomKey().publicKey
  private val localDelayedPaymentPriv = randomKey()
  private val remotePaymentPriv = randomKey()
  private val localHtlcPriv = randomKey()
  private val remoteHtlcPriv = randomKey()

  private val localKeys = LocalCommitmentKeys(
    ourDelayedPaymentKey = localDelayedPaymentPriv,
    theirPaymentPublicKey = remotePaymentPriv.publicKey,
    ourPaymentBasePoint = localPaymentBasePoint,
    ourHtlcKey = localHtlcPriv,
    theirHtlcPublicKey = remoteHtlcPriv.publicKey,
    revocationPublicKey = localRevocationPriv.publicKey,
  )

  test("extract payment hash from offered HTLC script") {
    val paymentHash = ByteVector32.fromValidHex("01" * 32)
    val expected = RipemdOfPaymentHash(paymentHash)
    assert(extractHtlcInfoFromHtlcOfferedScript(htlcOffered(localKeys.publicKeys, paymentHash, DefaultCommitmentFormat)).contains(expected))
    assert(extractHtlcInfoFromHtlcOfferedScript(htlcOffered(localKeys.publicKeys, paymentHash, UnsafeLegacyAnchorOutputsCommitmentFormat)).contains(expected))
    assert(extractHtlcInfoFromHtlcOfferedScript(htlcOffered(localKeys.publicKeys, paymentHash, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)).contains(expected))
  }

  test("extract payment hash and expiry from received HTLC script") {
    val expiry = CltvExpiry(420000)
    val paymentHash = ByteVector32.fromValidHex("01" * 32)
    val expected = (RipemdOfPaymentHash(paymentHash), expiry)
    assert(extractHtlcInfoFromHtlcReceived(htlcReceived(localKeys.publicKeys, paymentHash, expiry, DefaultCommitmentFormat)).contains(expected))
    assert(extractHtlcInfoFromHtlcReceived(htlcReceived(localKeys.publicKeys, paymentHash, expiry, UnsafeLegacyAnchorOutputsCommitmentFormat)).contains(expected))
    assert(extractHtlcInfoFromHtlcReceived(htlcReceived(localKeys.publicKeys, paymentHash, expiry, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)).contains(expected))
  }
}
