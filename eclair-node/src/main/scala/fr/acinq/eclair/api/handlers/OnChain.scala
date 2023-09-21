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
import org.json4s.{JObject, JString}

trait OnChain {
  this: Service with EclairDirectives =>

  import fr.acinq.eclair.api.serde.JsonSupport.{formats, marshaller, serialization}

  val getNewAddress: Route = postRequest("getnewaddress") { implicit t =>
    complete(eclairApi.newAddress())
  }

  val sendOnChain: Route = postRequest("sendonchain") { implicit t =>
    formFields("address".as[String], "amountSatoshis".as[Satoshi], "confirmationTarget".as[Long].?, "feeRatePerByte".as[Int].?) {
      (address, amount, confirmationTarget_opt, feeratePerByte_opt) => {
        val confirmationTargetOrFeerate = (feeratePerByte_opt, confirmationTarget_opt) match {
          case (Some(feeratePerByte), _) => Right(FeeratePerByte(Satoshi(feeratePerByte)))
          case (None, Some(confirmationTarget)) => Left(confirmationTarget)
          case _ => throw new IllegalArgumentException("You must provide a confirmation target (in blocks) or a fee rate (in sat/vb)")
        }
        complete(eclairApi.sendOnChain(address, amount, confirmationTargetOrFeerate))
      }
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
    formFields("account".as[Long].?) { account_opt =>
      val xpub = eclairApi.getOnChainMasterPubKey(account_opt.getOrElse(0L))
      complete(new JObject(List("xpub" -> JString(xpub))))
    }
  }

  val getdescriptors: Route = postRequest("getdescriptors") { implicit t =>
    formFields("account".as[Long].?) { account_opt =>
        val descriptors = eclairApi.getDescriptors(account_opt.getOrElse(0L))
        complete(descriptors.descriptors)
    }
  }

  val onChainRoutes: Route = getNewAddress ~ sendOnChain ~ cpfpBumpFees ~ onChainBalance ~ onChainTransactions ~ globalBalance ~ getmasterxpub ~ getdescriptors

}
