package fr.acinq.eclair

import fr.acinq.bitcoin.Crypto
import fr.acinq.eclair.channel._

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
      fundingPrivkey = Array.fill[Byte](32)(1),
      revocationSecret = Array.fill[Byte](32)(2),
      paymentSecret = Array.fill[Byte](32)(3),
      delayedPaymentKey = Array.fill[Byte](32)(4),
      finalPrivKey = Array.fill[Byte](32)(5),
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
      fundingPrivkey = Array.fill[Byte](32)(11),
      revocationSecret = Array.fill[Byte](32)(12),
      paymentSecret = Array.fill[Byte](32)(13),
      delayedPaymentKey = Array.fill[Byte](32)(14),
      finalPrivKey = Array.fill[Byte](32)(15),
      shaSeed = Crypto.sha256("alice-seed".getBytes())
    )
  }

}
