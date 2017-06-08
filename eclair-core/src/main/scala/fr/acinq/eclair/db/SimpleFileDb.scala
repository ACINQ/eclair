package fr.acinq.eclair.db

import java.io.File
import java.nio.file.Files

import fr.acinq.bitcoin.BinaryData
import grizzled.slf4j.Logging

import scala.util.Try

/**
  * Created by fabrice on 25/02/17.
  */
case class SimpleFileDb(root: File) extends SimpleDb with Logging {

  root.mkdirs()

  override def put(key: String, value: BinaryData): Unit = {
    logger.debug(s"put $key -> $value")
    Files.write(new File(root, key).toPath, value)
  }

  override def get(key: String): Option[BinaryData] = Try(Files.readAllBytes(new File(root, key).toPath)).toOption.map(a => BinaryData(a))

  override def delete(key: String): Boolean = new File(root, key).delete()

  override def keys: Seq[String] = root.list()

  override def values: Seq[BinaryData] = keys.map(get).flatten
}
