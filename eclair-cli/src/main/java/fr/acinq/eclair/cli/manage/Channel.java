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

@CommandLine.Command(name = "channel", description = "Returns detailed information about a local channel.", sortOptions = false)
public class Channel extends BaseSubCommand {

  @CommandLine.Option(names = { "--channelId", "-c" }, required = true, descriptionKey = "opts.channel_id")
  String channelId;

  @Override
  public Integer call() throws Exception {
    final Map<String, Object> params = new HashMap<>();
    params.put("channelId", channelId);
    final ResponseBody body = http("channel", params);
    print(body.string());
    return 0;
  }
}
