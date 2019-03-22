package fr.acinq.eclair.db

trait Databases {

  def network(): NetworkDb

  def audit(): AuditDb

  def channels(): ChannelsDb

  def peers(): PeersDb

  def payments(): PaymentsDb

  def pendingRelay(): PendingRelayDb

}