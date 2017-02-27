package fr.acinq.eclair.db

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.crypto.TransportHandler

/**
  * Created by fabrice on 25/02/17.
  */

trait SimpleDb {
  // @formatter:off
  def put(k: String, v: BinaryData) : Unit
  def get(k: String) : Option[BinaryData]
  def delete(k: String) : Boolean
  def keys: Seq[String]
  def values: Seq[BinaryData]
  // @formatter:on
}

class SimpleTypedDb[K, V](id2string: K => String, string2id: String => Option[K], serializer: TransportHandler.Serializer[V], db: SimpleDb) {
  // @formatter:off
  def put(k: K, v: V) = db.put(id2string(k), serializer.serialize(v))
  def get(k: K): Option[V] = db.get(id2string(k)).map(serializer.deserialize)
  def delete(k: K) : Boolean = db.delete(id2string(k))
  def keys: Seq[K] = db.keys.map(string2id).flatten
  def values: Seq[V] = keys.map(get).flatten
  // @formatter:on
}

