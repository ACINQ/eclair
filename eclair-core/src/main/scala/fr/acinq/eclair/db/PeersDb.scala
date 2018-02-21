package fr.acinq.eclair.db

import java.net.InetSocketAddress

import fr.acinq.bitcoin.Crypto.PublicKey

trait PeersDb {

  def addOrUpdatePeer(nodeId: PublicKey, address: InetSocketAddress)

  def removePeer(nodeId: PublicKey)

  def listPeers(): Map[PublicKey, InetSocketAddress]

}
