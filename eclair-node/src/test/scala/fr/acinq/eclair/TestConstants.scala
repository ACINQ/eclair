package fr.acinq.eclair

import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.Crypto.Scalar
import fr.acinq.eclair.channel._

/**
  * Created by PM on 26/04/2016.
  */
object TestConstants {
  val fundingSatoshis = 1000000L
  val pushMsat = 200000000L

  object Alice {
    val channelParams = LocalParams(
      dustLimitSatoshis = 542,
      maxHtlcValueInFlightMsat = Long.MaxValue,
      channelReserveSatoshis = 0,
      htlcMinimumMsat = 0,
      feeratePerKw = 10000,
      toSelfDelay = 144,
      maxAcceptedHtlcs = 100,
      fundingPrivkey = Scalar(Array.fill[Byte](32)(1) :+ 1.toByte),
      revocationSecret = Scalar(Array.fill[Byte](32)(2) :+ 1.toByte),
      paymentKey = Scalar(Array.fill[Byte](32)(3) :+ 1.toByte),
      delayedPaymentKey = Scalar(Array.fill[Byte](32)(4) :+ 1.toByte),
      finalPrivKey = Scalar(Array.fill[Byte](32)(5) :+ 1.toByte),
      shaSeed = Crypto.sha256("alice-seed".getBytes()),
      isFunder = true
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
      fundingPrivkey = Scalar(Array.fill[Byte](32)(11) :+ 1.toByte),
      revocationSecret = Scalar(Array.fill[Byte](32)(12) :+ 1.toByte),
      paymentKey = Scalar(Array.fill[Byte](32)(13) :+ 1.toByte),
      delayedPaymentKey = Scalar(Array.fill[Byte](32)(14) :+ 1.toByte),
      finalPrivKey = Scalar(Array.fill[Byte](32)(15) :+ 1.toByte),
      shaSeed = Crypto.sha256("alice-seed".getBytes()),
      isFunder = false
    )
  }

}
