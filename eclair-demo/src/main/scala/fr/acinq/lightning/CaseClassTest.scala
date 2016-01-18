package fr.acinq.lightning

/**
  * Created by PM on 12/01/2016.
  */
object CaseClassTest extends App {

  trait Closeable {
    def a: String
  }

  case class A(a: String, b: Int) extends Closeable



}
