package fr.acinq.eclair.api

import java.net.InetSocketAddress

import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.Crypto.{Point, PrivateKey, PublicKey, Scalar}
import fr.acinq.bitcoin.{BinaryData, OutPoint, Transaction}
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.channel.State
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.transactions.Transactions.TransactionWithInputInfo
import fr.acinq.eclair.wire.Color
import org.json4s.JsonAST.{JNull, JString}
import org.json4s.{CustomKeySerializer, CustomSerializer}

/**
  * JSON Serializers.
  * Note: in general, deserialization does not need to be implemented.
  */
class BinaryDataSerializer extends CustomSerializer[BinaryData](format => ({ null }, {
  case x: BinaryData => JString(x.toString())
}))

class ShortChannelIdSerializer extends CustomSerializer[ShortChannelId](format => ({ null }, {
  case x: ShortChannelId => JString(x.toString())
}))

class StateSerializer extends CustomSerializer[State](format => ({ null }, {
  case x: State => JString(x.toString())
}))

class ShaChainSerializer extends CustomSerializer[ShaChain](format => ({ null }, {
  case x: ShaChain => JNull
}))

class PublicKeySerializer extends CustomSerializer[PublicKey](format => ({ null }, {
  case x: PublicKey => JString(x.toString())
}))

class PrivateKeySerializer extends CustomSerializer[PrivateKey](format => ({ null }, {
  case x: PrivateKey => JString("XXX")
}))

class PointSerializer extends CustomSerializer[Point](format => ({ null }, {
  case x: Point => JString(x.toString())
}))

class ScalarSerializer extends CustomSerializer[Scalar](format => ({ null }, {
  case x: Scalar => JString("XXX")
}))

class TransactionSerializer extends CustomSerializer[TransactionWithInputInfo](ser = format => ({ null }, {
  case x: Transaction => JString(x.toString())
}))

class TransactionWithInputInfoSerializer extends CustomSerializer[TransactionWithInputInfo](ser = format => ({ null }, {
  case x: TransactionWithInputInfo => JString(x.tx.toString())
}))

class InetSocketAddressSerializer extends CustomSerializer[InetSocketAddress](format => ({ null }, {
  case address: InetSocketAddress => JString(HostAndPort.fromParts(address.getHostString, address.getPort).toString)
}))

class OutPointKeySerializer extends CustomKeySerializer[OutPoint](format => ({
  case x: String =>
    val Array(k, v) = x.split(":")
    OutPoint(BinaryData(k), v.toLong)
}, {
  case x: OutPoint => s"${x.hash}:${x.index}"
}))

class ColorSerializer extends CustomSerializer[Color](format => ({ null }, {
  case c: Color => JString(c.toString)
}))
