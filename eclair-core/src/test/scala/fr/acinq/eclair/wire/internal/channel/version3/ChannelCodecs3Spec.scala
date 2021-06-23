package fr.acinq.eclair.wire.internal.channel.version3

import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec.normal
import fr.acinq.eclair.wire.internal.channel.version3.ChannelCodecs3.Codecs.DATA_NORMAL_Codec
import org.scalatest.funsuite.AnyFunSuite

class ChannelCodecs3Spec extends AnyFunSuite {

  test("basic serialization test (NORMAL)") {
    val data = normal
    val bin = DATA_NORMAL_Codec.encode(data).require
    val check = DATA_NORMAL_Codec.decodeValue(bin).require
    assert(data.commitments.localCommit.spec === check.commitments.localCommit.spec)
    assert(data === check)
  }

}
