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

import java.time.OffsetDateTime

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.pattern.after
import com.softwaremill.sttp.json4s.asJson
import com.softwaremill.sttp.okhttp.OkHttpFutureBackend
import com.softwaremill.sttp.{StatusCodes, SttpBackend, Uri, UriContext, sttp}
import fr.acinq.bitcoin.{Block, BlockHeader, ByteVector32}
import fr.acinq.eclair.blockchain.watchdogs.BlockchainWatchdog.{BlockHeaderAt, LatestHeaders}
import fr.acinq.eclair.blockchain.watchdogs.Monitoring.{Metrics, Tags}
import org.json4s.JsonAST.{JArray, JInt, JObject, JString}
import org.json4s.jackson.Serialization
import org.json4s.{DefaultFormats, Serialization}

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
  implicit val sttpBackend: SttpBackend[Future, Nothing] = OkHttpFutureBackend()

  sealed trait Explorer {
    // @formatter:off
    /** Explorer friendly-name. */
    def name: String
    /** Map from chainHash to explorer API URI. */
    def baseUris: Map[ByteVector32, Uri]
    /** Fetch latest headers from the explorer. */
    def getLatestHeaders(baseUri: Uri, currentBlockCount: Long)(implicit context: ActorContext[Command]): Future[LatestHeaders]
    // @formatter:on
  }

  // @formatter:off
  sealed trait Command
  case class CheckLatestHeaders(replyTo: ActorRef[LatestHeaders]) extends Command
  private case class WrappedLatestHeaders(replyTo: ActorRef[LatestHeaders], headers: LatestHeaders) extends Command
  private case class WrappedFailure(e: Throwable) extends Command
  // @formatter:on

  def apply(chainHash: ByteVector32, currentBlockCount: Long, explorer: Explorer): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case CheckLatestHeaders(replyTo) =>
          explorer.baseUris.get(chainHash) match {
            case Some(uri) =>
              context.pipeToSelf(explorer.getLatestHeaders(uri, currentBlockCount)(context)) {
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
          context.log.error(s"${explorer.name} failed: ", e)
          Metrics.WatchdogError.withTag(Tags.Source, explorer.name).increment()
          Behaviors.stopped
      }
    }
  }

  /**
   * Query https://blockcypher.com/ to fetch block headers.
   * See https://www.blockcypher.com/dev/bitcoin/#introduction.
   */
  case class BlockcypherExplorer() extends Explorer {
    override val name = "blockcypher.com"
    override val baseUris = Map(
      Block.TestnetGenesisBlock.hash -> uri"https://api.blockcypher.com/v1/btc/test3",
      Block.LivenetGenesisBlock.hash -> uri"https://api.blockcypher.com/v1/btc/main"
    )

    override def getLatestHeaders(baseUri: Uri, currentBlockCount: Long)(implicit context: ActorContext[Command]): Future[LatestHeaders] = {
      implicit val classicSystem: ActorSystem = context.system.classicSystem
      implicit val ec: ExecutionContext = context.system.executionContext
      for {
        tip <- getTip(baseUri)
        start = currentBlockCount.max(tip - 10)
        // We add delays between API calls to avoid getting throttled.
        headers <- Future.sequence((start to tip).map(i => after((i - start).toInt * 1.second)(getHeader(baseUri, i))))
          .map(h => LatestHeaders(currentBlockCount, h.flatten.filter(_.blockCount >= currentBlockCount).toSet, name))
      } yield headers
    }

    private def getTip(baseUri: Uri)(implicit ec: ExecutionContext): Future[Long] = for {
      tip <- sttp.readTimeout(30 seconds).get(baseUri)
        .response(asJson[JObject])
        .send()
        .map(r => {
          val JInt(latestHeight) = r.unsafeBody \ "height"
          latestHeight.toLong
        })
    } yield tip

    private def getHeader(baseUri: Uri, blockCount: Long)(implicit ec: ExecutionContext): Future[Seq[BlockHeaderAt]] = for {
      header <- sttp.readTimeout(30 seconds).get(baseUri.path(baseUri.path :+ "blocks" :+ blockCount.toString))
        .response(asJson[JObject])
        .send()
        .map(r => r.code match {
          // HTTP 404 is a "normal" error: we're trying to lookup future blocks that haven't been mined.
          case StatusCodes.NotFound => Seq.empty
          case _ => r.unsafeBody \ "error" match {
            case JString(error) if error == s"Block $blockCount not found." => Seq.empty
            case _ => Seq(r.unsafeBody)
          }
        })
        .map(blocks => blocks.map(block => {
          val JInt(height) = block \ "height"
          val JInt(version) = block \ "ver"
          val JString(time) = block \ "time"
          val JInt(bits) = block \ "bits"
          val JInt(nonce) = block \ "nonce"
          val previousBlockHash = (block \ "prev_block").extractOpt[String].map(ByteVector32.fromValidHex(_).reverse).getOrElse(ByteVector32.Zeroes)
          val merkleRoot = (block \ "mrkl_root").extractOpt[String].map(ByteVector32.fromValidHex(_).reverse).getOrElse(ByteVector32.Zeroes)
          val header = BlockHeader(version.toLong, previousBlockHash, merkleRoot, OffsetDateTime.parse(time).toEpochSecond, bits.toLong, nonce.toLong)
          BlockHeaderAt(height.toLong, header)
        }))
    } yield header
  }

  /** Explorer API based on Esplora: see https://github.com/Blockstream/esplora/blob/master/API.md. */
  sealed trait Esplora extends Explorer {
    override def getLatestHeaders(baseUri: Uri, currentBlockCount: Long)(implicit context: ActorContext[Command]): Future[LatestHeaders] = {
      implicit val ec: ExecutionContext = context.system.executionContext
      for {
        headers <- sttp.readTimeout(10 seconds).get(baseUri.path(baseUri.path :+ "blocks"))
          .response(asJson[JArray])
          .send()
          .map(r => r.code match {
            // HTTP 404 is a "normal" error: we're trying to lookup future blocks that haven't been mined.
            case StatusCodes.NotFound => Seq.empty
            case _ => r.unsafeBody.arr
          })
          .map(blocks => blocks.map(block => {
            val JInt(height) = block \ "height"
            val JInt(version) = block \ "version"
            val JInt(time) = block \ "timestamp"
            val JInt(bits) = block \ "bits"
            val JInt(nonce) = block \ "nonce"
            val previousBlockHash = (block \ "previousblockhash").extractOpt[String].map(ByteVector32.fromValidHex(_).reverse).getOrElse(ByteVector32.Zeroes)
            val merkleRoot = (block \ "merkle_root").extractOpt[String].map(ByteVector32.fromValidHex(_).reverse).getOrElse(ByteVector32.Zeroes)
            val header = BlockHeader(version.toLong, previousBlockHash, merkleRoot, time.toLong, bits.toLong, nonce.toLong)
            BlockHeaderAt(height.toLong, header)
          }))
          .map(headers => LatestHeaders(currentBlockCount, headers.filter(_.blockCount >= currentBlockCount).toSet, name))
      } yield headers
    }
  }

  /** Query https://blockstream.info/ to fetch block headers. */
  case class BlockstreamExplorer() extends Esplora {
    override val name = "blockstream.info"
    override val baseUris = Map(
      Block.TestnetGenesisBlock.hash -> uri"https://blockstream.info/testnet/api",
      Block.LivenetGenesisBlock.hash -> uri"https://blockstream.info/api"
    )
  }

  /** Query https://mempool.space/ to fetch block headers. */
  case class MempoolSpaceExplorer() extends Esplora {
    override val name = "mempool.space"
    override val baseUris = Map(
      Block.TestnetGenesisBlock.hash -> uri"https://mempool.space/testnet/api",
      Block.LivenetGenesisBlock.hash -> uri"https://mempool.space/api"
    )
  }

}
