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

import com.squareup.moshi.JsonDataException;
import com.squareup.moshi.Moshi;
import fr.acinq.eclair.cli.exceptions.ApiException;
import fr.acinq.eclair.cli.exceptions.AuthenticationException;
import fr.acinq.eclair.cli.models.ApiError;
import okhttp3.*;
import picocli.CommandLine;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.Map;

@CommandLine.Command(
  name = "eclair-cli",
  mixinStandardHelpOptions = true,
  version = "1.0.0",
  resourceBundle = "messages",

  synopsisHeading = "%nUsage: ",
  description = "A command line interface for an Eclair node",

  optionListHeading = "%nOptions:%n",
  commandListHeading = "%nCommands:%n",
  parameterListHeading = "%nParameters:%n"
)
public class EclairCli implements Runnable {

  /* ========== STATIC GLOBALS =========== */

  static final Moshi MOSHI = new Moshi.Builder().build();

  /* ========== GLOBAL OPTIONS =========== */

  @CommandLine.Option(names = { "-r", "--raw" }, defaultValue = "false", required = true, description = "Print raw node output as JSON")
  boolean rawOutput;

  @CommandLine.Option(names = { "-p", "--password" }, defaultValue = "tata", interactive = true, description = "Your node's API password")
  String password;

  @CommandLine.Option(names = { "-a", "--address" }, defaultValue = "http://localhost:8081", required = true, description = "Your node's API url.%nDefaults to ${DEFAULT-VALUE}")
  String url;

  /* ========== STATIC UTILITIES METHODS =========== */

  private static void printErr(final CommandLine cmd, final String s, final Object... o) {
    cmd.getErr().println(String.format(s, o));
  }

  static void print(final String s, final Object... o) {
    System.out.println(String.format(s, o));
  }

  static ResponseBody http(final boolean rawOutput, final String password, final String endpoint, final Map<String, String> params) throws Exception {

    // 1 - setup okhttp client with auth
    final OkHttpClient client = new OkHttpClient.Builder()
      .authenticator((route, response) -> {
        if (response.request().header("Authorization") != null) {
          return null; // Give up, we've already attempted to authenticate.
        }
        final String credential = Credentials.basic("", password);
        return response.request().newBuilder()
          .header("Authorization", credential)
          .build();
      }).build();

    // 2 - add headers
    final Request.Builder requestBuilder = new Request.Builder()
      .url(endpoint)
      .header("User-Agent", "EclairCli");

    // 3 - add body (empty if no params)
    if (!params.isEmpty()) {
      final FormBody.Builder bodyBuilder = new FormBody.Builder();
      params.forEach((k, v) -> {
        if (k != null && v != null) {
          bodyBuilder.add(k, v);
        }
      });
      requestBuilder.post(bodyBuilder.build());
    } else {
      requestBuilder
        .method("POST", RequestBody.create(null, new byte[0]))
        .header("Content-Length", "0");
    }

    // 4 - execute
    final Response response = client.newCall(requestBuilder.build()).execute();

    // 5 - handle error if user does not want the raw output
    if (!rawOutput && !response.isSuccessful()) {
      ApiError res = MOSHI.adapter(ApiError.class).fromJson(response.body().source());
      if (response.code() == 401) {
        throw new AuthenticationException(res.error);
      } else {
        throw new ApiException(res.error);
      }
    }

    return response.body();
  }

  /* ========== ERROR HANDLER =========== */

  private static CommandLine.IExecutionExceptionHandler getErrorHandler() {
    return (e, cmd, parseResult) -> {
      printErr(cmd, "⚠ [%s]: %s", e.getClass().getSimpleName(), e.getMessage());

      // add some hints when adequate
      if (e instanceof ConnectException || e instanceof UnknownHostException) {
        printErr(cmd, "\nEclair-cli could not connect to your node. Use the -a or --address option to provide the address.");
        printErr(cmd, "Make sure that you've enabled the HTTP API on your node, and that you provide the correct url to the CLI");
      } else if (e instanceof JsonDataException) {
        cmd.getErr().println();
        printErr(cmd, "\neclair-cli could not read the node's response for this command. Consider using the -r option to print the raw json response instead.");
      } else if (e instanceof AuthenticationException) {
        printErr(cmd, "\nUse the -p or --password option to provide the API password.");
      }

//      e.printStackTrace();

      printErr(cmd, "\n---");
      cmd.usage(cmd.getErr());

      return cmd.getCommandSpec().exitCodeOnExecutionException();
    };
  }

  public static void main(String[] args) {
    final CommandLine cmd = new CommandLine(new EclairCli());

    // 1 - add subcommands (~ the list of commands accepted by the api)
    cmd.addSubcommand(GetInfo.class);
    cmd.addSubcommand(Connect.class);

    // 2 - exception handler
    cmd.setExecutionExceptionHandler(getErrorHandler());

    if (args.length == 0) {
      cmd.usage(System.out);
    } else {
      System.exit(cmd.execute(args));
    }
  }

  @Override
  public void run() {
  }

  private EclairCli() {
    // No instances.
  }
}




