package fr.acinq.protos

import java.math.BigInteger

import fr.acinq.bitcoin._
import fr.acinq.eclair.channel.Scripts
import org.bouncycastle.math.ec.ECPoint

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

    def point = Point(Crypto.publicKeyFromPrivateKey(data :+ 1.toByte))

    def bigInteger: BigInteger = new BigInteger(1, data)

    def add(scalar: Scalar): Scalar = Scalar(bigInteger.add(scalar.bigInteger))

    def multiply(scalar: Scalar): Scalar = Scalar(bigInteger.multiply(scalar.bigInteger).mod(Crypto.curve.getN))
  }

  object Scalar {
    def apply(value: BigInteger): Scalar = new Scalar(fixSize(value.toByteArray.dropWhile(_ == 0)))
  }

  case class Point(data: BinaryData) {
    require(data.length == 33)

    def ecPoint: ECPoint = Crypto.curve.getCurve.decodePoint(data)

    def add(point: Point): Point = Point(ecPoint.add(point.ecPoint))

    def multiply(scalar: Scalar): Point = Point(ecPoint.multiply(scalar.bigInteger))
  }

  object Point {
    def apply(ecPoint: ECPoint): Point = new Point(ecPoint.getEncoded(true))
  }

  def derivePrivKey(secret: Scalar, perCommitPoint: Point): Scalar = {
    // secretkey = basepoint-secret + SHA256(per-commitment-point || basepoint)
    secret.add(Scalar(Crypto.sha256(perCommitPoint.data ++ secret.point.data)))
  }

  def derivePubKey(basePoint: Point, perCommitPoint: Point): Point = {
    //pubkey = basepoint + SHA256(per-commitment-point || basepoint)*G
    val a = Scalar(Crypto.sha256(perCommitPoint.data ++ basePoint.data))
    Point(basePoint.ecPoint.add(Crypto.curve.getG.multiply(a.bigInteger)))
  }

  def revocationPubKey(basePoint: Point, perCommitPoint: Point): Point = {
    val a = Scalar(Crypto.sha256(basePoint.data ++ perCommitPoint.data))
    val b = Scalar(Crypto.sha256(perCommitPoint.data ++ basePoint.data))
    basePoint.multiply(a).add(perCommitPoint.multiply(b))
  }

  def revocationPrivKey(secret: Scalar, perCommitSecret: Scalar): Scalar = {
    val a = Scalar(Crypto.sha256(secret.point.data ++ perCommitSecret.point.data))
    val b = Scalar(Crypto.sha256(perCommitSecret.point.data ++ secret.point.data))
    secret.multiply(a).add(perCommitSecret.multiply(b))
  }
}
