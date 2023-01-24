package fr.acinq.eclair.wire.internal.channel.version3

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{DeterministicWallet, Satoshi}
import fr.acinq.eclair.{CltvExpiryDelta, Features, InitFeature, MilliSatoshi, channel}
import scodec.bits.ByteVector

private[version3] object ChannelTypes3 {

  case class LocalParams(nodeId: PublicKey,
                         fundingKeyPath: DeterministicWallet.KeyPath,
                         dustLimit: Satoshi,
                         maxHtlcValueInFlightMsat: MilliSatoshi,
                         requestedChannelReserve_opt: Option[Satoshi],
                         htlcMinimum: MilliSatoshi,
                         toSelfDelay: CltvExpiryDelta,
                         maxAcceptedHtlcs: Int,
                         isInitiator: Boolean,
                         defaultFinalScriptPubKey: ByteVector,
                         walletStaticPaymentBasepoint: Option[PublicKey],
                         initFeatures: Features[InitFeature]) {
    def migrate(): channel.LocalParams = channel.LocalParams(
      nodeId, fundingKeyPath, dustLimit, maxHtlcValueInFlightMsat, requestedChannelReserve_opt, htlcMinimum, toSelfDelay, maxAcceptedHtlcs, isInitiator,
      if (defaultFinalScriptPubKey.size == 0) None else Some(defaultFinalScriptPubKey),
      walletStaticPaymentBasepoint, initFeatures
    )
  }

  object LocalParams {
    def apply(input: channel.LocalParams) = new LocalParams(input.nodeId, input.fundingKeyPath, input.dustLimit, input.maxHtlcValueInFlightMsat,
      input.requestedChannelReserve_opt, input.htlcMinimum, input.toSelfDelay, input.maxAcceptedHtlcs, input.isInitiator,
      input.upfrontShutdownScript_opt.getOrElse(ByteVector.empty), input.walletStaticPaymentBasepoint, input.initFeatures)
  }
}
