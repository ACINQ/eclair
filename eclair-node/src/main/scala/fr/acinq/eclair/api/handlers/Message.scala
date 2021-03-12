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
import fr.acinq.eclair.api.Service
import fr.acinq.eclair.api.directives.EclairDirectives
import fr.acinq.eclair.api.serde.FormParamExtractors._
import scodec.bits.ByteVector

trait Message {
  this: Service with EclairDirectives =>

  import fr.acinq.eclair.api.serde.JsonSupport.{formats, marshaller, serialization}

  val signMessage: Route = postRequest("signmessage") { implicit t =>
    formFields("msg".as[ByteVector](base64DataUnmarshaller)) { message =>
      complete(eclairApi.signMessage(message))
    }
  }

  val verifyMessage: Route = postRequest("verifymessage") { implicit t =>
    formFields("msg".as[ByteVector](base64DataUnmarshaller), "sig".as[ByteVector](binaryDataUnmarshaller)) { (message, signature) =>
      complete(eclairApi.verifyMessage(message, signature))
    }
  }

  val messageRoutes: Route = signMessage ~ verifyMessage
}
