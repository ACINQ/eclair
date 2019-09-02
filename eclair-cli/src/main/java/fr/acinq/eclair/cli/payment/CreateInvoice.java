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

@CommandLine.Command(name = "createinvoice", description = "Create a BOLT11 payment invoice.", sortOptions = false)
public class CreateInvoice extends BaseSubCommand {

  @CommandLine.Option(names = { "--desc", "-d" }, required = true, description = "Invoice description")
  private String description;

  @CommandLine.Option(names = { "--amountMsat" }, description = "Invoice amount, in millisatoshi")
  private Long amountMsat;

  @CommandLine.Option(names = { "--expire", "-e" }, description = "Number of seconds that the invoice will be valid")
  private Integer expireIn;

  @CommandLine.Option(names = { "--fallback", "-f" }, description = "An on-chain fallback address to receive the payment")
  private String fallbackAddress;

  @CommandLine.Option(names = { "--preimage", "-p" }, description = "A user-defined input for the generation of the paymentHash")
  private String preimage;

  @Override
  public Integer call() throws Exception {
    final Map<String, Object> params = new HashMap<>();
    params.put("description", description);
    if (amountMsat != null) {
      params.put("amountMsat", amountMsat.toString());
    }
    if (expireIn != null) {
      params.put("expireIn", expireIn.toString());
    }
    params.put("fallbackAddress", fallbackAddress);
    params.put("paymentPreimage", preimage);

    final ResponseBody body = http("createinvoice", params);
    print(body.string());
    return 0;
  }
}
