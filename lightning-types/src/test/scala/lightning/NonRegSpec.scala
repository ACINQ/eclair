package lightning

import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

/**
  * Created by PM on 06/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class NonRegSpec extends FunSuite {

  test("check signature ToString extensions") {
    val sig = signature(1, 2, 3, 4, 5, 6, 7, 8)
    assert(sig.isInstanceOf[SignatureToString])
  }
}
