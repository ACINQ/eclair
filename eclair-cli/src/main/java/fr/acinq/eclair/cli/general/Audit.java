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

package fr.acinq.eclair.cli.general;

import fr.acinq.eclair.cli.utils.BaseSubCommand;
import okhttp3.ResponseBody;
import picocli.CommandLine;

import java.util.HashMap;
import java.util.Map;

import static fr.acinq.eclair.cli.utils.Utils.print;

@CommandLine.Command(name = "audit", description = "Retrieve information about the payments received, sent, and relayed by this node. Amounts are in millisatoshi", sortOptions = false)
public class Audit extends BaseSubCommand {

  @CommandLine.Option(names = { "--from" }, description = "Only payments created after this UNIX timestamp, in seconds")
  private Long from;

  @CommandLine.Option(names = { "--to" }, description = "Only payments created before this UNIX timestamp, in seconds")
  private Long to;

  @Override
  public Integer call() throws Exception {
    final Map<String, Object> params = new HashMap<>();
    params.put("from", from);
    params.put("to", to);

    final ResponseBody body = http("audit", params);
    print(body.string());

    return 0;
  }
}
