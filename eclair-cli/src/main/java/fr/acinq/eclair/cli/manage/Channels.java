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

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Types;
import fr.acinq.eclair.cli.utils.BaseSubCommand;
import fr.acinq.eclair.cli.utils.models.ChannelsRes;
import okhttp3.ResponseBody;
import picocli.CommandLine;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static fr.acinq.eclair.cli.utils.Utils.MOSHI;
import static fr.acinq.eclair.cli.utils.Utils.print;
import static java.util.Comparator.comparingLong;
import static java.util.stream.Collectors.toList;

@CommandLine.Command(name = "channels", description = "Returns the list of local channels, optionally filtered by remote node.", sortOptions = false)
public class Channels extends BaseSubCommand {

  @CommandLine.Option(names = { "--nodeId" }, descriptionKey = "opts.node_id")
  private String nodeId;

  @Override
  public Integer call() throws Exception {
    // 1 - execute query
    final Map<String, Object> params = new HashMap<>();
    params.put("nodeId", nodeId);
    final ResponseBody body = http("channels", params);

    // 2 - handle response
    if (!parent.isPrettyPrint()) {
      print(body.string());
    } else {
      final JsonAdapter<List<ChannelsRes>> adapter = MOSHI.adapter(Types.newParameterizedType(List.class, ChannelsRes.class));
      final List<ChannelsRes> channelsRaw = adapter.fromJson(body.source());

      if (nodeId == null) { // print summary only if user does not filter by nodeId
        final Long totalSat = channelsRaw.stream().mapToLong(ChannelsRes::getBalanceSat).sum();
        print("You have %d channels with a total balance of %,d sat (%f BTC)", channelsRaw.size(), totalSat, totalSat / 1e8);
        print("");
      }

      Map<String, List<ChannelsRes>> channelsByNode = channelsRaw.stream()
        .sorted(comparingLong(ChannelsRes::getBalanceSat).reversed())
        .collect(Collectors.groupingBy(ChannelsRes::getNodeId, LinkedHashMap::new, toList()));

      channelsByNode.forEach((nodeId, channels) -> {
        final Long totalNodeBalance = channels.stream().mapToLong(ChannelsRes::getBalanceSat).sum();
        print("▸ Peer:    %s (%d channels)", nodeId, channels.size());
        print("  Balance: %,d sat (%f BTC)", totalNodeBalance, totalNodeBalance / 1e8);
        print("");
        print("  %15s | %14s | %14s | %16s | %64s | %s", "STATE", "BALANCE", "CAPACITY", "SHORT ID", "CHANNEL ID",  "CHANNEL POINT");
        for (ChannelsRes c : channels) {
          print("  %15s | %,14d | %,14d | %16s | %64s | %s", c.state, c.getBalanceSat(), c.getCapacitySat(), c.getShortChannelId(), c.channelId, c.getChannelPoint());
        }
        print("");
      });
    }

    return 0;
  }
}
