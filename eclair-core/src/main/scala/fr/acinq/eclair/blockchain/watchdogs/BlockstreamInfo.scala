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

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import com.softwaremill.sttp.json4s.asJson
import com.softwaremill.sttp.okhttp.OkHttpFutureBackend
import com.softwaremill.sttp.{StatusCodes, SttpBackend, Uri, UriContext, sttp}
import fr.acinq.bitcoin.{Block, BlockHeader, ByteVector32}
import fr.acinq.eclair.blockchain.watchdogs.BlockchainWatchdog.{BlockHeaderAt, LatestHeaders}
import fr.acinq.eclair.blockchain.watchdogs.Monitoring.{Metrics, Tags}
import org.json4s.JsonAST.{JArray, JInt}
import org.json4s.jackson.Serialization
import org.json4s.{DefaultFormats, Serialization}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Created by t-bast on 30/09/2020.
 */

/** This actor queries https://blockstream.info/ to fetch block headers. See https://github.com/Blockstream/esplora/blob/master/API.md. */
object BlockstreamInfo {

  implicit val formats: DefaultFormats = DefaultFormats
  implicit val serialization: Serialization = Serialization
  implicit val sttpBackend: SttpBackend[Future, Nothing] = OkHttpFutureBackend()

  val Source = "blockstream.info"

  // @formatter:off
  sealed trait Command
  case class CheckLatestHeaders(replyTo: ActorRef[BlockchainWatchdog.LatestHeaders]) extends Command
  private case class WrappedLatestHeaders(replyTo: ActorRef[LatestHeaders], headers: LatestHeaders) extends Command
  private case class WrappedFailure(e: Throwable) extends Command
  // @formatter:on

  def apply(chainHash: ByteVector32, currentBlockCount: Long, blockCountDelta: Int): Behavior[Command] = {
    Behaviors.setup { context =>
      implicit val executionContext: ExecutionContext = context.executionContext
      Behaviors.receiveMessage {
        case CheckLatestHeaders(replyTo) =>
          val uri_opt = chainHash match {
            case Block.LivenetGenesisBlock.hash => Some(uri"https://blockstream.info/api")
            case Block.TestnetGenesisBlock.hash => Some(uri"https://blockstream.info/testnet/api")
            case _ => None
          }
          uri_opt match {
            case Some(uri) =>
              context.pipeToSelf(getHeaders(uri, currentBlockCount, currentBlockCount + blockCountDelta - 1)) {
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
          context.log.error("blockstream.info failed: ", e)
          Metrics.WatchdogError.withTag(Tags.Source, Source).increment()
          Behaviors.stopped
      }
    }
  }

  private def getHeaders(baseUri: Uri, from: Long, to: Long)(implicit ec: ExecutionContext): Future[LatestHeaders] = {
    val chunks = (0 to (to - from).toInt / 10).map(i => getChunk(baseUri, to - 10 * i))
    Future.sequence(chunks).map(headers => LatestHeaders(from, headers.flatten.filter(_.blockCount >= from).toSet, Source))
  }

  /** blockstream.info returns chunks of 10 headers between ]startHeight-10; startHeight] */
  private def getChunk(baseUri: Uri, startHeight: Long)(implicit ec: ExecutionContext): Future[Seq[BlockHeaderAt]] = for {
    headers <- sttp.readTimeout(10 seconds).get(baseUri.path(baseUri.path :+ "blocks" :+ startHeight.toString))
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
  } yield headers

}
