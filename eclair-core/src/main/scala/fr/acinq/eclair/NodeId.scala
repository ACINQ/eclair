package fr.acinq.eclair

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey

sealed trait NodeId

object NodeId {
  case class Standard(publicKey: PublicKey) extends NodeId

  case class ShortChannelIdDir(isNode1: Boolean, scid: RealShortChannelId) extends NodeId

  def apply(publicKey: PublicKey): NodeId = Standard(publicKey)
}
