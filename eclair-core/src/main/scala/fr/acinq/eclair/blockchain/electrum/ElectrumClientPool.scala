/*
 * Copyright 2018 ACINQ SAS
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

package fr.acinq.eclair.blockchain.electrum

import java.io.InputStream
import java.net.InetSocketAddress

import akka.actor.{Actor, ActorRef, FSM, OneForOneStrategy, Props, SupervisorStrategy, Terminated}
import fr.acinq.bitcoin.BlockHeader
import fr.acinq.eclair.Globals
import fr.acinq.eclair.blockchain.CurrentBlockCount
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.SSL
import fr.acinq.eclair.blockchain.electrum.ElectrumClientPool.ElectrumServerAddress
import org.json4s.JsonAST.{JObject, JString}
import org.json4s.jackson.JsonMethods

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Random

class ElectrumClientPool(serverAddresses: Set[ElectrumServerAddress])(implicit val ec: ExecutionContext) extends Actor with FSM[ElectrumClientPool.State, ElectrumClientPool.Data] {
  import ElectrumClientPool._

  val statusListeners = collection.mutable.HashSet.empty[ActorRef]
  val addresses = collection.mutable.Map.empty[ActorRef, InetSocketAddress]


  // on startup, we attempt to connect to a number of electrum clients
  // they will send us an `ElectrumReady` message when they're connected, or
  // terminate if they cannot connect
  (0 until Math.min(MAX_CONNECTION_COUNT, serverAddresses.size)) foreach (_ => self ! Connect)

  log.debug("starting electrum pool with serverAddresses={}", serverAddresses)

  // custom supervision strategy: always stop Electrum clients when there's a problem, we will automatically reconnect
  // to another client
  override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy(loggingEnabled = true) {
    case _ => SupervisorStrategy.stop
  }

  startWith(Disconnected, DisconnectedData)

  when(Disconnected) {
    case Event(ElectrumClient.ElectrumReady(height, tip, _), _) if addresses.contains(sender) =>
      sender ! ElectrumClient.HeaderSubscription(self)
      handleHeader(sender, height, tip, None)

    case Event(ElectrumClient.AddStatusListener(listener), _) =>
      statusListeners += listener
      stay

    case Event(Terminated(actor), _) =>
      log.info("lost connection to {}", addresses(actor))
      addresses -= actor
      context.system.scheduler.scheduleOnce(5 seconds, self, Connect)
      stay
  }

  when(Connected) {
    case Event(ElectrumClient.ElectrumReady(height, tip, _), d: ConnectedData) if addresses.contains(sender) =>
      sender ! ElectrumClient.HeaderSubscription(self)
      handleHeader(sender, height, tip, Some(d))

    case Event(ElectrumClient.HeaderSubscriptionResponse(height, tip), d: ConnectedData) if addresses.contains(sender) =>
      handleHeader(sender, height, tip, Some(d))

    case Event(request: ElectrumClient.Request, ConnectedData(master, _)) =>
      master forward request
      stay

    case Event(ElectrumClient.AddStatusListener(listener), d: ConnectedData) if addresses.contains(d.master) =>
      statusListeners += listener
      listener ! ElectrumClient.ElectrumReady(d.tips(d.master), addresses(d.master))
      stay

    case Event(Terminated(actor), d: ConnectedData) =>
      log.info("lost connection to {}", addresses(actor))
      addresses -= actor
      context.system.scheduler.scheduleOnce(5 seconds, self, Connect)
      val tips1 = d.tips - actor
      if (tips1.isEmpty) {
        goto(Disconnected) using DisconnectedData // no more connections
      } else if (d.master != actor) {
        stay using d.copy(tips = tips1) // we don't care, this wasn't our master
      } else {
        // we choose next best candidate as master
        val tips1 = d.tips - actor
        val (bestClient, bestTip) = tips1.toSeq.maxBy(_._2._1)
        handleHeader(bestClient, bestTip._1, bestTip._2, Some(d.copy(tips = tips1)))
      }
  }

  whenUnhandled {
    case Event(Connect, _) =>
      Random.shuffle(serverAddresses.toSeq diff addresses.values.toSeq).headOption match {
        case Some(ElectrumServerAddress(address, ssl)) =>
          val resolved = new InetSocketAddress(address.getHostName, address.getPort)
          val client = context.actorOf(Props(new ElectrumClient(resolved, ssl)))
          client ! ElectrumClient.AddStatusListener(self)
          // we watch each electrum client, they will stop on disconnection
          context watch client
          addresses += (client -> address)
        case None => () // no more servers available
      }
      stay

    case Event(ElectrumClient.ElectrumDisconnected, _) =>
      stay // ignored, we rely on Terminated messages to detect disconnections
  }

  onTransition {
    case Connected -> Disconnected =>
      statusListeners.foreach(_ ! ElectrumClient.ElectrumDisconnected)
      context.system.eventStream.publish(ElectrumClient.ElectrumDisconnected)
  }

  initialize()

  private def handleHeader(connection: ActorRef, height: Int, tip: BlockHeader, data: Option[ConnectedData]) = {
    val remoteAddress = addresses(connection)
    // we update our block count even if it doesn't come from our current master
    updateBlockCount(height)
    data match {
      case None =>
        // as soon as we have a connection to an electrum server, we select it as master
        log.info("selecting master {} at {}", remoteAddress, tip)
        statusListeners.foreach(_ ! ElectrumClient.ElectrumReady(height, tip, remoteAddress))
        context.system.eventStream.publish(ElectrumClient.ElectrumReady(height, tip, remoteAddress))
        goto(Connected) using ConnectedData(connection, Map(connection -> (height, tip)))
      case Some(d) if connection != d.master && height >= d.blockHeight + 2L =>
        // we only switch to a new master if there is a significant difference with our current master, because
        // we don't want to switch to a new master every time a new block arrives (some servers will be notified before others)
        // we check that the current connection is not our master because on regtest when you generate several blocks at once
        // (and maybe on testnet in some pathological cases where there's a block every second) it may seen like our master
        // skipped a block and is suddenly at height + 2
        log.info("switching to master {} at {}", remoteAddress, tip)
        // we've switched to a new master, treat this as a disconnection/reconnection
        // so users (wallet, watcher, ...) will reset their subscriptions
        statusListeners.foreach(_ ! ElectrumClient.ElectrumDisconnected)
        context.system.eventStream.publish(ElectrumClient.ElectrumDisconnected)
        statusListeners.foreach(_ ! ElectrumClient.ElectrumReady(height, tip, remoteAddress))
        context.system.eventStream.publish(ElectrumClient.ElectrumReady(height, tip, remoteAddress))
        goto(Connected) using d.copy(master = connection, tips = d.tips + (connection -> (height, tip)))
      case Some(d) =>
        log.debug("received tip {} from {} at {}", tip, remoteAddress, height)
        stay using d.copy(tips = d.tips + (connection -> (height, tip)))
    }
  }

  private def updateBlockCount(blockCount: Long): Unit = {
    // when synchronizing we don't want to advertise previous blocks
    if (Globals.blockCount.get() < blockCount) {
      log.debug("current blockchain height={}", blockCount)
      context.system.eventStream.publish(CurrentBlockCount(blockCount))
      Globals.blockCount.set(blockCount)
    }
  }
}

object ElectrumClientPool {

  val MAX_CONNECTION_COUNT = 3

  case class ElectrumServerAddress(adress: InetSocketAddress, ssl: SSL)

  /**
    * Parses default electrum server list and extract addresses
    *
    * @param stream
    * @param sslEnabled select plaintext/ssl ports
    * @return
    */
  def readServerAddresses(stream: InputStream, sslEnabled: Boolean): Set[ElectrumServerAddress] = try {
    val JObject(values) = JsonMethods.parse(stream)
    val addresses = values
        .toMap
        .filterKeys(!_.endsWith(".onion"))
        .flatMap {
      case (name, fields)  =>
        if (sslEnabled) {
          // We don't authenticate seed servers (SSL.LOOSE), because:
          // - we don't know them so authentication doesn't really bring anything
          // - most of them have self-signed SSL certificates so it would always fail
          fields \ "s" match {
            case JString(port) => Some(ElectrumServerAddress(InetSocketAddress.createUnresolved(name, port.toInt), SSL.LOOSE))
            case _ => None
          }
        } else {
          fields \ "t" match {
            case JString(port) => Some(ElectrumServerAddress(InetSocketAddress.createUnresolved(name, port.toInt), SSL.OFF))
            case _ => None
          }
        }
    }
    addresses.toSet
  } finally {
    stream.close()
  }

  // @formatter:off
  sealed trait State
  case object Disconnected extends State
  case object Connected extends State

  sealed trait Data
  case object DisconnectedData extends Data
  case class ConnectedData(master: ActorRef, tips: Map[ActorRef, (Int, BlockHeader)]) extends Data {
    def blockHeight = tips.get(master).map(_._1).getOrElse(0)
  }

  case object Connect
  // @formatter:on
}
