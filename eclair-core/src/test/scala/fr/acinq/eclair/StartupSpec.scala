package fr.acinq.eclair

import java.io.{File, IOException}
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file._

import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.Block
import fr.acinq.eclair.crypto.LocalKeyManager
import org.scalatest.FunSuite

import scala.util.Try

class StartupSpec extends FunSuite {

  test("NodeParams should fail if configuration params are non-okay") {

    val threeBytesUTFChar = '\u20AC' // €
    val baseUkraineAlias = "BitcoinLightningNodeUkraine"

    assert(baseUkraineAlias.length === 27)
    assert(baseUkraineAlias.getBytes.length === 27)

    // we add 2 UTF-8 chars, each is 3-bytes long -> total new length 33 bytes!
    val goUkraineGo = threeBytesUTFChar+"BitcoinLightningNodeUkraine"+threeBytesUTFChar

    assert(goUkraineGo.length === 29)
    assert(goUkraineGo.getBytes.length === 33) // too long for the alias, should be truncated

    val conf = ConfigFactory.parseString(rawEclairConf(goUkraineGo)).resolve().getConfig("eclair")
    val tempConfParentDir = new File("temp-test.conf")

    val keyManager = new LocalKeyManager(seed = randomKey.toBin, chainHash = Block.TestnetGenesisBlock.hash)

    // try to create a NodeParams instance with a conf that contains an illegal alias
    val nodeParamsAttempt = Try(NodeParams.makeNodeParams(tempConfParentDir, conf, keyManager))
    assert(nodeParamsAttempt.isFailure)

    // destroy conf files after the test
    Files.walkFileTree(tempConfParentDir.toPath, new SimpleFileVisitor[Path]() {
      override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
        Files.deleteIfExists(file)
        FileVisitResult.CONTINUE
      }
    })

    tempConfParentDir.listFiles.foreach(_.deleteOnExit())
    tempConfParentDir.deleteOnExit()
  }


  def rawEclairConf(testAlias: String) = s"""
			 eclair {

			   chain = "mainnet"

			   server {
			     public-ips = [] // external ips, will be announced on the network
			     binding-ip = "0.0.0.0"
			     port = 9735
			   }

			   api {
			     enabled = false // disabled by default for security reasons
			     binding-ip = "127.0.0.1"
			     port = 8080
			     password = "" // password for basic auth, must be non empty if json-rpc api is enabled
			   }

			   watcher-type = "bitcoind" // other *experimental* values include "electrum"

			   bitcoind {
			     host = "localhost"
			     rpcport = 28332
			     rpcuser = "foo"
			     rpcpassword = "bar"
			     zmqblock = "tcp://127.0.0.1:28334"
			     zmqtx = "tcp://127.0.0.1:28335"
			   }

			   default-feerates { // those are in satoshis per kilobyte
			     delay-blocks {
			       1 = 210000
			       2 = 180000
			       6 = 150000
			       12 = 110000
			       36 = 50000
			       72 = 20000
			     }
			   }
			   min-feerate = 2 // minimum feerate in satoshis per byte
			   smooth-feerate-window = 3 // 1 = no smoothing

			   node-alias = "$testAlias"
			   node-color = "49daaa"

			   global-features = ""
			   local-features = "8a" // initial_routing_sync + option_data_loss_protect + option_channel_range_queries
			   override-features = [ // optional per-node features
			   #  {
			   #    nodeid = "02aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			   #    global-features = "",
			   #    local-features = ""
			   #  }
			   ]
			   channel-flags = 1 // announce channels

			   dust-limit-satoshis = 546
			   max-htlc-value-in-flight-msat = 5000000000 // 50 mBTC
			   htlc-minimum-msat = 1
			   max-accepted-htlcs = 30

			   reserve-to-funding-ratio = 0.01 // recommended by BOLT #2
			   max-reserve-to-funding-ratio = 0.05 // channel reserve can't be more than 5% of the funding amount (recommended: 1%)

			   to-remote-delay-blocks = 144 // number of blocks that the other node's to-self outputs must be delayed (144 ~ 1 day)
			   max-to-local-delay-blocks = 2000 // maximum number of blocks that we are ready to accept for our own delayed outputs (2000 ~ 2 weeks)
			   mindepth-blocks = 3
			   expiry-delta-blocks = 144

			   fee-base-msat = 1000
			   fee-proportional-millionths = 100 // fee charged per transferred satoshi in millionths of a satoshi (100 = 0.01%)

			   // maximum local vs remote feerate mismatch; 1.0 means 100%
			   // actual check is abs((local feerate - remote fee rate) / (local fee rate + remote fee rate)/2) > fee rate mismatch
			   max-feerate-mismatch = 1.5

			   // funder will send an UpdateFee message if the difference between current commitment fee and actual current network fee is greater
			   // than this ratio.
			   update-fee_min-diff-ratio = 0.1

			   revocation-timeout = 20 seconds // after sending a commit_sig, we will wait for at most that duration before disconnecting

			   channel-exclude-duration = 60 seconds // when a temporary channel failure is returned, we exclude the channel from our payment routes for this duration
			   router-broadcast-interval = 60 seconds // see BOLT #7

			   router-init-timeout = 5 minutes

			   ping-interval = 30 seconds
			   auto-reconnect = true

			   payment-handler = "local"
			   payment-request-expiry = 1 hour // default expiry for payment requests generated by this node
			   max-pending-payment-requests = 10000000
			   max-payment-fee = 0.03 // max total fee for outgoing payments, in percentage: sending a payment will not be attempted if the cheapest route found is more expensive than that
			   min-funding-satoshis = 100000

         ping-interval = 30 seconds
         ping-timeout = 10 seconds // will disconnect if peer takes longer than that to respond
         ping-disconnect = true // disconnect if no answer to our pings
         auto-reconnect = true
       }
		""".stripMargin
}
