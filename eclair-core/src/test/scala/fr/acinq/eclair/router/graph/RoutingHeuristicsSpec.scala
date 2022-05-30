package fr.acinq.eclair.router.graph

import org.scalatest.funsuite.AnyFunSuite
import RoutingHeuristics.normalize

class RoutingHeuristicsSpec extends AnyFunSuite {

  test("normalize when value in range") {
    assert(normalize(value = 10, min = 0, max = 100) == 0.1)
    assert(normalize(value = 20, min = 10, max = 200) == 0.05263157894736842)
    assert(normalize(value = -11, min = -100, max = -10) == 0.9888888888888889)
  }

  test("normalize when value is == min") {
    assert(normalize(value = 0, min = 0, max = 100) == 1.0E-5)
    assert(normalize(value = 10, min = 10, max = 200) == 1.0E-5)
    assert(normalize(value = -100, min = -100, max = -10) == 1.0E-5)
  }

  test("normalize when value is < min") {
    assert(normalize(value = -1, min = 0, max = 100) == 1.0E-5)
    assert(normalize(value = 9.1, min = 10, max = 200) == 1.0E-5)
    assert(normalize(value = -101.1, min = -100, max = -10) == 1.0E-5)
  }

  test("normalize when value is == max") {
    assert(normalize(value = 100, min = 0, max = 100) == 1.0)
    assert(normalize(value = 200, min = 10, max = 200) == 1.0)
    assert(normalize(value = -10, min = -100, max = -10) == 1.0)
  }

  test("normalize when value is > max") {
    assert(normalize(value = 105.2, min = 0, max = 100) == 0.99999)
    assert(normalize(value = 300, min = 10, max = 200) == 0.99999)
    assert(normalize(value = -9, min = -100, max = -10) == 0.99999)
  }

  test("normalize when value is very close to max") {
    assert(normalize(value = 99.999999, min = 0, max = 100) == 0.9999999900000001)
    assert(normalize(value = 199.999999934, min = 10, max = 200) == 0.9999999996526315)
    assert(normalize(value = -10.000000034, min = -100, max = -10) == 0.9999999996222223)
  }

}
