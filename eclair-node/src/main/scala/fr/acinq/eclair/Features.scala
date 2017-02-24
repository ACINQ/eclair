package fr.acinq.eclair


import java.util.BitSet

import fr.acinq.bitcoin.BinaryData


/**
  * Created by PM on 13/02/2017.
  */
object Features {

  // @formatter:off
  sealed trait FeatureFlag
  case object Unset extends FeatureFlag
  case object Mandatory extends FeatureFlag
  case object Optional extends FeatureFlag
  // @formatter:on

  def readFeature(features: BinaryData, index: Int): FeatureFlag = {
    require(index % 2 == 0, "feature index must be even")
    val mandatoryBit = index
    val optionalBit = index + 1
    val bitset = BitSet.valueOf(features)
    require(!(bitset.get(mandatoryBit) && bitset.get(optionalBit)), s"both feature bits are set for feature index=$index")
    if (bitset.get(mandatoryBit)) Mandatory else if (bitset.get(optionalBit)) Optional else Unset
  }

  def channelPublic(localFeatures: BinaryData): FeatureFlag = readFeature(localFeatures, 0)

  def initialRoutingSync(localFeatures: BinaryData): FeatureFlag = readFeature(localFeatures, 2)

  def areFeaturesCompatible(localLocalFeatures: BinaryData, remoteLocalFeatures: BinaryData): Boolean = {
    val localChannelPublic = Features.channelPublic(localLocalFeatures)
    val remoteChannelPublic = Features.channelPublic(remoteLocalFeatures)

    if ((localChannelPublic == Mandatory && remoteChannelPublic == Unset) || (localChannelPublic == Unset && remoteChannelPublic == Mandatory)) false else true
  }

}
