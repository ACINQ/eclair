/*
 * Copyright 2019 ACINQ SAS
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

package fr.acinq.eclair.blockchain.bitcoind.rpc

import akka.actor.{Actor, ActorLogging, ActorRef, Status}
import akka.pattern.pipe
import fr.acinq.eclair.blockchain.bitcoind.rpc.BatchingClient.Pending

import scala.collection.immutable.Queue

class BatchingClient(rpcClient: BasicBitcoinJsonRPCClient) extends Actor with ActorLogging {

  import scala.concurrent.ExecutionContext.Implicits.global

  override def receive: Receive = {
    case request: JsonRPCRequest =>
      // immediately process isolated request
      process(queue = Queue(Pending(request, sender())))
  }

  def waiting(queue: Queue[Pending], processing: Seq[Pending]): Receive = {
    case request: JsonRPCRequest =>
      // there is already a batch in flight, just add this request to the queue
      context become waiting(queue :+ Pending(request, sender()), processing)

    case responses: Seq[JsonRPCResponse] @unchecked =>
      log.debug("got {} responses", responses.size)
      val keyedResponses = responses.map(r => r.id -> r).toMap
      processing.foreach(pending => keyedResponses.get(pending.request.id) match {
        case Some(response) => response.error match {
          case None => pending.requestor ! response.result
          case Some(error) => pending.requestor ! Status.Failure(JsonRPCError(error))
        }
        case None =>
          log.warning("response missing for requestId={} method={}", pending.request.id, pending.request.method)
          pending.requestor ! Status.Failure(new RuntimeException("no response from bitcoind"))
      })
      process(queue)

    case s@Status.Failure(t) =>
      log.error(s"got exception for batch of ${processing.size} requests: ${t.getMessage}")
      // We don't know what caused the failure at that point, and we cannot figure our which requests succeeded (if any)
      // and which failed. We tell requestors that all requests in the batch have failed, but it may be misleading.
      processing.foreach { case Pending(_, requestor) => requestor ! s }
      process(queue)
  }

  def process(queue: Queue[Pending]): Unit = {
    if (queue.isEmpty) {
      log.debug("no more requests, going back to idle")
      context become receive
    } else {
      val (batch, rest) = queue.splitAt(BatchingClient.BATCH_SIZE)
      log.debug(s"sending {} request(s): {} (queue={})", batch.size, batch.groupBy(_.request.method).map(e => e._1 + "=" + e._2.size).mkString(" "), queue.size)
      rpcClient.invoke(batch.map(_.request)).pipeTo(self)
      context become waiting(rest, batch)
    }
  }

}

object BatchingClient {

  private val BATCH_SIZE = 50

  case class Pending(request: JsonRPCRequest, requestor: ActorRef)

}