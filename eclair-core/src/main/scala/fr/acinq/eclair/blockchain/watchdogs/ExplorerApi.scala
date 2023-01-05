/*
 * Copyright 2020 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.blockchain.watchdogs

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.pattern.after
import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32}
import fr.acinq.eclair.blockchain.watchdogs.BlockchainWatchdog.{BlockHeaderAt, LatestHeaders}
import fr.acinq.eclair.blockchain.watchdogs.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.tor.Socks5ProxyParams
import fr.acinq.eclair.{BlockHeight, randomBytes}
import org.json4s.JsonAST.{JArray, JInt, JObject, JString}
import org.json4s.jackson.Serialization
import org.json4s.{DefaultFormats, Serialization}
import sttp.client3._
import sttp.client3.json4s._
import sttp.client3.okhttp.OkHttpFutureBackend
import sttp.model.{StatusCode, Uri}

import java.time.OffsetDateTime
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Created by t-bast on 12/10/2020.
 */

/** This actor queries a configurable explorer API to fetch block headers. */
object ExplorerApi {

  implicit val formats: DefaultFormats = DefaultFormats
  implicit val serialization: Serialization = Serialization

  import fr.acinq.bitcoin.scalacompat.KotlinUtils.scala2kmp

  sealed trait Explorer {
    // @formatter:off
    /** Explorer friendly-name. */
    def name: String
    /** Map from chainHash to explorer API URI. */
    def baseUris: Map[ByteVector32, Uri]
    /** Fetch latest headers from the explorer. */
    def getLatestHeaders(baseUri: Uri, currentBlockHeight: BlockHeight)(implicit context: ActorContext[Command]): Future[LatestHeaders]
    // @formatter:on
  }

  // @formatter:off
  sealed trait Command
  case class CheckLatestHeaders(replyTo: ActorRef[LatestHeaders]) extends Command
  private case class WrappedLatestHeaders(replyTo: ActorRef[LatestHeaders], headers: LatestHeaders) extends Command
  private case class WrappedFailure(e: Throwable) extends Command
  // @formatter:on

  def apply(chainHash: ByteVector32, currentBlockHeight: BlockHeight, explorer: Explorer): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case CheckLatestHeaders(replyTo) =>
          explorer.baseUris.get(chainHash) match {
            case Some(uri) =>
              context.pipeToSelf(explorer.getLatestHeaders(uri, currentBlockHeight)(context)) {
                case Success(headers) => WrappedLatestHeaders(replyTo, headers)
                case Failure(e) => WrappedFailure(e)
              }
              Behaviors.same
            case None =>
              Behaviors.stopped
          }

        case WrappedLatestHeaders(replyTo, headers) =>
          replyTo ! headers
          Behaviors.stopped

