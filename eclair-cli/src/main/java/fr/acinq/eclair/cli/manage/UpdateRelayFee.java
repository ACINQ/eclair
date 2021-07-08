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

package fr.acinq.eclair.cli.manage;

import fr.acinq.eclair.cli.utils.BaseSubCommand;
import okhttp3.ResponseBody;
import picocli.CommandLine;

import java.util.HashMap;
import java.util.Map;

import static fr.acinq.eclair.cli.utils.Utils.print;

@CommandLine.Command(name = "updaterelayfee", description = "Updates the fee policy for the specified channel.", sortOptions = false)
public class UpdateRelayFee extends BaseSubCommand {

  @CommandLine.ArgGroup(exclusive = true, multiplicity = "1")
  private ChannelIdentifierOpt identifier;

  @CommandLine.Option(names = { "--feeBaseMsat", "-b" }, description = "New base fee to use, in millisatoshis")
  private String feeBaseMsat;

  @CommandLine.Option(names = { "--feeProportionalMillionths", "-p" }, description = "New proportional fee to use")
  private String feeProportionalMillionths;

  @Override
  public Integer call() throws Exception {
    final Map<String, Object> params = new HashMap<>();
    params.put("channelId", identifier.channelId);
    params.put("shortChannelId", identifier.shortChannelId);
    params.put("feeBaseMsat", feeBaseMsat);
    params.put("feeProportionalMillionths", feeProportionalMillionths);
    final ResponseBody body = http("updaterelayfee", params);
    print(body.string());
    return 0;
  }
}
