package fr.acinq.eclair

import kamon.Kamon

import scala.concurrent.{ExecutionContext, Future}

object KamonExt {

  def time[T](name: String)(f: => T) = {
    val timer = Kamon.timer(name).withoutTags().start()
    try {
      f
    } finally {
      timer.stop()
    }
  }

  def timeFuture[T](name: String)(f: => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    val timer = Kamon.timer(name).withoutTags().start()
    val res = f
    res onComplete { case _ => timer.stop }
    res
  }

}
