package fr.acinq.eclair

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey

sealed trait EncodedNodeId

object EncodedNodeId {
  /** Nodes are usually identified by their public key. */
  case class Plain(publicKey: PublicKey) extends EncodedNodeId {
    override def toString: String = publicKey.toString
  }

  /** For compactness, nodes may be identified by the shortChannelId of one of their public channels. */
  case class ShortChannelIdDir(isNode1: Boolean, scid: RealShortChannelId) extends EncodedNodeId {
    override def toString: String = if (isNode1) s"<-$scid" else s"$scid->"
  }

  def apply(publicKey: PublicKey): EncodedNodeId = Plain(publicKey)
}
