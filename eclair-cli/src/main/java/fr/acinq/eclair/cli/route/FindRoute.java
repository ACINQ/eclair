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

package fr.acinq.eclair.cli.route;

import fr.acinq.eclair.cli.utils.BaseSubCommand;
import fr.acinq.eclair.cli.utils.exceptions.NotFoundException;
import okhttp3.ResponseBody;
import picocli.CommandLine;

import java.util.HashMap;
import java.util.Map;

import static fr.acinq.eclair.cli.utils.Utils.print;

@CommandLine.Command(name = "findroute", description = "Find a route to the node for a given invoice or node id.", sortOptions = false)
public class FindRoute extends BaseSubCommand {

  @CommandLine.ArgGroup
  RouteDestinationOpts routeOpts;

  static class RouteDestinationOpts {
    @CommandLine.Option(names = { "--invoice", "-i" }, required = true, descriptionKey = "opts.invoice")
    private String invoice;

    @CommandLine.Option(names = { "--nodeId", "-n" }, required = true, descriptionKey = "opts.node_id")
    private String nodeId;
  }

  @CommandLine.Option(names = { "--amountMsat", "-a" }, descriptionKey = "opts.payment.amount_msat_send")
  private Long amountMsat;

  @Override
  public Integer call() throws Exception {
    final Map<String, Object> params = new HashMap<>();
    params.put("invoice", routeOpts.invoice);
    params.put("nodeId", routeOpts.nodeId);
    params.put("amountMsat", amountMsat);

    try {
      if (routeOpts.invoice != null) {
        final ResponseBody body = http("findroute", params);
        print(body.string());
      } else if (routeOpts.nodeId != null) {
        final ResponseBody body = http("findroutetonode", params);
        print(body.string());
      }
    } catch (NotFoundException e) {
      print("No route could be found.");
    }

    return 0;
  }
}
