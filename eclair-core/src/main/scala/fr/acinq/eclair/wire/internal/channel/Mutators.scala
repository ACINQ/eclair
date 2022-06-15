package fr.acinq.eclair.wire.internal.channel

import fr.acinq.bitcoin.scalacompat.{ByteVector32, Transaction}
import fr.acinq.eclair.channel.{ClosingFeerates, Commitments, RealScidStatus, ShortIds}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{Alias, BlockHeight, RealShortChannelId}
import shapeless.{::, HNil}

/**
 * This class contains mutators for [[shapeless.HList]] that are meant to be stacked to account for incremental minor
 * changes to classes.
 *
 * Usage:
 * {{{
 *   def codec: Codec[MyClass] = (
 *   // original codec
 *   ...
 *   )
 *   .map(mutator1)
 *   .map(mutator2)
 *   .map(mutator3)
 *   .decodeOnly
 *   .as[MyClass]
 * }}}
 */
object Mutators {

  object AddLocalAlias {
    def genDeterministicAlias(channelId: ByteVector32): Alias = Alias(channelId.take(7).toLong(signed = false))

    def mutateDataWaitForFundingConfirmed: Commitments :: Option[Transaction] :: BlockHeight :: Option[ChannelReady] :: Either[FundingCreated, FundingSigned] :: HNil =>
      Commitments :: Option[Transaction] :: BlockHeight :: Alias :: Option[ChannelReady] :: Either[FundingCreated, FundingSigned] :: HNil = {
      case commitments :: fundingTx :: waitingSince :: deferred :: lastSent :: HNil =>
        commitments :: fundingTx :: waitingSince :: AddLocalAlias.genDeterministicAlias(commitments.channelId) :: deferred :: lastSent :: HNil
    }

    def mutateDataWaitForChannelReady: Commitments :: RealShortChannelId :: ChannelReady :: HNil =>
      Commitments :: ShortIds :: ChannelReady :: HNil = {
      case commitments :: shortChannelId :: lastSent :: HNil =>
        commitments :: ShortIds(real = RealScidStatus.Temporary(shortChannelId), localAlias = AddLocalAlias.genDeterministicAlias(commitments.channelId), remoteAlias_opt = None) :: lastSent :: HNil
    }

    def mutateDataNormal: Commitments :: RealShortChannelId :: Boolean :: Option[ChannelAnnouncement] :: ChannelUpdate :: Option[Shutdown] :: Option[Shutdown] :: Option[ClosingFeerates] :: HNil =>
      Commitments :: ShortIds :: Option[ChannelAnnouncement] :: ChannelUpdate :: Option[Shutdown] :: Option[Shutdown] :: Option[ClosingFeerates] :: HNil = {
      case commitments :: shortChannelId :: buried :: channelAnnouncement :: channelUpdate :: localShutdown :: remoteShutdown :: closingFeerates :: HNil =>
        commitments :: ShortIds(real = if (buried) RealScidStatus.Final(shortChannelId) else RealScidStatus.Temporary(shortChannelId), localAlias = AddLocalAlias.genDeterministicAlias(commitments.channelId), remoteAlias_opt = None) :: channelAnnouncement :: channelUpdate :: localShutdown :: remoteShutdown :: closingFeerates :: HNil
    }
  }

}
