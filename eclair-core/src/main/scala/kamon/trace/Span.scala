package kamon.trace

trait Span {
  def fail(s: String): Unit = ()
  def fail(s: String, t: AnyRef): Unit = ()
  def finish(): Span
}
