package fr.acinq.eclair

import org.scalatest.FunSuite

class PluginSpec extends FunSuite{

  test("compare versions ignoring the prerelease tag") {
    assert(Plugin.versionEquals("0.3.4", "0.3.4-SNAPSHOT"))
    assert(Plugin.versionEquals("0.3.4-SNAPSHOT", "0.3.4-SNAPSHOT"))
    assert(Plugin.versionEquals("0.3.4", "0.3.4"))
  }

}
