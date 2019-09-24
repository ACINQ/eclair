package fr.acinq.eclair.channel

import org.scalatest.FunSuite

class ChannelTypesSpec extends FunSuite {
  test("standard channel features include deterministic channel key path") {
    assert(ChannelVersion.STANDARD.isSet(ChannelVersion.USE_PUBKEY_KEYPATH_BIT))
    assert(!ChannelVersion.ZEROES.isSet(ChannelVersion.USE_PUBKEY_KEYPATH_BIT))
  }

  test("channels using option_static_remotekey also use deterministic key path") {
    assert(ChannelVersion.STATIC_REMOTEKEY.isSet(ChannelVersion.USE_PUBKEY_KEYPATH_BIT))
  }
}
