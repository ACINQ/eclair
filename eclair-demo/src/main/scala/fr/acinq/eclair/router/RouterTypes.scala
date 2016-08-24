package fr.acinq.eclair.router

import fr.acinq.bitcoin.BinaryData
import lightning.sha256_hash

/**
  * Created by PM on 10/08/2016.
  */

case class ChannelDesc(id: BinaryData, a: BinaryData, b: BinaryData)

case class ChannelRegister(c: ChannelDesc)

case class ChannelUnregister(c: ChannelDesc)

case class CreatePayment(amountMsat: Int, h: sha256_hash, targetNodeId: BinaryData)

