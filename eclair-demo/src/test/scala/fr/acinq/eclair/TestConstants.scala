package fr.acinq.eclair

import fr.acinq.bitcoin.{Base58, Base58Check, BinaryData, Crypto, Hash, OutPoint, Satoshi, TxIn, TxOut}
import fr.acinq.eclair.channel.{TheirChanges, _}
import fr.acinq.eclair.crypto.ShaChain
import lightning.locktime
import lightning.locktime.Locktime.Blocks

/**
  * Created by PM on 26/04/2016.
  */
object TestConstants {
  val anchorAmount = 1000000L

  lazy val anchorOutput = TxOut(Satoshi(anchorAmount), publicKeyScript = Scripts.anchorPubkeyScript(Alice.channelParams.commitPubKey, Bob.channelParams.commitPubKey))

  // Alice is funder, Bob is not

  object Alice {
    val (Base58.Prefix.SecretKeyTestnet, commitPrivKey) = Base58Check.decode("cQPmcNr6pwBQPyGfab3SksE9nTCtx9ism9T4dkS9dETNU2KKtJHk")
    val (Base58.Prefix.SecretKeyTestnet, finalPrivKey) = Base58Check.decode("cUrAtLtV7GGddqdkhUxnbZVDWGJBTducpPoon3eKp9Vnr1zxs6BG")
    val channelParams = OurChannelParams(locktime(Blocks(300)), commitPrivKey, finalPrivKey, 1, 10000, Crypto.sha256("alice-seed".getBytes()), Some(Satoshi(anchorAmount)))
    val finalPubKey = channelParams.finalPubKey

    def revocationHash(index: Long) = Helpers.revocationHash(channelParams.shaSeed, index)

    def ourSpec = CommitmentSpec(Set.empty[Htlc], feeRate = Alice.channelParams.initialFeeRate, initial_amount_them_msat = 0, initial_amount_us_msat = anchorAmount * 1000, amount_them_msat = 0, amount_us_msat = anchorAmount * 1000)

    def theirSpec = CommitmentSpec(Set.empty[Htlc], feeRate = Bob.channelParams.initialFeeRate, initial_amount_them_msat = anchorAmount * 1000, initial_amount_us_msat = 0, amount_them_msat = anchorAmount * 1000, amount_us_msat = 0)

    val ourTx = Helpers.makeOurTx(channelParams, TheirChannelParams(Bob.channelParams), TxIn(OutPoint(Hash.One, 0), Array.emptyByteArray, 0xffffffffL) :: Nil, revocationHash(0), ourSpec)

    val commitments = Commitments(
      Alice.channelParams,
      TheirChannelParams(Bob.channelParams),
      OurCommit(0, ourSpec, ourTx), TheirCommit(0, theirSpec, BinaryData(""), Bob.revocationHash(0)),
      OurChanges(Nil, Nil, Nil), TheirChanges(Nil, Nil), 0L,
      Right(Bob.revocationHash(1)), anchorOutput, ShaChain.init, new BasicTxDb)

  }

  object Bob {
    val (Base58.Prefix.SecretKeyTestnet, commitPrivKey) = Base58Check.decode("cSUwLtdZ2tht9ZmHhdQue48pfe7tY2GT2TGWJDtjoZgo6FHrubGk")
    val (Base58.Prefix.SecretKeyTestnet, finalPrivKey) = Base58Check.decode("cPR7ZgXpUaDPA3GwGceMDS5pfnSm955yvks3yELf3wMJwegsdGTg")
    val channelParams = OurChannelParams(locktime(Blocks(350)), commitPrivKey, finalPrivKey, 2, 10000, Crypto.sha256("bob-seed".getBytes()), None)
    val finalPubKey = channelParams.finalPubKey

    def revocationHash(index: Long) = Helpers.revocationHash(channelParams.shaSeed, index)

    def ourSpec = Alice.theirSpec

    def theirSpec = Alice.ourSpec

    val ourTx = Helpers.makeOurTx(channelParams, TheirChannelParams(Alice.channelParams), TxIn(OutPoint(Hash.One, 0), Array.emptyByteArray, 0xffffffffL) :: Nil, revocationHash(0), ourSpec)

    val commitments = Commitments(
      Bob.channelParams,
      TheirChannelParams(Alice.channelParams),
      OurCommit(0, ourSpec, ourTx), TheirCommit(0, theirSpec, BinaryData(""), Alice.revocationHash(0)),
      OurChanges(Nil, Nil, Nil), TheirChanges(Nil, Nil), 0L,
      Right(Alice.revocationHash(1)), anchorOutput, ShaChain.init, new BasicTxDb)
  }

}
