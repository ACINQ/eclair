package fr.acinq.eclair

import fr.acinq.bitcoin.BinaryData
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class UInt64Spec extends FunSuite {
  test("encode value that are > 2^63 - 1") {
    val a = UInt64("0xffffffffffffffff")
    val b = UInt64("0xfffffffffffffffe")
    assert(a > b)
    assert(b < a)
    assert(a == a)
    assert(a.toBin === BinaryData("0xffffffffffffffff"))
    assert(a.toString === "UInt64(18446744073709551615)")
    assert(b.toBin === BinaryData("0xfffffffffffffffe"))
    assert(b.toString === "UInt64(18446744073709551614)")
  }
}
