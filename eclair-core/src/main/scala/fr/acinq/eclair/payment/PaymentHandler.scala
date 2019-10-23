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

package fr.acinq.eclair.payment

import akka.actor.{Actor, ActorLogging, Props}
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.payment.handlers.{MultiPartHandler, ReceiveHandler}

/**
 * Generic payment handler that delegates handling of incoming messages to a list of handlers.
 *
 * @param nodeParams
 */
class PaymentHandler(nodeParams: NodeParams) extends Actor with ActorLogging {

  override def receive: Receive = {
    val defaultHandler = new MultiPartHandler(nodeParams, nodeParams.db.payments)
    normal(Seq(defaultHandler), defaultHandler.handle(context, log))
  }

  def normal(handlers: Seq[ReceiveHandler], handle: Receive): Receive = {

    case handler: ReceiveHandler =>
      log.info(s"registering handler of type=${handler.getClass.getSimpleName}")
      // NB: the last handler that was added will be the first called
      context become normal(handler +: handlers, handler.handle(context, log) orElse handle)

    case msg => handle(msg)
  }

}

object PaymentHandler {

  def props(nodeParams: NodeParams): Props = Props(new PaymentHandler(nodeParams))

}
