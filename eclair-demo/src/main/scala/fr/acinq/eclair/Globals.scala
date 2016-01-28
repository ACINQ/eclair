package fr.acinq.eclair

import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.{BitcoinJsonRPCClient, Base58Check}
import fr.acinq.eclair.crypto.LightningCrypto._
import lightning.locktime
import lightning.locktime.Locktime.Seconds


/**
  * Created by PM on 25/01/2016.
  */
object Globals {
  val config = ConfigFactory.load()
  val node_id = KeyPair("0277863c1e40a2d4934ccf18e6679ea949d36bb0d1333fb098e99180df60d0195a","0623a602c7b0c96df445b999de31ca31682f0117ca2bf2fb149b9e09287d5d47")
  val commit_priv = Base58Check.decode("cQPmcNr6pwBQPyGfab3SksE9nTCtx9ism9T4dkS9dETNU2KKtJHk")._2
  val final_priv = Base58Check.decode("cUrAtLtV7GGddqdkhUxnbZVDWGJBTducpPoon3eKp9Vnr1zxs6BG")._2

  val default_locktime = locktime(Seconds(86400))
  val default_mindepth = 3
  val default_commitfee = 50000

  val default_anchor_amount = 1000000

  val bitcoin_client = new BitcoinJsonRPCClient(
    user = config.getString("eclair.bitcoind.rpcuser"),
    password = config.getString("eclair.bitcoind.rpcpassword"),
    host = config.getString("eclair.bitcoind.address"),
    port = config.getInt("eclair.bitcoind.port"))
}
