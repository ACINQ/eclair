package fr.acinq.eclair.api

import fr.acinq.eclair._
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.channel.State
import fr.acinq.eclair.crypto.ShaChain
import lightning.sha256_hash
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

class Sha256Serializer extends CustomSerializer[sha256_hash](format => ( {
  case JString(x) if (false) => // NOT IMPLEMENTED
    ???
}, {
  case x: sha256_hash => JString(sha2562bin(x).toString())
}
  ))

class ShaChainSerializer extends CustomSerializer[ShaChain](format => ( {
  case JString(x) if (false) => // NOT IMPLEMENTED
    ???
}, {
  case x: ShaChain => JNull
}
  ))
