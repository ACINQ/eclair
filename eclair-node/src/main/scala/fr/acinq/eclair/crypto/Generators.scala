package fr.acinq.eclair.crypto

import fr.acinq.bitcoin.Crypto.{Point, Scalar}
import fr.acinq.bitcoin.{BinaryData, Crypto}

/**
  * Created by PM on 07/12/2016.
  */
object Generators {

  def fixSize(data: BinaryData): BinaryData = data.length match {
    case 32 => data
    case length if length < 32 => Array.fill(32 - length)(0.toByte) ++ data
  }

  def perCommitSecret(seed: BinaryData, index: Int): Scalar = Scalar(ShaChain.shaChainFromSeed(seed, 0xFFFFFFFFFFFFFFFFL - index) :+ 1.toByte)

  def perCommitPoint(seed: BinaryData, index: Int): Point = perCommitSecret(seed, index).toPoint

  def derivePrivKey(secret: Scalar, perCommitPoint: Point): Scalar = {
    // secretkey = basepoint-secret + SHA256(per-commitment-point || basepoint)
    secret.add(Scalar(Crypto.sha256(perCommitPoint.data ++ secret.toPoint.data)))
  }

  def derivePubKey(basePoint: Point, perCommitPoint: Point): Point = {
    //pubkey = basepoint + SHA256(per-commitment-point || basepoint)*G
    val a = Scalar(Crypto.sha256(perCommitPoint.data ++ basePoint.data))
    basePoint.add(a.toPoint)
  }

  def revocationPubKey(basePoint: Point, perCommitPoint: Point): Point = {
    val a = Scalar(Crypto.sha256(basePoint.data ++ perCommitPoint.data))
    val b = Scalar(Crypto.sha256(perCommitPoint.data ++ basePoint.data))
    basePoint.multiply(a).add(perCommitPoint.multiply(b))
  }

  def revocationPrivKey(secret: Scalar, perCommitSecret: Scalar): Scalar = {
    val a = Scalar(Crypto.sha256(secret.toPoint.data ++ perCommitSecret.toPoint.data))
    val b = Scalar(Crypto.sha256(perCommitSecret.toPoint.data ++ secret.toPoint.data))
    secret.multiply(a).add(perCommitSecret.multiply(b))
  }

}
