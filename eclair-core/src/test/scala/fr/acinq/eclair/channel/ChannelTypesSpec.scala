package fr.acinq.eclair.channel

import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._

class ChannelTypesSpec extends AnyFunSuite {
  test("standard channel features include deterministic channel key path") {
    assert(!ChannelVersion.ZEROES.hasPubkeyKeyPath)
    assert(ChannelVersion.STANDARD.hasPubkeyKeyPath)
    assert(ChannelVersion.STATIC_REMOTEKEY.hasStaticRemotekey)
    assert(ChannelVersion.STATIC_REMOTEKEY.hasPubkeyKeyPath)
  }

  test("channel version") {
    assert(ChannelVersion.STANDARD.bits === bin"00000000 00000000 00000000 00000001") // USE_PUBKEY_KEYPATH_BIT is enabled by default
    assert((ChannelVersion.STANDARD | ChannelVersion.ZERO_RESERVE).bits === bin"00000000 00000000 00000000 00001001")

    assert(ChannelVersion.STANDARD.hasZeroReserve === false)
    assert((ChannelVersion.STANDARD | ChannelVersion.ZERO_RESERVE).hasZeroReserve === true)
  }
}
