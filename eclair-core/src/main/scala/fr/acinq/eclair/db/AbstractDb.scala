package fr.acinq.eclair.db

trait AbstractDb {

  def network(): NetworkDb

  def audit(): AuditDb

  def channels(): ChannelsDb

  def peers(): PeersDb

  def payments(): PaymentsDb

  def pendingRelay(): PendingRelayDb

}