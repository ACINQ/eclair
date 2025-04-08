package fr.acinq.eclair

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey

/** Identifying information for a remote node, used in blinded paths and onion contents. */
sealed trait EncodedNodeId

object EncodedNodeId {

  def apply(publicKey: PublicKey): EncodedNodeId = WithPublicKey.Plain(publicKey)

  /** For compactness, nodes may be identified by the shortChannelId of one of their public channels. */
  case class ShortChannelIdDir(isNode1: Boolean, scid: RealShortChannelId) extends EncodedNodeId {
    override def toString: String = if (isNode1) s"<-$scid" else s"$scid->"
  }

  // @formatter:off
  sealed trait WithPublicKey extends EncodedNodeId { def publicKey: PublicKey }
  object WithPublicKey {
    /** Standard case where a node is identified by its public key. */
    case class Plain(publicKey: PublicKey) extends WithPublicKey { override def toString: String = publicKey.toString }
    /**
     * Wallet nodes are not part of the public graph, and may not have channels yet.
     * Wallet providers are usually able to contact such nodes using push notifications or similar mechanisms.
     */
    case class Wallet(publicKey: PublicKey) extends WithPublicKey { override def toString: String = publicKey.toString }
  }
  // @formatter:on

}
