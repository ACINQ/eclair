package fr.acinq.eclair.db.sqlite
import com.codahale.metrics.Timer

trait TimeUnit {
  def timeUnit(timer: Timer, f: => Unit) = {
    val context = timer.time()
    try {
        f
    } finally {
        context.stop();
    }
  }
}