/*
 * Copyright 2018 ACINQ SAS
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

package fr.acinq.eclair.blockchain.fee

import akka.util.Timeout
import com.softwaremill.sttp.okhttp.OkHttpFutureBackend
import grizzled.slf4j.Logging
import org.json4s.DefaultFormats
import org.scalatest.FunSuite

import scala.concurrent.Await

/**
  * Created by PM on 27/01/2017.
  */

class EarnDotComFeeProviderSpec extends FunSuite with Logging {

  import EarnDotComFeeProvider._
  import org.json4s.jackson.JsonMethods.parse

  implicit val formats = DefaultFormats

  val sample_response =
    """
      {"fees":[{"minFee":0,"maxFee":0,"dayCount":10,"memCount":0,"minDelay":83,"maxDelay":10000,"minMinutes":600,"maxMinutes":10000},{"minFee":1,"maxFee":10,"dayCount":31871,"memCount":29690,"minDelay":3,"maxDelay":118,"minMinutes":30,"maxMinutes":1320},{"minFee":11,"maxFee":20,"dayCount":8272,"memCount":7261,"minDelay":3,"maxDelay":118,"minMinutes":30,"maxMinutes":1320},{"minFee":21,"maxFee":30,"dayCount":13817,"memCount":12781,"minDelay":3,"maxDelay":89,"minMinutes":30,"maxMinutes":1080},{"minFee":31,"maxFee":40,"dayCount":8651,"memCount":4484,"minDelay":3,"maxDelay":54,"minMinutes":25,"maxMinutes":660},{"minFee":41,"maxFee":50,"dayCount":18306,"memCount":2152,"minDelay":3,"maxDelay":38,"minMinutes":15,"maxMinutes":420},{"minFee":51,"maxFee":60,"dayCount":16592,"memCount":1699,"minDelay":3,"maxDelay":21,"minMinutes":10,"maxMinutes":300},{"minFee":61,"maxFee":70,"dayCount":5351,"memCount":711,"minDelay":3,"maxDelay":19,"minMinutes":5,"maxMinutes":300},{"minFee":71,"maxFee":80,"dayCount":4138,"memCount":350,"minDelay":3,"maxDelay":18,"minMinutes":5,"maxMinutes":300},{"minFee":81,"maxFee":90,"dayCount":3190,"memCount":584,"minDelay":2,"maxDelay":18,"minMinutes":5,"maxMinutes":300},{"minFee":91,"maxFee":100,"dayCount":3738,"memCount":596,"minDelay":2,"maxDelay":16,"minMinutes":5,"maxMinutes":240},{"minFee":101,"maxFee":110,"dayCount":1649,"memCount":348,"minDelay":1,"maxDelay":14,"minMinutes":0,"maxMinutes":240},{"minFee":111,"maxFee":120,"dayCount":1622,"memCount":252,"minDelay":1,"maxDelay":13,"minMinutes":0,"maxMinutes":180},{"minFee":121,"maxFee":130,"dayCount":1562,"memCount":106,"minDelay":1,"maxDelay":13,"minMinutes":0,"maxMinutes":180},{"minFee":131,"maxFee":140,"dayCount":2386,"memCount":165,"minDelay":1,"maxDelay":12,"minMinutes":0,"maxMinutes":180},{"minFee":141,"maxFee":150,"dayCount":2008,"memCount":217,"minDelay":1,"maxDelay":11,"minMinutes":0,"maxMinutes":180},{"minFee":151,"maxFee":160,"dayCount":1594,"memCount":136,"minDelay":1,"maxDelay":10,"minMinutes":0,"maxMinutes":180},{"minFee":161,"maxFee":170,"dayCount":1415,"memCount":65,"minDelay":1,"maxDelay":10,"minMinutes":0,"maxMinutes":180},{"minFee":171,"maxFee":180,"dayCount":1533,"memCount":169,"minDelay":1,"maxDelay":10,"minMinutes":0,"maxMinutes":180},{"minFee":181,"maxFee":190,"dayCount":1569,"memCount":121,"minDelay":1,"maxDelay":9,"minMinutes":0,"maxMinutes":180},{"minFee":191,"maxFee":200,"dayCount":5824,"memCount":205,"minDelay":1,"maxDelay":8,"minMinutes":0,"maxMinutes":120},{"minFee":201,"maxFee":210,"dayCount":3028,"memCount":142,"minDelay":0,"maxDelay":7,"minMinutes":0,"maxMinutes":110},{"minFee":211,"maxFee":220,"dayCount":1521,"memCount":104,"minDelay":0,"maxDelay":7,"minMinutes":0,"maxMinutes":110},{"minFee":221,"maxFee":230,"dayCount":2057,"memCount":249,"minDelay":0,"maxDelay":6,"minMinutes":0,"maxMinutes":100},{"minFee":231,"maxFee":240,"dayCount":1429,"memCount":86,"minDelay":0,"maxDelay":6,"minMinutes":0,"maxMinutes":100},{"minFee":241,"maxFee":250,"dayCount":2351,"memCount":87,"minDelay":0,"maxDelay":6,"minMinutes":0,"maxMinutes":100},{"minFee":251,"maxFee":260,"dayCount":3748,"memCount":90,"minDelay":0,"maxDelay":5,"minMinutes":0,"maxMinutes":90},{"minFee":261,"maxFee":270,"dayCount":3518,"memCount":154,"minDelay":0,"maxDelay":5,"minMinutes":0,"maxMinutes":80},{"minFee":271,"maxFee":280,"dayCount":1731,"memCount":86,"minDelay":0,"maxDelay":4,"minMinutes":0,"maxMinutes":80},{"minFee":281,"maxFee":290,"dayCount":1467,"memCount":101,"minDelay":0,"maxDelay":4,"minMinutes":0,"maxMinutes":80},{"minFee":291,"maxFee":300,"dayCount":5799,"memCount":161,"minDelay":0,"maxDelay":3,"minMinutes":0,"maxMinutes":70},{"minFee":301,"maxFee":310,"dayCount":4390,"memCount":147,"minDelay":0,"maxDelay":3,"minMinutes":0,"maxMinutes":70},{"minFee":311,"maxFee":320,"dayCount":2655,"memCount":78,"minDelay":0,"maxDelay":3,"minMinutes":0,"maxMinutes":60},{"minFee":321,"maxFee":330,"dayCount":1422,"memCount":44,"minDelay":0,"maxDelay":3,"minMinutes":0,"maxMinutes":60},{"minFee":331,"maxFee":340,"dayCount":1328,"memCount":27,"minDelay":0,"maxDelay":3,"minMinutes":0,"maxMinutes":60},{"minFee":341,"maxFee":350,"dayCount":1725,"memCount":43,"minDelay":0,"maxDelay":2,"minMinutes":0,"maxMinutes":60},{"minFee":351,"maxFee":360,"dayCount":2527,"memCount":77,"minDelay":0,"maxDelay":2,"minMinutes":0,"maxMinutes":55},{"minFee":361,"maxFee":370,"dayCount":2130,"memCount":37,"minDelay":0,"maxDelay":2,"minMinutes":0,"maxMinutes":55},{"minFee":371,"maxFee":380,"dayCount":2038,"memCount":46,"minDelay":0,"maxDelay":2,"minMinutes":0,"maxMinutes":50},{"minFee":381,"maxFee":390,"dayCount":3248,"memCount":58,"minDelay":0,"maxDelay":2,"minMinutes":0,"maxMinutes":45},{"minFee":391,"maxFee":400,"dayCount":7721,"memCount":37,"minDelay":0,"maxDelay":1,"minMinutes":0,"maxMinutes":40},{"minFee":401,"maxFee":410,"dayCount":6337,"memCount":19,"minDelay":0,"maxDelay":1,"minMinutes":0,"maxMinutes":40},{"minFee":411,"maxFee":420,"dayCount":7327,"memCount":96,"minDelay":0,"maxDelay":1,"minMinutes":0,"maxMinutes":40},{"minFee":421,"maxFee":430,"dayCount":4298,"memCount":21,"minDelay":0,"maxDelay":1,"minMinutes":0,"maxMinutes":40},{"minFee":431,"maxFee":440,"dayCount":10088,"memCount":63,"minDelay":0,"maxDelay":1,"minMinutes":0,"maxMinutes":40},{"minFee":441,"maxFee":450,"dayCount":6246,"memCount":33,"minDelay":0,"maxDelay":1,"minMinutes":0,"maxMinutes":40},{"minFee":451,"maxFee":460,"dayCount":8231,"memCount":19,"minDelay":0,"maxDelay":1,"minMinutes":0,"maxMinutes":35},{"minFee":461,"maxFee":470,"dayCount":3725,"memCount":19,"minDelay":0,"maxDelay":1,"minMinutes":0,"maxMinutes":35},{"minFee":471,"maxFee":480,"dayCount":2903,"memCount":21,"minDelay":0,"maxDelay":1,"minMinutes":0,"maxMinutes":35},{"minFee":481,"maxFee":490,"dayCount":3086,"memCount":11,"minDelay":0,"maxDelay":1,"minMinutes":0,"maxMinutes":30},{"minFee":491,"maxFee":500,"dayCount":8870,"memCount":14,"minDelay":0,"maxDelay":1,"minMinutes":0,"maxMinutes":30},{"minFee":501,"maxFee":510,"dayCount":33705,"memCount":105,"minDelay":0,"maxDelay":0,"minMinutes":0,"maxMinutes":30},{"minFee":511,"maxFee":520,"dayCount":2373,"memCount":17,"minDelay":0,"maxDelay":0,"minMinutes":0,"maxMinutes":30},{"minFee":521,"maxFee":530,"dayCount":2457,"memCount":8,"minDelay":0,"maxDelay":0,"minMinutes":0,"maxMinutes":30},{"minFee":531,"maxFee":540,"dayCount":2275,"memCount":4,"minDelay":0,"maxDelay":0,"minMinutes":0,"maxMinutes":30},{"minFee":541,"maxFee":550,"dayCount":1412,"memCount":5,"minDelay":0,"maxDelay":0,"minMinutes":0,"maxMinutes":30},{"minFee":551,"maxFee":560,"dayCount":11752,"memCount":4,"minDelay":0,"maxDelay":0,"minMinutes":0,"maxMinutes":30},{"minFee":561,"maxFee":570,"dayCount":1370,"memCount":2,"minDelay":0,"maxDelay":0,"minMinutes":0,"maxMinutes":30},{"minFee":571,"maxFee":580,"dayCount":2743,"memCount":3,"minDelay":0,"maxDelay":0,"minMinutes":0,"maxMinutes":30},{"minFee":581,"maxFee":590,"dayCount":1741,"memCount":4,"minDelay":0,"maxDelay":0,"minMinutes":0,"maxMinutes":30},{"minFee":591,"maxFee":600,"dayCount":1398,"memCount":6,"minDelay":0,"maxDelay":0,"minMinutes":0,"maxMinutes":30},{"minFee":601,"maxFee":610,"dayCount":1602,"memCount":4,"minDelay":0,"maxDelay":0,"minMinutes":0,"maxMinutes":30},{"minFee":611,"maxFee":620,"dayCount":891,"memCount":9,"minDelay":0,"maxDelay":0,"minMinutes":0,"maxMinutes":30},{"minFee":621,"maxFee":630,"dayCount":1284,"memCount":7,"minDelay":0,"maxDelay":0,"minMinutes":0,"maxMinutes":30},{"minFee":631,"maxFee":640,"dayCount":1543,"memCount":4,"minDelay":0,"maxDelay":0,"minMinutes":0,"maxMinutes":30},{"minFee":641,"maxFee":650,"dayCount":683,"memCount":6,"minDelay":0,"maxDelay":0,"minMinutes":0,"maxMinutes":30},{"minFee":651,"maxFee":660,"dayCount":717,"memCount":4,"minDelay":0,"maxDelay":0,"minMinutes":0,"maxMinutes":30},{"minFee":661,"maxFee":670,"dayCount":656,"memCount":5,"minDelay":0,"maxDelay":0,"minMinutes":0,"maxMinutes":30},{"minFee":671,"maxFee":680,"dayCount":564,"memCount":4,"minDelay":0,"maxDelay":0,"minMinutes":0,"maxMinutes":30},{"minFee":681,"maxFee":690,"dayCount":1126,"memCount":3,"minDelay":0,"maxDelay":0,"minMinutes":0,"maxMinutes":30},{"minFee":691,"maxFee":700,"dayCount":9357,"memCount":25,"minDelay":0,"maxDelay":0,"minMinutes":0,"maxMinutes":30},{"minFee":701,"maxFee":710,"dayCount":10161,"memCount":34,"minDelay":0,"maxDelay":0,"minMinutes":0,"maxMinutes":30},{"minFee":711,"maxFee":720,"dayCount":2206,"memCount":20,"minDelay":0,"maxDelay":0,"minMinutes":0,"maxMinutes":30},{"minFee":721,"maxFee":50710,"dayCount":17883,"memCount":142,"minDelay":0,"maxDelay":0,"minMinutes":0,"maxMinutes":30}]}
    """

