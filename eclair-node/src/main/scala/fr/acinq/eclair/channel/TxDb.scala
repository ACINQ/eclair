package fr.acinq.eclair.channel

import fr.acinq.bitcoin.{BinaryData, Transaction}

/**
  * Created by PM on 06/12/2016.
  */
trait TxDb {
  def add(parentId: BinaryData, spending: Transaction): Unit

  def get(parentId: BinaryData): Option[Transaction]
}

class BasicTxDb extends TxDb {
  val db = collection.mutable.HashMap.empty[BinaryData, Transaction]

  override def add(parentId: BinaryData, spending: Transaction): Unit = {
    db += parentId -> spending
  }

  override def get(parentId: BinaryData): Option[Transaction] = db.get(parentId)
}
