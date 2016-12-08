package fr.acinq.eclair.crypto

import java.math.BigInteger

import fr.acinq.bitcoin.{BinaryData, Crypto}
import org.bouncycastle.math.ec.ECPoint

/**
  * Created by PM on 07/12/2016.
  */
object Generators {

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

  def perCommitSecret(seed: BinaryData, index: Int): Scalar = ???

  def perCommitPoint(perCommitSecret: Scalar): Point = ???

  def perCommitPoint(seed: BinaryData, index: Int): Point = perCommitPoint(perCommitSecret(seed, index))


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
