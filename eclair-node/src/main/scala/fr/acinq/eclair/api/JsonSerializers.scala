package fr.acinq.eclair.api

import fr.acinq.bitcoin.{BinaryData, Script, ScriptElt, Transaction}
import fr.acinq.bitcoin.Crypto.{Point, PrivateKey, PublicKey, Scalar}
import fr.acinq.eclair.channel.State
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.transactions.Transactions.TransactionWithInputInfo
import org.json4s.CustomSerializer
import org.json4s.JsonAST.{JNull, JString}

/**
  * Created by PM on 28/01/2016.
  */
class BinaryDataSerializer extends CustomSerializer[BinaryData](format => ( {
  case JString(hex) if (false) => // NOT IMPLEMENTED
    ???
}, {
  case x: BinaryData => JString(x.toString())
}
))

class StateSerializer extends CustomSerializer[State](format => ( {
  case JString(x) if (false) => // NOT IMPLEMENTED
    ???
}, {
  case x: State => JString(x.toString())
}
))

class ShaChainSerializer extends CustomSerializer[ShaChain](format => ( {
  case JString(x) if (false) => // NOT IMPLEMENTED
    ???
}, {
  case x: ShaChain => JNull
}
))

class PublicKeySerializer extends CustomSerializer[PublicKey](format => ( {
  case JString(x) if (false) => // NOT IMPLEMENTED
    ???
}, {
  case x: PublicKey => JString(x.toString())
}
))

class PrivateKeySerializer extends CustomSerializer[PrivateKey](format => ( {
  case JString(x) if (false) => // NOT IMPLEMENTED
    ???
}, {
  case x: PrivateKey => JString("XXX")
}
))

class PointSerializer extends CustomSerializer[Point](format => ( {
  case JString(x) if (false) => // NOT IMPLEMENTED
    ???
}, {
  case x: Point => JString(x.toString())
}
))

class ScalarSerializer extends CustomSerializer[Scalar](format => ( {
  case JString(x) if (false) => // NOT IMPLEMENTED
    ???
}, {
  case x: Scalar => JString("XXX")
}
))

class TransactionWithInputInfoSerializer extends CustomSerializer[TransactionWithInputInfo](format => ( {
  case JString(x) if (false) => // NOT IMPLEMENTED
    ???
}, {
  case x: TransactionWithInputInfo => JString(Transaction.write(x.tx).toString())
}
))
