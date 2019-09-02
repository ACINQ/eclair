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
import fr.acinq.eclair.cli.utils.models.PeersRes;
import okhttp3.ResponseBody;
import picocli.CommandLine;

import java.util.List;

import static fr.acinq.eclair.cli.utils.Utils.MOSHI;
import static fr.acinq.eclair.cli.utils.Utils.print;

@CommandLine.Command(name = "peers", description = "Returns the list of currently known peers, both connected and disconnected.", sortOptions = false)
public class Peers extends BaseSubCommand {

  @Override
  public Integer call() throws Exception {
    final ResponseBody body = http("peers");
    if (parent.isRawOutput()) {
      print(body.string());
    } else {
      JsonAdapter<List<PeersRes>> adapter = MOSHI.adapter(Types.newParameterizedType(List.class, PeersRes.class));
      List<PeersRes> peers = adapter.fromJson(body.source());
      peers.sort((p1, p2) -> p2.channels - p1.channels);
      print("You have %d peers with a total of %d channels", peers.size(), peers.stream().mapToInt(p -> p.channels).sum());
      print("");
      print("%18s | %10s | %67s | %s", "STATE", "CHANNELS", "NODE ID", "ADDRESS");
      for (PeersRes p : peers) {
        print("%18s | %10d | %67s | %s", p.state, p.channels, p.nodeId, p.address);
      }
    }

    return 0;
  }
}
