package fr.acinq.eclair

import fr.acinq.bitcoin.BinaryData

/**
  * Created by PM on 13/02/2017.
  */
object Features {

  def isChannelPublic(localFeatures: BinaryData): Boolean = localFeatures.size >= 1 && localFeatures.data(0) == 0x01

  def requiresInitialRoutingSync(localFeatures: BinaryData): Boolean = localFeatures.size >= 2 && localFeatures.data(1) == 0x01

}
