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

package fr.acinq.eclair.cli.payment;

import fr.acinq.eclair.cli.utils.BaseSubCommand;
import okhttp3.ResponseBody;
import picocli.CommandLine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static fr.acinq.eclair.cli.utils.Utils.print;

@CommandLine.Command(name = "sendtoroute", description = "Sends money to a node without an invoice. The payment will be retried several times if needed.", sortOptions = false)
public class SendToRoute extends BaseSubCommand {

  @CommandLine.Option(names = { "--route", "-r" }, required = true, description = "List of node ids")
  private List<String> route;

  @CommandLine.Option(names = { "--amountMsat", "-a" }, required = true, descriptionKey = "opts.payment.amount_msat_send")
  private Long amountMsat;

  @CommandLine.Option(names = { "--paymentHash", "-p" }, required = true, descriptionKey = "opts.payment_hash")
  private String paymentHash;

  @CommandLine.Option(names = { "--finalCltvExpiry", "-e" }, required = true, description = "The total CLTV expiry value for this payment")
  private Integer finalCltvExpiry;

  @CommandLine.Option(names = { "--wait", "-w" }, descriptionKey = "opts.payment.wait")
  private boolean wait;

  @Override
  public Integer call() throws Exception {
    final Map<String, Object> params = new HashMap<>();
    params.put("route", String.join(",", route));
    params.put("amountMsat", amountMsat);
    params.put("paymentHash", paymentHash);
    params.put("finalCltvExpiry", finalCltvExpiry);

    final ResponseBody body = http("sendtoroute", params);
    final String uuid = body.string().replace("\"", "");

    if (wait) { // chain with get sent info for details
      final Map<String, Object> paramsSentInfo = new HashMap<>();
      paramsSentInfo.put("id", uuid);
      final ResponseBody bodySentInfo = http("getsentinfo", paramsSentInfo);
      print(bodySentInfo.string());
    } else {
      print(uuid);
    }

    return 0;
  }
}
