package fr.acinq.eclair

import fr.acinq.bitcoin.{Base58, Base58Check, BinaryData, Crypto, Hash, OutPoint, Transaction, TxIn, TxOut}
import fr.acinq.eclair.channel.{RemoteChanges, _}
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.transactions.{CommitmentSpec, Htlc}

/**
  * Created by PM on 26/04/2016.
  */
object TestConstants {
  val anchorAmount = 1000000L

  lazy val anchorOutput: TxOut = ??? //TxOut(Satoshi(anchorAmount), publicKeyScript = Scripts.anchorPubkeyScript(Alice.channelParams.commitPubKey, Bob.channelParams.commitPubKey))

  // Alice is funder, Bob is not

  object Alice {
    val channelParams: OurChannelParams = ???
    val (Base58.Prefix.SecretKeyTestnet, commitPrivKey) = Base58Check.decode("cQPmcNr6pwBQPyGfab3SksE9nTCtx9ism9T4dkS9dETNU2KKtJHk")
    val (Base58.Prefix.SecretKeyTestnet, finalPrivKey) = Base58Check.decode("cUrAtLtV7GGddqdkhUxnbZVDWGJBTducpPoon3eKp9Vnr1zxs6BG")
    val localParams = LocalParams(
      dustLimitSatoshis = 542,
      maxHtlcValueInFlightMsat = Long.MaxValue,
      channelReserveSatoshis = 0,
      htlcMinimumMsat = 0,
      maxNumHtlcs = 100,
      feeratePerKb = 10000,
      toSelfDelay = 144,
      fundingPubkey = Array.fill[Byte](33)(0),
      revocationBasepoint = Array.fill[Byte](33)(0),
      paymentBasepoint = Array.fill[Byte](33)(0),
      delayedPaymentBasepoint = Array.fill[Byte](33)(0),
      finalScriptPubKey = Array.fill[Byte](47)(0)
    )
    //(locktime(Blocks(300)), commitPrivKey, finalPrivKey, 1, 10000, Crypto.sha256("alice-seed".getBytes()), Some(Satoshi(anchorAmount)))

    val remoteParams = RemoteParams(
      dustLimitSatoshis = 542,
      maxHtlcValueInFlightMsat = Long.MaxValue,
      channelReserveSatoshis = 0,
      htlcMinimumMsat = 0,
      maxNumHtlcs = 100,
      feeratePerKb = 10000,
      toSelfDelay = 144,
      fundingPubkey = Array.fill[Byte](33)(0),
      revocationBasepoint = Array.fill[Byte](33)(0),
      paymentBasepoint = Array.fill[Byte](33)(0),
      delayedPaymentBasepoint = Array.fill[Byte](33)(0))
    //val remoteChannelParams = OurChannelParams(locktime(Blocks(300)), commitPrivKey, finalPrivKey, 1, 10000, Crypto.sha256("alice-seed".getBytes()), Some(Satoshi(anchorAmount)))

    val finalPubKey: BinaryData = Array.fill[Byte](33)(0)

    val shaSeed = Crypto.sha256("alice-seed".getBytes())

    def revocationHash(index: Long) = Commitments.revocationHash(shaSeed, index)

    def ourSpec = CommitmentSpec(Set.empty[Htlc], feeRate = localParams.feeratePerKb, to_remote_msat = 0, to_local_msat = anchorAmount * 1000)

    def theirSpec = CommitmentSpec(Set.empty[Htlc], feeRate = remoteParams.feeratePerKb, to_remote_msat = anchorAmount * 1000, to_local_msat = 0)

    val ourTx = CommitmentSpec.makeLocalTx(localParams, remoteParams, TxIn(OutPoint(Hash.One, 0), Array.emptyByteArray, 0xffffffffL) :: Nil, revocationHash(0), ourSpec)

    val commitments = Commitments(
      localParams,
      remoteParams,
      LocalCommit(0, ourSpec, ourTx), RemoteCommit(0, theirSpec, BinaryData(""), Bob.revocationHash(0)),
      LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil), 0L,
      Right(Bob.revocationHash(1)), anchorOutput, shaSeed, ShaChain.init, new BasicTxDb)

  }

  object Bob {
    val (Base58.Prefix.SecretKeyTestnet, commitPrivKey) = Base58Check.decode("cSUwLtdZ2tht9ZmHhdQue48pfe7tY2GT2TGWJDtjoZgo6FHrubGk")
    val (Base58.Prefix.SecretKeyTestnet, finalPrivKey) = Base58Check.decode("cPR7ZgXpUaDPA3GwGceMDS5pfnSm955yvks3yELf3wMJwegsdGTg")
    val channelParams = OurChannelParams(350, commitPrivKey, finalPrivKey, 2, 10000, Crypto.sha256("bob-seed".getBytes()), None)
    val finalPubKey = channelParams.finalPubKey

    def revocationHash(index: Long) = Commitments.revocationHash(channelParams.shaSeed, index)

    def ourSpec = Alice.theirSpec

    def theirSpec = Alice.ourSpec

    val ourTx: Transaction = ??? // Helpers.makeOurTx(channelParams, TheirChannelParams(Alice.channelParams), TxIn(OutPoint(Hash.One, 0), Array.emptyByteArray, 0xffffffffL) :: Nil, revocationHash(0), ourSpec)

    val commitments: Commitments = ???/*Commitments(
      Bob.channelParams,
      TheirChannelParams(Alice.channelParams),
      OurCommit(0, ourSpec, ourTx), TheirCommit(0, theirSpec, BinaryData(""), Alice.revocationHash(0)),
      OurChanges(Nil, Nil, Nil), TheirChanges(Nil, Nil), 0L,
      Right(Alice.revocationHash(1)), anchorOutput, ShaChain.init, new BasicTxDb)*/
  }

}
