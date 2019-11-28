package kamon

import kamon.context.Context
import kamon.tag.TagSet
import kamon.trace.Span

/**
 * Kamon does not work on Android and using it would not make sense anyway, we use this simplistic mocks instead
 */
object Kamon {

  object Mock extends Span {
    def start() = this

    def stop() = this

    def finish() = this

    def withoutTags() = this

    def withTags(args: TagSet) = this

    def withTag(args: String*) = this

    def increment() = this

    def decrement() = this

    def record(a: Long) = this

    def tag(a: String, b: Any) = this

    def asChildOf(a: AnyRef) = this
  }

  def timer(name: String) = Mock

  def spanBuilder(name: String) = Mock

  def counter(name: String) = Mock

  def histogram(name: String) = Mock

  def rangeSampler(name: String) = Mock

  def runWithContextEntry[T, K](key: Context.Key[K], value: K)(f: => T): T = f

  def runWithSpan[T](span: Any, finishSpan: Boolean)(f: => T): T = f

  def currentSpan() = Some(Mock)
}
