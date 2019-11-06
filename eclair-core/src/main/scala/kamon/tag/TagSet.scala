package kamon.tag

trait TagSet
object TagSet extends TagSet {
  def Empty = this
  def of(t: String, s: String) = this
}
