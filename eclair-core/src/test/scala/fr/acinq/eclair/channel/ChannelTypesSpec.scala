package fr.acinq.eclair.channel

import org.scalatest.funsuite.AnyFunSuite

class ChannelTypesSpec extends AnyFunSuite {
  test("standard channel features include deterministic channel key path") {
    assert(!ChannelVersion.ZEROES.isSet(ChannelVersion.USE_PUBKEY_KEYPATH_BIT))
    assert(ChannelVersion.STANDARD.isSet(ChannelVersion.USE_PUBKEY_KEYPATH_BIT))
    assert(ChannelVersion.STATIC_REMOTEKEY.isSet(ChannelVersion.USE_PUBKEY_KEYPATH_BIT))
  }
}
