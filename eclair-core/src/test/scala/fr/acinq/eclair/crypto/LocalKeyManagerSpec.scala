package fr.acinq.eclair.crypto

import fr.acinq.bitcoin.BinaryData
import fr.acinq.bitcoin.Crypto.PublicKey
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class LocalKeyManagerSpec extends FunSuite {
  test("generate the same node id") {
    // if this test breaks it means that we will generate a different node id  from
    // the same seed, which could be a problem during an upgrade
    val seed = BinaryData("17b086b228025fa8f4416324b6ba2ec36e68570ae2fc3d392520969f2a9d0c1501")
    val keyManager = new LocalKeyManager(seed)
    assert(keyManager.nodeId == PublicKey("02a051267759c3a149e3e72372f4e0c4054ba597ebfd0eda78a2273023667205ee"))
  }
}
