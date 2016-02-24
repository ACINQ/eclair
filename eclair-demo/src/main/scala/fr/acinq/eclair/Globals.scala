package fr.acinq.eclair

import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.{Crypto, BitcoinJsonRPCClient}
import fr.acinq.eclair.channel.OurChannelParams
import fr.acinq.eclair.crypto.LightningCrypto
import lightning.locktime
import lightning.locktime.Locktime.Seconds


/**
  * Created by PM on 25/01/2016.
  */
object Globals {
  val config = ConfigFactory.load()

  val node_id = LightningCrypto.randomKeyPair()
  val commit_priv = Crypto.sha256(node_id.priv) // TODO : just for testing
  val final_priv = Crypto.sha256(commit_priv) // TODO : just for testing

  val default_locktime = locktime(Seconds(86400))
  val default_mindepth = 3
  val commit_fee = config.getInt("eclair.commit-fee")
  val closing_fee = config.getInt("eclair.closing-fee")

  val default_anchor_amount = 1000000

  val params_noanchor = OurChannelParams(default_locktime, commit_priv, final_priv, default_mindepth, commit_fee, "sha-seed".getBytes(), None)

  val bitcoin_client = new BitcoinJsonRPCClient(
    user = config.getString("eclair.bitcoind.rpcuser"),
    password = config.getString("eclair.bitcoind.rpcpassword"),
    host = config.getString("eclair.bitcoind.host"),
    port = config.getInt("eclair.bitcoind.rpcport"))
}
