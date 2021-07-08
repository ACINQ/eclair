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
import java.util.Map;

import static fr.acinq.eclair.cli.utils.Utils.print;

@CommandLine.Command(name = "payinvoice", description = "Pay a BOLT-11 invoice. If the invoice has a predefined amount, it can be be overridden by using the --amountMsat option.", sortOptions = false)
public class PayInvoice extends BaseSubCommand {

  @CommandLine.Option(names = { "--invoice", "-i" }, required = true, descriptionKey = "opts.invoice")
  private String invoice;

  @CommandLine.Option(names = { "--amountMsat", "-a" }, descriptionKey = "opts.payment.amount_msat_send")
  private Long amountMsat;

  @CommandLine.Option(names = { "--maxAttempts" }, descriptionKey = "opts.payment.max_retries")
  private Integer maxAttempts;

  @CommandLine.Option(names = { "--feeThresholdSat" }, descriptionKey = "opts.payment.fee_threshold_sat")
  private Long feeThresholdSat;

  @CommandLine.Option(names = { "--maxFeePct" }, descriptionKey = "opts.payment.max_fee_pct")
  private Integer maxFeePct;

  @CommandLine.Option(names = { "--wait", "-w" }, descriptionKey = "opts.payment.wait")
  private boolean wait;

  @Override
  public Integer call() throws Exception {
    final Map<String, Object> params = new HashMap<>();
    params.put("invoice", invoice);
    params.put("amountMsat", amountMsat == null ? null : amountMsat.toString());
    params.put("maxAttempts", maxAttempts == null ? null : maxAttempts.toString());
    params.put("feeThresholdSat", feeThresholdSat == null ? null : feeThresholdSat.toString());
    params.put("maxFeePct", maxFeePct == null ? null : maxFeePct.toString());

    final ResponseBody body = http("payinvoice", params);
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
