package fr.acinq.eclair.api

import java.net.InetSocketAddress

import fr.acinq.bitcoin.Crypto.{Point, PrivateKey, PublicKey, Scalar}
import fr.acinq.bitcoin.{BinaryData, OutPoint}
import fr.acinq.eclair.channel.State
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.transactions.Transactions.TransactionWithInputInfo
import fr.acinq.eclair.wire.Color
import org.json4s.JsonAST.{JNull, JString}
import org.json4s.{CustomKeySerializer, CustomSerializer}

/**
  * Created by PM on 28/01/2016.
  */
class BinaryDataSerializer extends CustomSerializer[BinaryData](format => ({
  case JString(hex) if (false) => // NOT IMPLEMENTED
    ???
}, {
  case x: BinaryData => JString(x.toString())
}
))

class StateSerializer extends CustomSerializer[State](format => ({
  case JString(x) if (false) => // NOT IMPLEMENTED
    ???
}, {
  case x: State => JString(x.toString())
}
))

class ShaChainSerializer extends CustomSerializer[ShaChain](format => ({
  case JString(x) if (false) => // NOT IMPLEMENTED
    ???
}, {
  case x: ShaChain => JNull
}
))

class PublicKeySerializer extends CustomSerializer[PublicKey](format => ({
  case JString(x) if (false) => // NOT IMPLEMENTED
    ???
}, {
  case x: PublicKey => JString(x.toString())
}
))

class PrivateKeySerializer extends CustomSerializer[PrivateKey](format => ({
  case JString(x) if (false) => // NOT IMPLEMENTED
    ???
}, {
  case x: PrivateKey => JString("XXX")
}
))

class PointSerializer extends CustomSerializer[Point](format => ({
  case JString(x) if (false) => // NOT IMPLEMENTED
    ???
}, {
  case x: Point => JString(x.toString())
}
))

class ScalarSerializer extends CustomSerializer[Scalar](format => ({
  case JString(x) if (false) => // NOT IMPLEMENTED
    ???
}, {
  case x: Scalar => JString("XXX")
}
))

class TransactionWithInputInfoSerializer extends CustomSerializer[TransactionWithInputInfo](format => ({
  case JString(x) if (false) => // NOT IMPLEMENTED
    ???
}, {
  case x: TransactionWithInputInfo => JString(x.tx.toString())
}
))

class InetSocketAddressSerializer extends CustomSerializer[InetSocketAddress](format => ({
  case JString(x) if (false) => // NOT IMPLEMENTED
    ???
}, {
  case x: InetSocketAddress => JString(s"${x.getHostString}:${x.getPort}")
}
))

class OutPointKeySerializer extends CustomKeySerializer[OutPoint](format => ({
  case x: String =>
    val Array(k, v) = x.split(":")
    OutPoint(BinaryData(k), v.toLong)
}, {
  case x: OutPoint => s"${x.hash}:${x.index}"
}
))

class ColorSerializer extends CustomSerializer[Color](format => ({
  case JString(x) if (false) => // NOT IMPLEMENTED
    ???
}, {
  case c: Color => JString(c.toString)
}))
