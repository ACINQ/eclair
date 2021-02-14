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
import com.google.common.net.HostAndPort
import fr.acinq.eclair.api.Service
import fr.acinq.eclair.api.directives.EclairDirectives
import fr.acinq.eclair.io.NodeURI
import fr.acinq.eclair.api.serde.FormParamExtractors._

trait Node {
  this: Service with EclairDirectives =>

  import fr.acinq.eclair.api.serde.JsonSupport.{formats, marshaller, serialization}

  val getInfo: Route = postRequest("getinfo") { implicit t =>
    complete(eclairApi.getInfo())
  }

  val peers: Route = postRequest("peers") { implicit t =>
    complete(eclairApi.peers())
  }

  val connect: Route = postRequest("connect") { implicit t =>
    formFields("uri".as[NodeURI]) { uri =>
      complete(eclairApi.connect(Left(uri)))
    } ~ formFields(nodeIdFormParam, "host".as[String], "port".as[Int].?) { (nodeId, host, port_opt) =>
      complete {
        eclairApi.connect(
          Left(NodeURI(nodeId, HostAndPort.fromParts(host, port_opt.getOrElse(NodeURI.DEFAULT_PORT))))
        )
      }
    } ~ formFields(nodeIdFormParam) { nodeId =>
      complete(eclairApi.connect(Right(nodeId)))
    }
  }

  val disconnect: Route = postRequest("disconnect") { implicit t =>
    formFields(nodeIdFormParam) { nodeId =>
      complete(eclairApi.disconnect(nodeId))
    }
  }

  val findRouteToNode: Route = postRequest("findroutetonode") { implicit t =>
    formFields(nodeIdFormParam, amountMsatFormParam) { (nodeId, amount) =>
      complete(eclairApi.findRoute(nodeId, amount))
    }
  }

  val networkStats: Route = postRequest("networkstats") { implicit t =>
    complete(eclairApi.networkStats())
  }

  val nodes: Route = postRequest("nodes") { implicit t =>
    formFields(nodeIdsFormParam.?) { nodeIds_opt =>
      complete(eclairApi.nodes(nodeIds_opt.map(_.toSet)))
    }
  }

  val allUpdates: Route = postRequest("allupdates") { implicit t =>
    formFields(nodeIdFormParam.?) { nodeId_opt =>
      complete(eclairApi.allUpdates(nodeId_opt))
    }
  }

  val nodeRoutes: Route = getInfo ~ peers ~ connect ~ disconnect ~ networkStats ~ findRouteToNode ~ nodes ~ allUpdates
}
