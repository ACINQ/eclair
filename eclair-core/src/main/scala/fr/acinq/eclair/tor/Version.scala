package fr.acinq.eclair.tor

import scala.util.Try

case class Version(value: String) extends Ordered[Version] {
  lazy val parts: Seq[String] = {
    val ps = value.split('.')
    // remove non-numeric symbols at the end of the last number (rc, beta, alpha, etc.)
    ps.update(ps.length - 1, ps.last.split('-').head)
    ps
  }

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