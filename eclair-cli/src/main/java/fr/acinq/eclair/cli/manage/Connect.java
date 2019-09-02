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

@CommandLine.Command(name = "connect", description = "Connect to a Lightning node")
public class Connect extends BaseSubCommand {

  @CommandLine.Option(names = { "--uri" }, description = "URI of the node to connect to (nodeId@host:port)")
  private String uri;

  @CommandLine.ArgGroup(exclusive = false)
  ConnectExplicitOpts explicitOpts;

  static class ConnectExplicitOpts {
    @CommandLine.Option(names = { "--nodeId" }, required = true, description = "Id of the node you want to connect to")
    private String nodeId;

    @CommandLine.Option(names = { "--host" }, description = "IPv4 host address of the node")
    private String host;

    @CommandLine.Option(names = { "--port" }, defaultValue = "9735", description = "Port of the node (default ${DEFAULT-VALUE})")
    private String port;
  }

  @Override
  public Integer call() throws Exception {
    final Map<String, Object> params = new HashMap<>();
    if (uri != null) {
      params.put("uri", uri);
    } else if (explicitOpts != null) {
      params.put("nodeId", explicitOpts.nodeId);
      params.put("host", explicitOpts.host);
      params.put("port", explicitOpts.port);
    } else {
      throw new RuntimeException("Missing parameters. Please provide correct options");
    }
    final ResponseBody body = http("connect", params);
    print(body.string());
    return 0;
  }
}
