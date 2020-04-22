package kamon.metric

trait Timer {
  def start(): Timer
  def stop(): Timer
}
