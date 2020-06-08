package fr.acinq.eclair.channel

import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._

class ChannelTypesSpec extends AnyFunSuite {
  test("standard channel features include deterministic channel key path") {
    assert(!ChannelVersion.ZEROES.isSet(ChannelVersion.USE_PUBKEY_KEYPATH_BIT))
    assert(ChannelVersion.STANDARD.isSet(ChannelVersion.USE_PUBKEY_KEYPATH_BIT))
    assert(ChannelVersion.STATIC_REMOTEKEY.isSet(ChannelVersion.USE_PUBKEY_KEYPATH_BIT))
  }

  test("channel version") {
    assert(ChannelVersion.STANDARD.bits === bin"00000000 00000000 00000000 00000001") // USE_PUBKEY_KEYPATH_BIT is enabled by default
    assert((ChannelVersion.STANDARD | ChannelVersion.ZERO_RESERVE).bits === bin"00000000 00000000 00000000 00001001")

    assert(ChannelVersion.STANDARD.isSet(ChannelVersion.ZERO_RESERVE_BIT) === false)
    assert((ChannelVersion.STANDARD | ChannelVersion.ZERO_RESERVE).isSet(ChannelVersion.ZERO_RESERVE_BIT) === true)
  }
}
