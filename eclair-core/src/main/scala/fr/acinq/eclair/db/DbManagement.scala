package fr.acinq.eclair.db

trait DbManagement {
  def dbConfig: DbConfig

  def createTables: Unit

  def dropTables: Unit
}
