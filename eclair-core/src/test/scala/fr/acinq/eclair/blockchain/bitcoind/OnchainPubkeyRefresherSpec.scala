package fr.acinq.eclair.blockchain.bitcoind

import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{Block, Crypto, computeBIP84Address}
import fr.acinq.eclair.blockchain.OnChainAddressGenerator
import fr.acinq.eclair.{TestKitBaseClass, randomKey}
import org.scalatest.funsuite.AnyFunSuiteLike

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

class OnchainPubkeyRefresherSpec extends TestKitBaseClass with AnyFunSuiteLike {
  test("renew onchain scripts") {
    val finalPubkey = new AtomicReference[PublicKey](randomKey().publicKey)
    val generator = new OnChainAddressGenerator {
      override def getReceiveAddress(label: String)(implicit ec: ExecutionContext): Future[String] = Future.successful(computeBIP84Address(randomKey().publicKey, Block.RegtestGenesisBlock.hash))

      override def getP2wpkhPubkey()(implicit ec: ExecutionContext): Future[Crypto.PublicKey] = Future.successful(randomKey().publicKey)
    }
    val manager = system.spawnAnonymous(OnchainPubkeyRefresher(generator, finalPubkey, 3 seconds))

    // renew script explicitly
    val currentPubkey = finalPubkey.get()
    manager ! OnchainPubkeyRefresher.Renew
    awaitCond(finalPubkey.get() != currentPubkey)
  }
}
