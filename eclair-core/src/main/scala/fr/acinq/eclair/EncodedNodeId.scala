package fr.acinq.eclair

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey

sealed trait EncodedNodeId

object EncodedNodeId {
  case class Plain(publicKey: PublicKey) extends EncodedNodeId {
    override def toString: String = publicKey.toString
  }

  case class ShortChannelIdDir(isNode1: Boolean, scid: RealShortChannelId) extends EncodedNodeId {
    override def toString: String = if (isNode1) s"<-$scid" else s"$scid->"
  }

  def apply(publicKey: PublicKey): EncodedNodeId = Plain(publicKey)
}
