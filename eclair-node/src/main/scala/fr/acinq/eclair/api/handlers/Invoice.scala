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

package fr.acinq.eclair.api.handlers

import akka.http.scaladsl.server.Route
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.api.Service
import fr.acinq.eclair.api.directives.EclairDirectives
import fr.acinq.eclair.api.serde.FormParamExtractors._

trait Invoice {
  this: Service with EclairDirectives =>

  import fr.acinq.eclair.api.serde.JsonSupport.{formats, marshaller, serialization}

  val createInvoice: Route = postRequest("createinvoice") { implicit t =>
    formFields("description".as[String].?, "descriptionHash".as[ByteVector32].?, amountMsatFormParam.?, "expireIn".as[Long].?, "fallbackAddress".as[String].?, "paymentPreimage".as[ByteVector32](bytes32Unmarshaller).?) {
      case (Some(desc), None, amountMsat, expire, fallBackAddress, paymentPreimage_opt) => complete(eclairApi.receive(Left(desc), amountMsat, expire, fallBackAddress, paymentPreimage_opt))
      case (None, Some(desc), amountMsat, expire, fallBackAddress, paymentPreimage_opt) => complete(eclairApi.receive(Right(desc), amountMsat, expire, fallBackAddress, paymentPreimage_opt))
      case _ => failWith(new RuntimeException("Either 'description' (string) or 'descriptionHash' (sha256 hash of description string) must be supplied"))
    }
  }

  val getInvoice: Route = postRequest("getinvoice") { implicit t =>
    formFields(paymentHashFormParam) { paymentHash =>
      completeOrNotFound(eclairApi.getInvoice(paymentHash))
    }
  }

  val listInvoices: Route = postRequest("listinvoices") { implicit t =>
    withPaginated { paginated_opt =>
      formFields(fromFormParam(), toFormParam()) { (from, to) =>
        complete(eclairApi.allInvoices(from, to, paginated_opt))
      }
    }
  }

  val listPendingInvoices: Route = postRequest("listpendinginvoices") { implicit t =>
    withPaginated { paginated_opt =>
      formFields(fromFormParam(), toFormParam()) { (from, to) =>
        complete(eclairApi.pendingInvoices(from, to, paginated_opt))
      }
    }
  }

  val parseInvoice: Route = postRequest("parseinvoice") { implicit t =>
    formFields(invoiceFormParam) { invoice =>
      complete(invoice)
    }
  }

  val deleteInvoice: Route = postRequest("deleteinvoice") { implicit t =>
    formFields(paymentHashFormParam) { paymentHash =>
      complete(eclairApi.deleteInvoice(paymentHash))
    }
  }

  val invoiceRoutes: Route = createInvoice ~ getInvoice ~ listInvoices ~ listPendingInvoices ~ parseInvoice ~ deleteInvoice

}
