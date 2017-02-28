package fr.acinq.eclair.db

import java.io.File
import java.nio.file.{Files, Paths}

import fr.acinq.bitcoin.BinaryData
import grizzled.slf4j.Logging

import scala.util.Try

/**
  * Created by fabrice on 25/02/17.
  */
object SimpleFileDb {
  def expandUserHomeDirectory(fileName: String): String = {
    if (fileName == "~") System.getProperty("user.home")
    else if (fileName.startsWith("~/")) System.getProperty("user.home") + fileName.drop(1)
    else fileName
  }
}

case class SimpleFileDb(directory: String) extends SimpleDb with Logging {

  import SimpleFileDb._

  val root = expandUserHomeDirectory(directory)

  new File(root).mkdirs()


  override def put(key: String, value: BinaryData): Unit = {
    logger.debug(s"put $key -> $value")
    Files.write(Paths.get(root, key), value)
  }

  override def get(key: String): Option[BinaryData] = Try(Files.readAllBytes(Paths.get(root, key))).toOption.map(a => BinaryData(a))

  override def delete(key: String): Boolean = Paths.get(root, key).toFile.delete()

  override def keys: Seq[String] = new File(root).list()

  override def values: Seq[BinaryData] = keys.map(get).flatten
}
