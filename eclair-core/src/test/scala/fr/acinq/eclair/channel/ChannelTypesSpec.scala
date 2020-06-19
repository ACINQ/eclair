package fr.acinq.eclair.channel

import org.scalatest.funsuite.AnyFunSuite

class ChannelTypesSpec extends AnyFunSuite {
  test("standard channel features include deterministic channel key path") {
    assert(!ChannelVersion.ZEROES.hasPubkeyKeyPath)
    assert(ChannelVersion.STANDARD.hasPubkeyKeyPath)
    assert(ChannelVersion.STATIC_REMOTEKEY.hasStaticRemotekey)
    assert(ChannelVersion.STATIC_REMOTEKEY.hasPubkeyKeyPath)
  }
}
