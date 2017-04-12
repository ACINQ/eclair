package fr.acinq.eclair.router

/**
  * Created by PM on 12/04/2017.
  */

class RouterException(message: String) extends RuntimeException(message)

object RouteNotFound extends RouterException("Route not found")
object CannotRouteToSelf extends RouterException("Cannot route to self")
