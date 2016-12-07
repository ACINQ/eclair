package fr.acinq.eclair.crypto

import fr.acinq.bitcoin.BinaryData

/**
  * Created by PM on 07/12/2016.
  */
object Generators {

  type Point = BinaryData
  type Scalar = BinaryData

  def perCommitSecret(seed: BinaryData, index: Int): Scalar = ???

  def perCommitPoint(perCommitSecret: Scalar): Point = ???

  def perCommitPoint(seed: BinaryData, index: Int): Point = perCommitPoint(perCommitSecret(seed, index))

  def revocationPubKey(revocationBasePoint: Point, perCommitPoint: Point): Point = ???

  def revocationPrivKey(revocationSecret: Scalar, perCommitSecret: Scalar): Scalar = ???

  def paymentPubKey(paymentBasePoint: Point, perCommitPoint: Point): Point = ???

  def paymentPrivKey(paymentSecret: Scalar, perCommitSecret: Scalar): Scalar = ???

  def delayedPaymentPubKey(delayedPaymentBasePoint: Point, perCommitPoint: Point): Point = ???

  def delayedPaymentPrivKey(delayedPaymentSecret: Scalar, perCommitSecret: Scalar): Scalar = ???

}
