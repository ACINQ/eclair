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
import fr.acinq.eclair.cli.utils.models.GetInfoRes;
import okhttp3.ResponseBody;
import picocli.CommandLine;

import java.util.Arrays;

import static fr.acinq.eclair.cli.utils.Utils.MOSHI;
import static fr.acinq.eclair.cli.utils.Utils.print;

@CommandLine.Command(name = "getinfo", description = "Displays information about the node (id, label,...)")
public class GetInfo extends BaseSubCommand {
  @Override
  public Integer call() throws Exception {
    final ResponseBody body = http("getinfo");
    if (parent.isRawOutput()) {
      print(body.string());
    } else {
      GetInfoRes res = MOSHI.adapter(GetInfoRes.class).fromJson(body.source());
      print("node id: %s", res.nodeId);
      print("alias: %s", res.alias);
      print("chain hash: %s", res.chainHash);
      print("current block height: %s", res.blockHeight);
      print("public addresses: %s", Arrays.toString(res.publicAddresses.toArray()));
    }
    return 0;
  }
}