        case WrappedFailure(e) =>
          context.log.warn(s"${explorer.name} failed: ", e)
          Metrics.WatchdogError.withTag(Tags.Source, explorer.name).increment()
          Behaviors.stopped
      }
    }
  }

  def createSttpBackend(socksProxy_opt: Option[Socks5ProxyParams]): SttpBackend[Future, _] = {
    val options = SttpBackendOptions(connectionTimeout = 1.minute, proxy = None)
    val sttpBackendOptions: SttpBackendOptions = socksProxy_opt match {
      case Some(proxy) =>
        val host = proxy.address.getHostString
        val port = proxy.address.getPort
        if (proxy.randomizeCredentials)
          options.socksProxy(host, port, username = randomBytes(16).toHex, password = randomBytes(16).toHex)
        else
          options.socksProxy(host, port)
      case None => options
    }
    OkHttpFutureBackend(sttpBackendOptions)
  }

  /**
   * Query https://blockcypher.com/ to fetch block headers.
   * See https://www.blockcypher.com/dev/bitcoin/#introduction.
   */
  case class BlockcypherExplorer()(implicit val sb: SttpBackend[Future, _]) extends Explorer {
    override val name = "blockcypher.com"
    override val baseUris = Map(
      Block.TestnetGenesisBlock.hash -> uri"https://api.blockcypher.com/v1/btc/test3",
      Block.LivenetGenesisBlock.hash -> uri"https://api.blockcypher.com/v1/btc/main"
    )

    override def getLatestHeaders(baseUri: Uri, currentBlockHeight: BlockHeight)(implicit context: ActorContext[Command]): Future[LatestHeaders] = {
      implicit val classicSystem: ActorSystem = context.system.classicSystem
      implicit val ec: ExecutionContext = context.system.executionContext
      for {
        tip <- getTip(baseUri)
        start = currentBlockHeight.max(tip - 10)
        // We add delays between API calls to avoid getting throttled.
        headers <- Future.sequence((start.toLong to tip.toLong).map(i => after((i - start.toLong).toInt * 1.second)(getHeader(baseUri, i))))
          .map(h => LatestHeaders(currentBlockHeight, h.flatten.filter(_.blockHeight >= currentBlockHeight).toSet, name))
      } yield headers
    }

    private def getTip(baseUri: Uri)(implicit ec: ExecutionContext): Future[BlockHeight] = {
      for {
        tip <- basicRequest.readTimeout(30 seconds).get(baseUri)
          .headers(Socks5ProxyParams.FakeFirefoxHeaders)
          .response(asJson[JObject])
          .send(sb)
          .map(_.body.fold(exc => throw exc, jvalue => jvalue))
          .map(json => {
            val JInt(latestHeight) = json \ "height"
            BlockHeight(latestHeight.toLong)
          })
      } yield tip
    }

    private def getHeader(baseUri: Uri, blockCount: Long)(implicit ec: ExecutionContext): Future[Seq[BlockHeaderAt]] = for {
      header <- basicRequest.readTimeout(30 seconds).get(baseUri.addPath("blocks", blockCount.toString))
        .headers(Socks5ProxyParams.FakeFirefoxHeaders)
        .response(asJson[JObject])
        .send(sb)
        .map(r => r.body match {
          // HTTP 404 is a "normal" error: we're trying to lookup future blocks that haven't been mined.
          case Left(res: HttpError[_]) if res.statusCode == StatusCode.NotFound => Seq.empty
          case Left(otherError) => throw otherError
          case Right(json) if json \ "error" == JString(s"Block $blockCount not found.") => Seq.empty
          case Right(block) =>
            val JInt(height) = block \ "height"
            val JInt(version) = block \ "ver"
            val JString(time) = block \ "time"
            val JInt(bits) = block \ "bits"
            val JInt(nonce) = block \ "nonce"
            val previousBlockHash = (block \ "prev_block").extractOpt[String].map(ByteVector32.fromValidHex(_).reverse).getOrElse(ByteVector32.Zeroes)
            val merkleRoot = (block \ "mrkl_root").extractOpt[String].map(ByteVector32.fromValidHex(_).reverse).getOrElse(ByteVector32.Zeroes)
            val header = new BlockHeader(version.toLong, previousBlockHash, merkleRoot, OffsetDateTime.parse(time).toEpochSecond, bits.toLong, nonce.toLong)
            Seq(BlockHeaderAt(BlockHeight(height.toLong), header))
        })
    } yield header
  }

  /** Explorer API based on Esplora: see https://github.com/Blockstream/esplora/blob/master/API.md. */
  sealed trait Esplora extends Explorer {
    implicit val sb: SttpBackend[Future, _]

    override def getLatestHeaders(baseUri: Uri, currentBlockHeight: BlockHeight)(implicit context: ActorContext[Command]): Future[LatestHeaders] = {
      implicit val ec: ExecutionContext = context.system.executionContext
      for {
        headers <- basicRequest.readTimeout(10 seconds).get(baseUri.addPath("blocks"))
          .response(asJson[JArray])
          .send(sb)
          .map(r => r.code match {
            // HTTP 404 is a "normal" error: we're trying to lookup future blocks that haven't been mined.
            case StatusCode.NotFound => Seq.empty
            case _ => r.body.fold(exc => throw exc, jvalue => jvalue).arr
          })
          .map(blocks => blocks.map(block => {
            val JInt(height) = block \ "height"
            val JInt(version) = block \ "version"
            val JInt(time) = block \ "timestamp"
            val JInt(bits) = block \ "bits"
            val JInt(nonce) = block \ "nonce"
            val previousBlockHash = (block \ "previousblockhash").extractOpt[String].map(ByteVector32.fromValidHex(_).reverse).getOrElse(ByteVector32.Zeroes)
            val merkleRoot = (block \ "merkle_root").extractOpt[String].map(ByteVector32.fromValidHex(_).reverse).getOrElse(ByteVector32.Zeroes)
            val header = new BlockHeader(version.toLong, previousBlockHash, merkleRoot, time.toLong, bits.toLong, nonce.toLong)
            BlockHeaderAt(BlockHeight(height.toLong), header)
          }))
          .map(headers => LatestHeaders(currentBlockHeight, headers.filter(_.blockHeight >= currentBlockHeight).toSet, name))
      } yield headers
    }
  }

  /** Query https://blockstream.info/ to fetch block headers. */
  case class BlockstreamExplorer(useTorEndpoints: Boolean)(implicit val sb: SttpBackend[Future, _]) extends Esplora {
    override val name = "blockstream.info"
    override val baseUris = if (useTorEndpoints) {
      Map(
        Block.TestnetGenesisBlock.hash -> uri"http://explorerzydxu5ecjrkwceayqybizmpjjznk5izmitf2modhcusuqlid.onion/testnet/api",
        Block.LivenetGenesisBlock.hash -> uri"http://explorerzydxu5ecjrkwceayqybizmpjjznk5izmitf2modhcusuqlid.onion/api"
      )
    } else {
      Map(
        Block.TestnetGenesisBlock.hash -> uri"https://blockstream.info/testnet/api",
        Block.LivenetGenesisBlock.hash -> uri"https://blockstream.info/api"
      )
    }
  }

  /** Query https://mempool.space/ to fetch block headers. */
  case class MempoolSpaceExplorer(useTorEndpoints: Boolean)(implicit val sb: SttpBackend[Future, _]) extends Esplora {
    override val name = "mempool.space"
    override val baseUris = if (useTorEndpoints) {
      Map(
        Block.TestnetGenesisBlock.hash -> uri"http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion/testnet/api",
        Block.LivenetGenesisBlock.hash -> uri"http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion/api",
        Block.SignetGenesisBlock.hash -> uri"http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion/signet/api"
      )
    } else {
      Map(
        Block.TestnetGenesisBlock.hash -> uri"https://mempool.space/testnet/api",
        Block.LivenetGenesisBlock.hash -> uri"https://mempool.space/api",
        Block.SignetGenesisBlock.hash -> uri"https://mempool.space/signet/api"
      )
    }
  }

}
