package fr.acinq.eclair.channel

import fr.acinq.bitcoin.{Base58, Base58Check, Crypto, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.{MakeAnchor, Publish}
import lightning.locktime
import lightning.locktime.Locktime.Blocks

/**
  * Created by PM on 26/04/2016.
  */
object TestConstants {
  val anchorAmount = 1000000L

  // Alice is funder, Bob is not

  object Alice {
    val (Base58.Prefix.SecretKeyTestnet, commitPrivKey) = Base58Check.decode("cQPmcNr6pwBQPyGfab3SksE9nTCtx9ism9T4dkS9dETNU2KKtJHk")
    val (Base58.Prefix.SecretKeyTestnet, finalPrivKey) = Base58Check.decode("cUrAtLtV7GGddqdkhUxnbZVDWGJBTducpPoon3eKp9Vnr1zxs6BG")
    val channelParams = OurChannelParams(locktime(Blocks(10)), commitPrivKey, finalPrivKey, 1, 10000, Crypto.sha256("alice-seed".getBytes()), Some(anchorAmount))
  }

  object Bob {
    val (Base58.Prefix.SecretKeyTestnet, commitPrivKey) = Base58Check.decode("cSUwLtdZ2tht9ZmHhdQue48pfe7tY2GT2TGWJDtjoZgo6FHrubGk")
    val (Base58.Prefix.SecretKeyTestnet, finalPrivKey) = Base58Check.decode("cPR7ZgXpUaDPA3GwGceMDS5pfnSm955yvks3yELf3wMJwegsdGTg")
    val channelParams = OurChannelParams(locktime(Blocks(10)), commitPrivKey, finalPrivKey, 2, 10000, Crypto.sha256("bob-seed".getBytes()), None)
  }

}
