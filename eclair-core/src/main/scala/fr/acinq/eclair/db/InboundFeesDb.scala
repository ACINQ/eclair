package fr.acinq.eclair.db

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.payment.relay.Relayer.InboundFees

/** The PeersDb contains information about our direct peers, with whom we have or had channels. */
trait InboundFeesDb {

  def addOrUpdateInboundFees(nodeId: PublicKey, fees: InboundFees): Unit

  def getInboundFees(nodeId: PublicKey): Option[InboundFees]

}
