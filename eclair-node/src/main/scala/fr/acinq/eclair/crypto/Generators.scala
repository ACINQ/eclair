package fr.acinq.eclair.crypto

import fr.acinq.bitcoin.Crypto.{Point, PrivateKey, PublicKey, Scalar}
import fr.acinq.bitcoin.{BinaryData, Crypto}

/**
  * Created by PM on 07/12/2016.
  */
object Generators {

  def fixSize(data: BinaryData): BinaryData = data.length match {
    case 32 => data
    case length if length < 32 => Array.fill(32 - length)(0.toByte) ++ data
  }

  def perCommitSecret(seed: BinaryData, index: Long): Scalar = Scalar(ShaChain.shaChainFromSeed(seed, 0xFFFFFFFFFFFFFFFFL - index))

  def perCommitPoint(seed: BinaryData, index: Long): Point = perCommitSecret(seed, index).toPoint

  def derivePrivKey(secret: Scalar, perCommitPoint: Point): PrivateKey = {
    // secretkey = basepoint-secret + SHA256(per-commitment-point || basepoint)
    PrivateKey(secret.add(Scalar(Crypto.sha256(perCommitPoint.toBin(true) ++ secret.toPoint.toBin(true)))), compressed = true)
  }

  def derivePubKey(basePoint: Point, perCommitPoint: Point): PublicKey = {
    //pubkey = basepoint + SHA256(per-commitment-point || basepoint)*G
    val a = Scalar(Crypto.sha256(perCommitPoint.toBin(true) ++ basePoint.toBin(true)))
    PublicKey(basePoint.add(a.toPoint), compressed = true)
  }

  def revocationPubKey(basePoint: Point, perCommitPoint: Point): PublicKey = {
    val a = Scalar(Crypto.sha256(basePoint.toBin(true) ++ perCommitPoint.toBin(true)))
    val b = Scalar(Crypto.sha256(perCommitPoint.toBin(true) ++ basePoint.toBin(true)))
    PublicKey(basePoint.multiply(a).add(perCommitPoint.multiply(b)), compressed = true)
  }

  def revocationPrivKey(secret: Scalar, perCommitSecret: Scalar): PrivateKey = {
    val a = Scalar(Crypto.sha256(secret.toPoint.toBin(true) ++ perCommitSecret.toPoint.toBin(true)))
    val b = Scalar(Crypto.sha256(perCommitSecret.toPoint.toBin(true) ++ secret.toPoint.toBin(true)))
    PrivateKey(secret.multiply(a).add(perCommitSecret.multiply(b)), compressed = true)
  }

}
