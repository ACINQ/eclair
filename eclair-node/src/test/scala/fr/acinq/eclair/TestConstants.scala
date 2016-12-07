package fr.acinq.eclair

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
      revocationSecret = Array.fill[Byte](33)(2),
      paymentSecret = Array.fill[Byte](33)(3),
      delayedPaymentKey = Array.fill[Byte](33)(4),
      finalPrivKey = Array.fill[Byte](33)(5),
      shaSeed = "alice-seed".getBytes()
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
      revocationSecret = Array.fill[Byte](33)(12),
      paymentSecret = Array.fill[Byte](33)(13),
      delayedPaymentKey = Array.fill[Byte](33)(14),
      finalPrivKey = Array.fill[Byte](33)(15),
      shaSeed = "bob-seed".getBytes()
    )
  }

}
