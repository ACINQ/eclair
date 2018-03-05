package fr.acinq.eclair

import fr.acinq.bitcoin.BinaryData
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner


@RunWith(classOf[JUnitRunner])
class UInt64Spec extends FunSuite {

  test("handle values from 0 to 2^63-1") {
    val a = UInt64("0xffffffffffffffff")
    val b = UInt64("0xfffffffffffffffe")
    val c = UInt64(42)
    val z = UInt64(0)
    assert(a > b)
    assert(b < a)
    assert(z < a && z < b && z < c)
    assert(a == a)
    assert(BinaryData(a.toByteArray) === BinaryData("0xffffffffffffffff"))
    assert(a.toString === "18446744073709551615")
    assert(BinaryData(b.toByteArray) === BinaryData("0xfffffffffffffffe"))
    assert(b.toString === "18446744073709551614")
    assert(BinaryData(c.toByteArray) === BinaryData("0x2a"))
    assert(c.toString === "42")
    assert(BinaryData(z.toByteArray) === BinaryData("0x00"))
    assert(z.toString === "0")
  }

}