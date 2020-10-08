package kamon

import java.util.concurrent.TimeUnit

import kamon.context.Context
import kamon.metric.{Counter, Timer}
import kamon.tag.TagSet
import kamon.trace.Span

/**
 * Kamon does not work on Android and using it would not make sense anyway, we use this simplistic mocks instead
 */
object Kamon {

  object Mock extends Span with Timer with Counter {
    def start() = this

    def stop() = this

    def finish() = this

    def withoutTags() = this

    def withTags(args: TagSet) = this

    def withTags(a: TagSet, b: TagSet, c: Boolean) = this

    def withTag(a: TagSet, b: String) = this

    def withTag(a: TagSet, value: Boolean) = this

    def withTag(a: String, value: Boolean) = this

    def withTag(a: String, value: Int) = this

    def withTag(args: String*) = this

    def increment() = this

    def increment(a: Int) = this

    def decrement() = this

    def record(a: Long) = this

    def record(a: Long, b: TimeUnit) = this

    def tag(a: String, b: Any) = this

    def asChildOf(a: AnyRef) = this

    def update(a: Double) = this
  }

  def timer(name: String) = Mock

  def timer(a: String, b: String) = Mock

  def spanBuilder(name: String) = Mock

  def counter(name: String) = Mock

  def counter(a: String, b: String) = Mock

  def histogram(name: String) = Mock

  def histogram(a: String, b: String) = Mock

  def rangeSampler(name: String) = Mock

  def runWithContextEntry[T, K](key: Context.Key[K], value: K)(f: => T): T = f

  def runWithSpan[T](span: Any, finishSpan: Boolean)(f: => T): T = f

  def currentSpan() = Some(Mock)

  def gauge() = Mock

  def gauge(a: String, args: Any*) = Mock
}
