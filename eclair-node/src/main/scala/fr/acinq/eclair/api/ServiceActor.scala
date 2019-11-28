package fr.acinq.eclair.api

import fr.acinq.eclair.Eclair
import spray.routing.HttpServiceActor

class ServiceActor(pass: String, eclair: Eclair) extends HttpServiceActor with Service {
  override val password = pass
  override val eclairApi = eclair
  override def receive: Receive = runRoute(route)
}