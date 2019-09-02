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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static fr.acinq.eclair.cli.utils.Utils.print;

@CommandLine.Command(name = "open", description = "Open a channel to another Lightning node. You must first have a connection to this node.", sortOptions = false)
public class Open extends BaseSubCommand {

  static class ChannelsFlags extends ArrayList<String> {
    ChannelsFlags() {
      super(Arrays.asList("0", "1"));
    }
  }

  @CommandLine.Option(names = { "--nodeId", "-n" }, required = true, descriptionKey = "opts.node_id")
  private String nodeId;

  @CommandLine.Option(names = { "--funding", "-f" }, required = true, description = "Amount to spend in the funding of the channel, in satoshis", paramLabel = "fundingSat")
  private String fundingSat;

  @CommandLine.Option(names = { "--feerate", "-r" }, description = "Feerate to apply to the funding transaction, in satoshi/byte", paramLabel = "<feerateSatByte>")
  private String fundingFeerateSatByte;

  @CommandLine.Option(names = { "--push" }, description = "Amount to unilaterally push to the counterparty, in millisatoshi")
  private String pushMsat;

  @CommandLine.Option(names = { "--flags" }, completionCandidates = ChannelsFlags.class, description = "Flags for the new channel: 0 = private, 1 = public")
  private String channelFlags;

  @CommandLine.Option(names = { "--timeout", "-o" }, description = "Timeout for the operation to complete")
  private String openTimeoutSeconds;

  @Override
  public Integer call() throws Exception {
    final Map<String, Object> params = new HashMap<>();
    params.put("nodeId", nodeId);
    params.put("fundingSatoshis", fundingSat);
    params.put("fundingFeerateSatByte", fundingFeerateSatByte);
    params.put("pushMsat", pushMsat);
    params.put("channelFlags", channelFlags);
    params.put("openTimeoutSeconds", openTimeoutSeconds);
    final ResponseBody body = http("open", params);
    print(body.string());
    return 0;
  }
}
