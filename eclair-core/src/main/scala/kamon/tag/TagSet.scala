package kamon.tag

trait TagSet {
  def withTag(t: String, s: Boolean) = this
  def withTag(a: String, value: Long) = this
  def withTag(a: String, value: String) = this
}
object TagSet extends TagSet {
  def Empty: TagSet = this
  def of(t: String, s: String) = this
}
