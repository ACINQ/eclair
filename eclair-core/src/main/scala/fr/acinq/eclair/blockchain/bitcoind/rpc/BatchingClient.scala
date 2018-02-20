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
      process(queue = Queue(Pending(request, sender)))
  }

  def waiting(queue: Queue[Pending], processing: Seq[Pending]): Receive = {
    case request: JsonRPCRequest =>
      // there is already a batch in flight, just add this request to the queue
      context become waiting(queue :+ Pending(request, sender), processing)

    case responses: Seq[JsonRPCResponse]@unchecked =>
      log.debug(s"got {} responses", responses.size)
      // let's send back answers to the requestors
      require(responses.size == processing.size, s"responses=${responses.size} != processing=${processing.size}")
      responses.zip(processing).foreach {
        case (JsonRPCResponse(result, None, _), Pending(_, requestor)) => requestor ! result
        case (JsonRPCResponse(_, Some(error), _), Pending(_, requestor)) => requestor ! Status.Failure(JsonRPCError(error))
      }
      process(queue)

    case s@Status.Failure(t) =>
      log.error(t, s"got exception for batch of ${processing.size} requests")
      // let's fail all requests
      processing.foreach { case Pending(_, requestor) => requestor ! s }
      process(queue)
  }

  def process(queue: Queue[Pending]) = {
    // do we have queued requests?
    if (queue.isEmpty) {
      log.debug(s"no more requests, going back to idle")
      context become receive
    } else {
      val (batch, rest) = queue.splitAt(BatchingClient.BATCH_SIZE)
      log.debug(s"sending {} request(s): {} (queue={})", batch.size, batch.groupBy(_.request.method).map(e => e._1 + "=" + e._2.size).mkString(" "), queue.size)
      rpcClient.invoke(batch.map(_.request)) pipeTo self
      context become waiting(rest, batch)
    }
  }

}

object BatchingClient {

  val BATCH_SIZE = 50

  case class Pending(request: JsonRPCRequest, requestor: ActorRef)

}