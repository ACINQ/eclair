package fr.acinq.eclair

import fr.acinq.bitcoin.Crypto
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Generators.Scalar

/**
  * Created by PM on 26/04/2016.
  */
object TestConstants {
  val anchorAmount = 1000000L

  object Alice {
    val channelParams = LocalParams(
      dustLimitSatoshis = 542,
      maxHtlcValueInFlightMsat = Long.MaxValue,
      channelReserveSatoshis = 0,
      htlcMinimumMsat = 0,
      feeratePerKw = 10000,
      toSelfDelay = 144,
      maxAcceptedHtlcs = 100,
      fundingPrivkey = Scalar(Array.fill[Byte](32)(1)),
      revocationSecret = Scalar(Array.fill[Byte](32)(2)),
      paymentSecret = Scalar(Array.fill[Byte](32)(3)),
      delayedPaymentKey = Scalar(Array.fill[Byte](32)(4)),
      finalPrivKey = Scalar(Array.fill[Byte](32)(5)),
      shaSeed = Crypto.sha256("alice-seed".getBytes())
    )
  }

  object Bob {
    val channelParams = LocalParams(
      dustLimitSatoshis = 542,
      maxHtlcValueInFlightMsat = Long.MaxValue,
      channelReserveSatoshis = 0,
      htlcMinimumMsat = 0,
      feeratePerKw = 10000,
      toSelfDelay = 144,
      maxAcceptedHtlcs = 100,
      fundingPrivkey = Scalar(Array.fill[Byte](32)(11)),
      revocationSecret = Scalar(Array.fill[Byte](32)(12)),
      paymentSecret = Scalar(Array.fill[Byte](32)(13)),
      delayedPaymentKey = Scalar(Array.fill[Byte](32)(14)),
      finalPrivKey = Scalar(Array.fill[Byte](32)(15)),
      shaSeed = Crypto.sha256("alice-seed".getBytes())
    )
  }

}