  test("parse test") {
    val json = parse(sample_response)
    val feeRanges = parseFeeRanges(json)
    assert(feeRanges.size === 74)
  }

  test("extract fee for a particular block delay") {
    val json = parse(sample_response)
    val feeRanges = parseFeeRanges(json)
    val fee = extractFeerate(feeRanges, 6)
    assert(fee === 230 * 1000)
  }

  test("extract all fees") {
    val json = parse(sample_response)
    val feeRanges = parseFeeRanges(json)
    val feerates = extractFeerates(feeRanges)
    val ref = FeeratesPerKB(
      block_1 = 400 * 1000,
      blocks_2 = 350 * 1000,
      blocks_6 = 230 * 1000,
      blocks_12 = 140 * 1000,
      blocks_36 = 60 * 1000,
      blocks_72 = 40 * 1000)
    assert(feerates === ref)
  }

  test("make sure API hasn't changed") {
    import scala.concurrent.ExecutionContext.Implicits.global
    import scala.concurrent.duration._
    implicit val sttpBackend  = OkHttpFutureBackend()
    implicit val timeout = Timeout(30 seconds)
    val provider = new EarnDotComFeeProvider()
    logger.info("earn.com livenet fees: " + Await.result(provider.getFeerates, 10 seconds))
  }

}
