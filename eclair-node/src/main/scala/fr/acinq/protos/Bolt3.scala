package fr.acinq.protos

import java.math.BigInteger

import fr.acinq.bitcoin._
import fr.acinq.eclair.channel.Scripts

object Bolt3 {
  // TODO: sort tx according to BIP69 (lexicographical ordering)

  def baseSize(tx: Transaction) = Transaction.write(tx, Protocol.PROTOCOL_VERSION | Transaction.SERIALIZE_TRANSACTION_NO_WITNESS).length

  def totalSize(tx: Transaction) = Transaction.write(tx, Protocol.PROTOCOL_VERSION).length

  def weight(tx: Transaction) = 3 * baseSize(tx) + totalSize(tx)

  def fundingScript(pubKey1: BinaryData, pubKey2: BinaryData) = Scripts.multiSig2of2(pubKey1, pubKey2)

  def toLocal(revocationPubKey: BinaryData, toSelfDelay: Long, localDelayedKey: BinaryData) = {
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

  def toRemote(remoteKey: BinaryData) = remoteKey

  def htlcOffered(localKey: BinaryData, remoteKey: BinaryData, paymentHash: BinaryData) = {
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

  def htlcReceived(localKey: BinaryData, remoteKey: BinaryData, paymentHash: BinaryData, lockTime: Long) = {
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

  def htlcSuccessOrTimeout(revocationPubKey: BinaryData, toSelfDelay: Long, localDelayedKey: BinaryData) = {
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


  def fixSize(data: BinaryData): BinaryData = data.length match {
    case 32 => data
    case length if length < 32 => Array.fill(32 - length)(0.toByte) ++ data
  }

  case class Scalar(data: BinaryData) {
    require(data.length == 32)

    def basePoint = BasePoint(Crypto.publicKeyFromPrivateKey(data :+ 1.toByte))

    def add(scalar: Scalar): Scalar = {
      val buffer = new BigInteger(1, data).add(new BigInteger(1, scalar.data)).mod(Crypto.curve.getN).toByteArray
      val buffer1 = fixSize(buffer.dropWhile(_ == 0))
      Scalar(buffer1)
    }

    def multiply(scalar: Scalar): Scalar = {
      val buffer = new BigInteger(1, data).multiply(new BigInteger(1, scalar.data)).mod(Crypto.curve.getN).toByteArray
      val buffer1 = fixSize(buffer.dropWhile(_ == 0))
      Scalar(buffer1)
    }
  }

  case class BasePoint(data: BinaryData) {
    require(data.length == 33)

    def add(point: BasePoint): BasePoint = {
      val local = Crypto.curve.getCurve.decodePoint(data)
      val rhs = Crypto.curve.getCurve.decodePoint(point.data)
      BasePoint(local.add(rhs).getEncoded(true))
    }

    def multiply(scalar: Scalar): BasePoint = {
      val local = Crypto.curve.getCurve.decodePoint(data)
      val point = local.multiply(new BigInteger(1, scalar.data))
      BasePoint(point.getEncoded(true))
    }
  }

  def revocationPubKey(revocationBasePoint: BasePoint, perCommitPoint: BasePoint): BasePoint = {
    val a = Scalar(Crypto.sha256(revocationBasePoint.data ++ perCommitPoint.data))
    val b = Scalar(Crypto.sha256(perCommitPoint.data ++ revocationBasePoint.data))
    revocationBasePoint.multiply(a).add(perCommitPoint.multiply(b))
  }

  def revocationPrivKey(revocationSecret: Scalar, perCommitSecret: Scalar): Scalar = {
    val a = Scalar(Crypto.sha256(revocationSecret.basePoint.data ++ perCommitSecret.basePoint.data))
    val b = Scalar(Crypto.sha256(perCommitSecret.basePoint.data ++ revocationSecret.basePoint.data))
    revocationSecret.multiply(a).add(perCommitSecret.multiply(b))
  }
}
