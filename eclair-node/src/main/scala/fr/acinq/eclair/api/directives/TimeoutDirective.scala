package fr.acinq.eclair.api.directives

import akka.http.scaladsl.model.{ContentTypes, HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.{Directive0, Directive1, Directives}
import fr.acinq.eclair.api.{ErrorResponse, JsonSupport}
import akka.util.Timeout
import fr.acinq.eclair.api.FormParamExtractors._
import fr.acinq.eclair.api.JsonSupport._

import scala.concurrent.duration.DurationInt

trait TimeoutDirective extends Directives {

  import JsonSupport.{formats, serialization}


  /**
   * Extracts a given request timeout from an optional form field. Provides either the
   * extracted Timeout or a default Timeout to the inner route.
   */
  def withTimeout:Directive1[Timeout] = extractTimeout.tflatMap { timeout =>
    withTimeoutRequest(timeout._1) & provide(timeout._1)
  }

  private val timeoutResponse: HttpRequest => HttpResponse = { _ =>
    HttpResponse(StatusCodes.RequestTimeout).withEntity(
      ContentTypes.`application/json`, serialization.writePretty(ErrorResponse("request timed out"))
    )
  }

  private def withTimeoutRequest(t: Timeout): Directive0 = withRequestTimeout(t.duration + 2.seconds) &
    withRequestTimeoutResponse(timeoutResponse)

  private def extractTimeout: Directive1[Timeout] = formField("timeoutSeconds".as[Timeout].?).tflatMap { opt =>
    provide(opt._1.getOrElse(Timeout(30 seconds)))
  }

}
