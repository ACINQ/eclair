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

package fr.acinq.eclair.cli.utils;

import fr.acinq.eclair.cli.EclairCli;
import okhttp3.ResponseBody;
import picocli.CommandLine;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command(synopsisHeading = "%nUsage: ",
  descriptionHeading = "%n",
  parameterListHeading = "%nParameters: %n",
  optionListHeading = "%nOptions: %n",
  commandListHeading = "%nCommands: %n")
public abstract class BaseSubCommand implements Callable<Integer> {

  /**
   * Not accessible from children because we want them to be able to use values retrieved from conf file.
   */
  @CommandLine.ParentCommand
  protected EclairCli parent;

  protected ResponseBody http(final String command) throws Exception {
    return Utils.http(parent.isRawOutput(), parent.getPassword(), parent.getAddress() + "/" + command, new HashMap<>());
  }

  protected ResponseBody http(final String command, final Map<String, Object> params) throws Exception {
    return Utils.http(parent.isRawOutput(), parent.getPassword(), parent.getAddress() + "/" + command, params);
  }

  /* ========== COMMON GROUPED ARGUMENTS =========== */

  public static class ChannelIdentifierOpt {
    @CommandLine.Option(names = { "--channelId", "-c" }, required = true, descriptionKey = "opts.channel_id")
    public String channelId;

    @CommandLine.Option(names = { "--shortChannelId", "-s" }, required = true, descriptionKey = "opts.short_channel_id")
    public String shortChannelId;
  }

  public static class AmountOpt {
    @CommandLine.Option(names = { "--amountMsat" }, required = true, description = "Amount, in millisatoshi")
    public Long amountMsat;

    @CommandLine.Option(names = { "--amountSat" }, required = true, description = "Amount, in satoshi")
    public Long amountSat;

    @CommandLine.Option(names = { "--amountMbtc" }, required = true, description = "Amount, in millibtc")
    public Long amountMbtc;
  }
}
