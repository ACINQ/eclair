package fr.acinq.eclair.db

import fr.acinq.bitcoin.BinaryData

/**
  * Created by fabrice on 20/02/17.
  */
class DummyDb extends SimpleDb {
  val map = collection.mutable.HashMap.empty[String, BinaryData]

  override def put(key: String, value: BinaryData): Unit = map.put(key, value)

  override def get(key: String): Option[BinaryData] = map.get(key)

  override def delete(key: String): Boolean = map.remove(key).isDefined

  override def keys: Seq[String] = map.keys.toSeq

  override def values: Seq[BinaryData] = map.values.toSeq
}