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

import akka.http.scaladsl.server.{MalformedFormFieldRejection, Route}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.api.Service
import fr.acinq.eclair.api.directives.EclairDirectives
import fr.acinq.eclair.api.serde.FormParamExtractors._
import fr.acinq.eclair.crypto.Sphinx
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
    formFields("msg".as[ByteVector](base64DataUnmarshaller), "sig".as[ByteVector](bytesUnmarshaller)) { (message, signature) =>
      complete(eclairApi.verifyMessage(message, signature))
    }
  }

  val sendOnionMessage: Route = postRequest("sendonionmessage") { implicit t =>
    formFields(
      "recipientNode".as[PublicKey](publicKeyUnmarshaller).?,
      "recipientBlindedRoute".as[Sphinx.RouteBlinding.BlindedRoute](blindedRouteUnmarshaller).?,
      "intermediateNodes".as[List[PublicKey]](pubkeyListUnmarshaller).?,
      "replyPath".as[List[PublicKey]](pubkeyListUnmarshaller).?,
      "content".as[ByteVector](bytesUnmarshaller)) {
      case (Some(recipientNode), None, intermediateNodes, replyPath, userCustomContent) =>
        complete(
          eclairApi.sendOnionMessage(intermediateNodes.getOrElse(Nil),
            Left(recipientNode),
            replyPath,
            userCustomContent))
      case (None, Some(recipientBlindedRoute), intermediateNodes, replyPath, userCustomContent) =>
        complete(
          eclairApi.sendOnionMessage(intermediateNodes.getOrElse(Nil),
            Right(recipientBlindedRoute),
            replyPath,
            userCustomContent))
      case (None, None, _, _, _) =>
        reject(MalformedFormFieldRejection("recipientNode", "You must provide recipientNode or recipientBlindedRoute"))
      case (Some(_), Some(_), _, _, _) =>
        reject(MalformedFormFieldRejection("recipientNode", "Only one of recipientNode and recipientBlindedRoute must be provided"))
    }
  }

  val messageRoutes: Route = signMessage ~ verifyMessage ~ sendOnionMessage
}
