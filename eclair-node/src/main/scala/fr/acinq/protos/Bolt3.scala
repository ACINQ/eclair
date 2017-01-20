package fr.acinq.protos

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin._
import fr.acinq.eclair.transactions.Scripts

object Bolt3 extends App {
  // TODO: sort tx according to BIP69 (lexicographical ordering)

  def baseSize(tx: Transaction) = Transaction.write(tx, Protocol.PROTOCOL_VERSION | Transaction.SERIALIZE_TRANSACTION_NO_WITNESS).length

  def totalSize(tx: Transaction) = Transaction.write(tx, Protocol.PROTOCOL_VERSION).length

  def weight(tx: Transaction) = 3 * baseSize(tx) + totalSize(tx)

  def fundingScript(pubKey1: PublicKey, pubKey2: PublicKey) = Scripts.multiSig2of2(pubKey1, pubKey2)

  def toLocal(revocationPubKey: PublicKey, toSelfDelay: Long, localDelayedKey: PublicKey) = {
    // @formatter:off
    OP_IF ::
      OP_PUSHDATA(revocationPubKey) ::
    OP_ELSE ::
      OP_PUSHDATA(Script.encodeNumber(toSelfDelay)) :: OP_CHECKSEQUENCEVERIFY :: OP_DROP ::
      OP_PUSHDATA(localDelayedKey) ::
    OP_ENDIF ::
    OP_CHECKSIG :: Nil
    // @formatter:on
  }

  def toRemote(remoteKey: PublicKey) = remoteKey

  def htlcOffered(localKey: PublicKey, remoteKey: PublicKey, paymentHash: BinaryData) = {
    // @formatter:off
    OP_PUSHDATA(remoteKey) :: OP_SWAP ::
    OP_SIZE :: OP_PUSHDATA(Script.encodeNumber(32)) :: OP_EQUAL ::
    OP_NOTIF ::
      OP_DROP :: OP_2 :: OP_SWAP :: OP_PUSHDATA(localKey) :: OP_2 :: OP_CHECKMULTISIG ::
    OP_ELSE ::
      OP_HASH160 :: OP_PUSHDATA(paymentHash) :: OP_EQUALVERIFY ::
      OP_CHECKSIG ::
    OP_ENDIF :: Nil
    // @formatter:on
  }

  def htlcReceived(localKey: PublicKey, remoteKey: PublicKey, paymentHash: BinaryData, lockTime: Long) = {
    // @formatter:off
    OP_PUSHDATA(remoteKey) :: OP_SWAP ::
    OP_SIZE :: OP_PUSHDATA(Script.encodeNumber(32)) :: OP_EQUAL ::
    OP_IF ::
      OP_HASH160 :: OP_PUSHDATA(paymentHash) :: OP_EQUALVERIFY ::
      OP_2 :: OP_SWAP :: OP_PUSHDATA(localKey) :: OP_2 :: OP_CHECKMULTISIG ::
    OP_ELSE ::
      OP_DROP :: OP_PUSHDATA(Script.encodeNumber(lockTime)) :: OP_CHECKLOCKTIMEVERIFY :: OP_DROP :: OP_CHECKSIG ::
    OP_ENDIF :: Nil
    // @formatter:on
  }

  def htlcSuccessOrTimeout(revocationPubKey: PublicKey, toSelfDelay: Long, localDelayedKey: PublicKey) = {
    // @formatter:off
    OP_IF ::
      OP_PUSHDATA(revocationPubKey) ::
    OP_ELSE ::
      OP_PUSHDATA(Script.encodeNumber(toSelfDelay)) :: OP_CHECKSEQUENCEVERIFY :: OP_DROP ::
      OP_PUSHDATA(localDelayedKey) ::
    OP_ENDIF ::
    OP_CHECKSIG :: Nil
    // @formatter:on
  }

  val htlcTimeoutWeight = 634

  val htlcSuccessWeight = 671

  def weight2fee(feeRatePerKw: Int, weight: Int) = Satoshi((feeRatePerKw * weight) / 1024)

  def htlcTimeoutFee(feeRatePerKw: Int): Satoshi = weight2fee(feeRatePerKw, htlcTimeoutWeight)

  def htlcSuccessFee(feeRatePerKw: Int): Satoshi = weight2fee(feeRatePerKw, htlcSuccessWeight)

  def commitTxFee(feeRatePerKw: Int, dustLimit: Satoshi, htlcOffered: Seq[MilliSatoshi], htlcReceived: Seq[MilliSatoshi]): Satoshi = {
    println(s"HTLC timeout tx fee: ${htlcTimeoutFee(feeRatePerKw)}")
    println(s"HTLC success tx fee: ${htlcSuccessFee(feeRatePerKw)}")
    val (weight, fee) = htlcOffered.foldLeft((724, 0 satoshi)) {
      case ((weight, fee), amount) if millisatoshi2satoshi(amount).compare(dustLimit + htlcTimeoutFee(feeRatePerKw)) < 0 =>
        println(s"offered htlc amount $amount is too small")
        (weight, fee + amount)
      case ((weight, fee), _) => (weight + 172, fee)
    }
    val (weight1, fee1) = htlcReceived.foldLeft((weight, fee)) {
      case ((weight, fee), amount) if millisatoshi2satoshi(amount).compare(dustLimit + htlcSuccessFee(feeRatePerKw)) < 0 =>
        println(s"received htlc amount $amount is too small")
        (weight, fee + amount)
      case ((weight, fee), _) => (weight + 172, fee)
    }
    weight2fee(feeRatePerKw, weight1) + fee
  }

  println(s"HTLC timeout fee: ")
  val fee = commitTxFee(5000, 546 satoshi, Seq(5000 satoshi, 1000 satoshi), Seq(7000 satoshi, 800 satoshi))
  println(fee)
}
