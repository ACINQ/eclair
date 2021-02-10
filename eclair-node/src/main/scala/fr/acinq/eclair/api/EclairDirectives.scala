package fr.acinq.eclair.api

import akka.http.scaladsl.server.{Directive0, Directive1, Directives}
import akka.util.Timeout
import fr.acinq.eclair.api.directives._

import scala.concurrent.duration.DurationInt

class EclairDirectives extends Directives with TimeoutDirective with ErrorDirective
  with AuthDirective with DefaultHeaders with ExtraDirectives { this: Service =>

  /**
   * Prepares inner routes to be exposed as public API with default headers and
   * basic authentication.
   */
  def securedPublicHandler:Directive0 = eclairHeaders & authenticated

  /**
   * Wraps inner route with Exception/Rejection handlers and provides a Timeout
   * to the inner route either from request param or the default.
   */
  def standardHandler:Directive1[Timeout] = toStrictEntity(5 seconds) & handled & withTimeout

  /**
   * Handles POST requests with given simple path. The inner route is wrapped in a
   * standard handler and provides a Timeout as parameter.
   */
  def postRequest(p:String):Directive1[Timeout] = post & path(p) & standardHandler

  /**
   * Handles GET requests with given simple path. The inner route is wrapped in a
   * standard handler and provides a Timeout as parameter.
   */
  def getRequest(p:String):Directive1[Timeout] = get & path(p) & standardHandler

}
