package fr.acinq.eclair.api

import akka.http.scaladsl.marshalling.ToResponseMarshaller
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{Directives, Route}

import scala.concurrent.Future
import scala.util.{Failure, Success}

trait ExtraDirectives extends Directives {

  // custom directive to fail with HTTP 404 if the element was not found
  def completeWithFutureOption[T](fut: Future[Option[T]])(implicit marshaller: ToResponseMarshaller[T]): Route = onComplete(fut) {
    case Success(Some(t)) => complete(t)
    case Success(None) => complete(StatusCodes.NotFound)
    case Failure(thr) => reject
  }

}
