package fr.acinq.eclair.wire.internal.channel.version3

import fr.acinq.eclair.channel.{ChannelConfigOption, ChannelConfigOptions}
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec.normal
import fr.acinq.eclair.wire.internal.channel.version3.ChannelCodecs3.Codecs.{DATA_NORMAL_Codec, channelConfigCodec}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.HexStringSyntax

class ChannelCodecs3Spec extends AnyFunSuite {

  test("basic serialization test (NORMAL)") {
    val data = normal
    val bin = DATA_NORMAL_Codec.encode(data).require
    val check = DATA_NORMAL_Codec.decodeValue(bin).require
    assert(data.commitments.localCommit.spec === check.commitments.localCommit.spec)
    assert(data === check)
  }

  test("encode/decode channel configuration options") {
    assert(channelConfigCodec.encode(ChannelConfigOptions(Set.empty[ChannelConfigOption])).require.bytes === hex"00")
    assert(channelConfigCodec.decode(hex"00".bits).require.value === ChannelConfigOptions(Set.empty[ChannelConfigOption]))
    assert(channelConfigCodec.decode(hex"01f0".bits).require.value === ChannelConfigOptions(Set.empty[ChannelConfigOption]))
    assert(channelConfigCodec.decode(hex"020000".bits).require.value === ChannelConfigOptions(Set.empty[ChannelConfigOption]))

    assert(channelConfigCodec.encode(ChannelConfigOptions.standard).require.bytes === hex"0101")
    assert(channelConfigCodec.encode(ChannelConfigOptions(ChannelConfigOptions.FundingPubKeyBasedChannelKeyPath)).require.bytes === hex"0101")
    assert(channelConfigCodec.decode(hex"0101".bits).require.value === ChannelConfigOptions(ChannelConfigOptions.FundingPubKeyBasedChannelKeyPath))
    assert(channelConfigCodec.decode(hex"01ff".bits).require.value === ChannelConfigOptions(ChannelConfigOptions.FundingPubKeyBasedChannelKeyPath))
    assert(channelConfigCodec.decode(hex"020001".bits).require.value === ChannelConfigOptions(ChannelConfigOptions.FundingPubKeyBasedChannelKeyPath))
  }

  test("decode all known channel configuration options") {
    import scala.reflect.ClassTag
    import scala.reflect.runtime.universe._
    import scala.reflect.runtime.{universe => runtime}
    val mirror = runtime.runtimeMirror(ClassLoader.getSystemClassLoader)

    def extract[T: TypeTag](container: T)(implicit c: ClassTag[T]): Set[ChannelConfigOption] = {
      typeOf[T].decls.filter(_.isPublic).flatMap(symbol => {
        if (symbol.isTerm && symbol.isModule) {
          mirror.reflectModule(symbol.asModule).instance match {
            case f: ChannelConfigOption => Some(f)
            case _ => None
          }
        } else {
          None
        }
      }).toSet
    }

    val declaredOptions = extract(ChannelConfigOptions)
    assert(declaredOptions.nonEmpty)
    val encoded = channelConfigCodec.encode(ChannelConfigOptions(declaredOptions)).require
    val decoded = channelConfigCodec.decode(encoded).require.value
    assert(decoded.activated === declaredOptions)
  }

}
