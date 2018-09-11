package fr.acinq.eclair.utils

import scala.util.Try

case class Version(value: String) extends Ordered[Version] {
  lazy val parts: Seq[String] = value.split('.')

  override def compare(that: Version): Int = {
    parts
      .zipAll(that.parts, "", "")
      .find { case (a, b) => a != b }
      .map { case (a, b) =>
        (for {
          x <- Try(a.toInt)
          y <- Try(b.toInt)
        } yield x.compare(y))
          .getOrElse(a.compare(b))
      }
      .getOrElse(0)
  }
}
