package kamon.metric

trait Counter {
  def increment(): Counter

  def increment(a: Int): Counter

  def decrement(): Counter
}
