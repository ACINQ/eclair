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
import fr.acinq.eclair.cli.utils.exceptions.NotFoundException;
import okhttp3.ResponseBody;
import picocli.CommandLine;

import java.util.HashMap;
import java.util.Map;

import static fr.acinq.eclair.cli.utils.Utils.print;

@CommandLine.Command(name = "getreceivedinfo", description = "Retrieve data for a inbound payment if it has been received.", sortOptions = false)
public class GetReceivedInfo extends BaseSubCommand {

  @CommandLine.ArgGroup(exclusive = true)
  private PaymentIdentifierOpts identifier;

  private static class PaymentIdentifierOpts {
    @CommandLine.Option(names = { "--paymentHash", "-h" }, required = true, descriptionKey = "opts.payment_hash")
    private String paymentHash;

    @CommandLine.Option(names = { "--invoice", "-i" }, required = true, descriptionKey = "opts.invoice")
    private String invoice;
  }

  @Override
  public Integer call() throws Exception {
    final Map<String, Object> params = new HashMap<>();
    if (identifier.paymentHash != null) {
      params.put("paymentHash", identifier.paymentHash);
    } else if (identifier.invoice != null) {
      params.put("id", identifier.invoice);
    }

    try {
      final ResponseBody body = http("getreceivedinfo", params);
      print(body.string());
    } catch (NotFoundException e) {
      print("No payment found.");
    }

    return 0;
  }
}
