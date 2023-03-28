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
import fr.acinq.bitcoin.scalacompat.Satoshi
import fr.acinq.eclair.api.Service
import fr.acinq.eclair.api.directives.EclairDirectives
import fr.acinq.eclair.api.serde.FormParamExtractors._
import fr.acinq.eclair.blockchain.fee.FeeratePerByte
import org.json4s.{JArray, JBool, JObject, JString}

trait OnChain {
  this: Service with EclairDirectives =>

  import fr.acinq.eclair.api.serde.JsonSupport.{formats, marshaller, serialization}

  val getNewAddress: Route = postRequest("getnewaddress") { implicit t =>
    complete(eclairApi.newAddress())
  }

  val sendOnChain: Route = postRequest("sendonchain") { implicit t =>
    formFields("address".as[String], "amountSatoshis".as[Satoshi], "confirmationTarget".as[Long]) {
      (address, amount, confirmationTarget) =>
        complete(eclairApi.sendOnChain(address, amount, confirmationTarget))
    }
  }

  val cpfpBumpFees: Route = postRequest("cpfpbumpfees") { implicit t =>
    formFields("targetFeerateSatByte".as[FeeratePerByte], outPointsFormParam) {
      (targetFeerate, outPoints) =>
        complete(eclairApi.cpfpBumpFees(targetFeerate, outPoints.toSet))
    }
  }

  val onChainBalance: Route = postRequest("onchainbalance") { implicit t =>
    complete(eclairApi.onChainBalance())
  }

  val onChainTransactions: Route = postRequest("onchaintransactions") { implicit t =>
    withPaginated { paginated_opt =>
      formFields(countFormParam, skipFormParam) { (count_opt, skip_opt) =>
        val count = paginated_opt.map(_.count).getOrElse(10)
        val skip = paginated_opt.map(_.skip).getOrElse(0)
        complete(eclairApi.onChainTransactions(count, skip))
      }
    }
  }

  val globalBalance: Route = postRequest("globalbalance") { implicit t =>
    complete(eclairApi.globalBalance())
  }

  val getmasterxpub: Route = postRequest("getmasterxpub") { implicit t =>
    formFields("account".as[Long]) { account =>
      val xpub = this.eclairApi.getOnchainMasterPubKey(account)
      complete(new JObject(List("xpub" -> JString(xpub))))
    }
  }

  val getdescriptors: Route = postRequest("getdescriptors") { implicit t =>
    formFields("account".as[Long].?) {
      (account_opt) =>
        val (receiveDescs, internalDescs) = this.eclairApi.getDescriptors(account_opt.getOrElse(0L))

        // format JSON result to be compatible with Bitcoin Core's importdescriptors RPC call
        val receive = receiveDescs.map(desc => JObject(
          "desc" -> JString(desc),
          "active" -> JBool(true),
          "timestamp" -> JString("now")
        ))
        val change = internalDescs.map(desc => JObject(
          "desc" -> JString(desc),
          "active" -> JBool(true),
          "timestamp" -> JString("now"),
          "internal" -> JBool(true)
        ))
        val json = JArray(receive ++ change)
        complete(json)
    }
  }

  val onChainRoutes: Route = getNewAddress ~ sendOnChain ~ cpfpBumpFees ~ onChainBalance ~ onChainTransactions ~ globalBalance ~ getmasterxpub ~ getdescriptors

}
