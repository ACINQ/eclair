package fr.acinq.eclair.router.graph

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import scodec.bits._

/**
 * Nodes in graphs are identified with public keys
 */
object TestNodeKeys {

  val (a, b, c, d, e, f, g, h) = (
    PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), //a
    PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"), //b
    PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"), //c
    PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c"), //d
    PublicKey(hex"02f38f4e37142cc05df44683a83e22dea608cf4691492829ff4cf99888c5ec2d3a"), //e
    PublicKey(hex"03fc5b91ce2d857f146fd9b986363374ffe04dc143d8bcd6d7664c8873c463cdfc"), //f
    PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"), //g
    PublicKey(hex"03bfddd2253b42fe12edd37f9071a3883830ed61a4bc347eeac63421629cf032b5")  //h
  )
}
