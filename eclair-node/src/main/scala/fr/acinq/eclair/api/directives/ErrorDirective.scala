package fr.acinq.eclair.api.directives

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.{Directive0, ExceptionHandler, RejectionHandler}
import fr.acinq.eclair.api.{EclairDirectives, ErrorResponse, JsonSupport, Service}

trait ErrorDirective {
  this: Service with EclairDirectives =>

  /**
   * Handles API exceptions and rejections. Produces json formatted
   * error responses.
   */
  def handled: Directive0 = handleExceptions(apiExceptionHandler) &
    handleRejections(apiRejectionHandler)


  import JsonSupport.{formats, marshaller, serialization}

  private val apiExceptionHandler = ExceptionHandler {
    case t: IllegalArgumentException =>
      logger.error(s"API call failed with cause=${t.getMessage}", t)
      complete(StatusCodes.BadRequest, ErrorResponse(t.getMessage))
    case t: Throwable =>
      logger.error(s"API call failed with cause=${t.getMessage}", t)
      complete(StatusCodes.InternalServerError, ErrorResponse(t.getMessage))
  }

  // map all the rejections to a JSON error object ErrorResponse
  private val apiRejectionHandler = RejectionHandler.default.mapRejectionResponse {
    case res@HttpResponse(_, _, ent: HttpEntity.Strict, _) =>
      res.withEntity(
        HttpEntity(ContentTypes.`application/json`, serialization.writePretty(ErrorResponse(ent.data.utf8String)))
      )
  }

}
