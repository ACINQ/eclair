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

@CommandLine.Command(name = "getsentinfo", description = "List all attempts for an outgoing payment, with the corresponding status (PENDING, FAILED and SUCCEEDED).", sortOptions = false)
public class GetSentInfo extends BaseSubCommand {

  @CommandLine.ArgGroup(exclusive = true)
  PaymentIdentifierOpts identifier;

  static class PaymentIdentifierOpts {
    @CommandLine.Option(names = { "--paymentHash", "-p" }, required = true, descriptionKey = "opts.payment_hash")
    private String paymentHash;

    @CommandLine.Option(names = { "--uuid", "-u" }, required = true, descriptionKey = "opts.payment_uuid")
    private String uuid;
  }

  @Override
  public Integer call() throws Exception {
    final Map<String, Object> params = new HashMap<>();
    if (identifier.paymentHash != null) {
      params.put("paymentHash", identifier.paymentHash);
    } else if (identifier.uuid != null) {
      params.put("id", identifier.uuid);
    }

    final ResponseBody body = http("getsentinfo", params);
    print(body.string());

    return 0;
  }
}
