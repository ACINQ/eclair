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

package fr.acinq.eclair.cli;

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

  @CommandLine.ParentCommand
  protected EclairCli parent;

  protected ResponseBody http(final String command) throws Exception {
    return EclairCli.http(parent.rawOutput, parent.password, parent.url + "/" + command, new HashMap<>());
  }

  protected ResponseBody http(final String command, final Map<String, String> params) throws Exception {
    return EclairCli.http(parent.rawOutput, parent.password, parent.url + "/" + command, params);
  }
}
