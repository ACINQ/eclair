package fr.acinq.eclair

import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.{DeterministicWallet, BinaryData, Crypto, BitcoinJsonRPCClient}
import fr.acinq.eclair.api.BinaryDataSerializer
import fr.acinq.eclair.channel.OurChannelParams
import fr.acinq.eclair.crypto.LightningCrypto
import lightning.locktime
import lightning.locktime.Locktime.Seconds


/**
  * Created by PM on 25/01/2016.
  */
object Globals {
  val config = ConfigFactory.load()

  object Node {
    val seed: BinaryData = config.getString("eclair.node.seed")
    val master = DeterministicWallet.generate(seed)
    val extendedPrivateKey = DeterministicWallet.derivePrivateKey(master, DeterministicWallet.hardened(46) :: DeterministicWallet.hardened(0) :: Nil)
    val privateKey = extendedPrivateKey.secretkey
    val extendedPublicKey = DeterministicWallet.publicKey(extendedPrivateKey)
    val publicKey = extendedPublicKey.publickey
  }

  val default_locktime = locktime(Seconds(86400))
  val default_mindepth = 3
  val commit_fee = config.getInt("eclair.commit-fee")
  val closing_fee = config.getInt("eclair.closing-fee")

  val default_anchor_amount = 1000000

  //def newChannelParameters = OurChannelParams(default_locktime, commit_priv, final_priv, default_mindepth, commit_fee, "sha-seed".getBytes(), None)

  val bitcoin_client = new BitcoinJsonRPCClient(
    user = config.getString("eclair.bitcoind.rpcuser"),
    password = config.getString("eclair.bitcoind.rpcpassword"),
    host = config.getString("eclair.bitcoind.address"),
    port = config.getInt("eclair.bitcoind.port"))
}
