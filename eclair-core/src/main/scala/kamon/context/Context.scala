package kamon.context

object Context {
  def key[T](name: String, emptyValue: T) = Key(name, emptyValue)

  case class Key[T](val name: String, val emptyValue: T)
}
