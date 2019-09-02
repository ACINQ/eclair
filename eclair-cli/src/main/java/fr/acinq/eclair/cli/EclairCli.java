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
import fr.acinq.eclair.cli.general.Audit;
import fr.acinq.eclair.cli.general.ChannelsStats;
import fr.acinq.eclair.cli.general.GetInfo;
import fr.acinq.eclair.cli.general.NetworkFees;
import fr.acinq.eclair.cli.manage.*;
import fr.acinq.eclair.cli.network.AllChannels;
import fr.acinq.eclair.cli.network.AllNodes;
import fr.acinq.eclair.cli.network.AllUpdates;
import fr.acinq.eclair.cli.payment.*;
import fr.acinq.eclair.cli.route.FindRoute;
import fr.acinq.eclair.cli.utils.exceptions.ApiException;
import fr.acinq.eclair.cli.utils.exceptions.AuthenticationException;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.stream.Stream;

import static fr.acinq.eclair.cli.utils.Utils.print;
import static fr.acinq.eclair.cli.utils.Utils.printErr;

@CommandLine.Command(
  name = "eclair-cli",
  mixinStandardHelpOptions = true,
  version = "1.0.0",
  resourceBundle = "messages",

  synopsisHeading = "%nUsage: ",
  description = "%nA command line interface for an Eclair node",

  optionListHeading = "%nGlobal options:%n--------------%n%nNote that these options can be saved in ~/.eclair/cli.conf for future reuse. Format is <option_name>=<option_value>.%n%n",
  commandListHeading = "%nCommands:%n--------%n%n",
  parameterListHeading = "%nParameters:%n----------%n%n"
)
public class EclairCli implements Runnable {

  /* ========== GLOBAL OPTIONS =========== */

  @CommandLine.Option(names = { "-r", "--raw" }, description = "Print raw node output as JSON.")
  private Boolean rawOutput;

  @CommandLine.Option(names = { "-p", "--password" }, interactive = true, description = "Your node's API password.")
  private String password;

  @CommandLine.Option(names = { "-a", "--address" }, description = "Your node's API url.")
  private String url;

  private OptsFromConf optsFromConf;

  public boolean isRawOutput() {
    if (rawOutput != null) return rawOutput;
    if (optsFromConf.raw != null) return optsFromConf.raw;
    return false;
  }

  public String getPassword() {
    if (password != null) return password;
    if (optsFromConf.password != null) return optsFromConf.password;
    return "";
  }

  public String getAddress() {
    if (url != null) return url;
    if (optsFromConf.address != null) return optsFromConf.address;
    return "http://localhost:8080";
  }


  /* ========== ERROR HANDLER =========== */

  private static CommandLine.IExecutionExceptionHandler getErrorHandler() {
    return (e, cmd, parseResult) -> {
      printErr(cmd, "⚠ [%s]: %s", e.getClass().getSimpleName(), e.getMessage());

      // add some hints when adequate
      if (e instanceof ConnectException || e instanceof UnknownHostException) {
        printErr(cmd, "\nEclair-cli could not connect to your node. Use the -a or --address option to provide the node's address.");
        printErr(cmd, "Make also sure that you've enabled the HTTP API on your node (disabled by default).");
      } else if (e instanceof JsonDataException) {
        cmd.getErr().println();
        printErr(cmd, "\neclair-cli could not read the node's response for this command. Consider using the -r option to print the raw data instead.");
      } else if (e instanceof AuthenticationException) {
        printErr(cmd, "\nUse the -p or --password option to provide the API password.");
      } else if (e instanceof ApiException) {
        // nothing special
      } else {
        e.printStackTrace();
      }

      printErr(cmd, "\n---");
      cmd.usage(cmd.getErr());

      return cmd.getCommandSpec().exitCodeOnExecutionException();
    };
  }

  /* ========== MAIN =========== */

  public static void main(String[] args) {
    final CommandLine cmd = new CommandLine(new EclairCli());

    // 1 - add subcommands (~ the list of commands accepted by the eclair node api)
    // general
    cmd.addSubcommand(GetInfo.class);
    cmd.addSubcommand(Audit.class);
    cmd.addSubcommand(NetworkFees.class);
    cmd.addSubcommand(ChannelsStats.class);

    // channel management
    cmd.addSubcommand(Connect.class);
    cmd.addSubcommand(Disconnect.class);
    cmd.addSubcommand(Open.class);
    cmd.addSubcommand(Close.class);
    cmd.addSubcommand(ForceClose.class);
    cmd.addSubcommand(UpdateRelayFee.class);
    cmd.addSubcommand(Peers.class);
    cmd.addSubcommand(Channels.class);
    cmd.addSubcommand(Channel.class);

    // network
    cmd.addSubcommand(AllNodes.class);
    cmd.addSubcommand(AllChannels.class);
    cmd.addSubcommand(AllUpdates.class);

    // payment
    cmd.addSubcommand(CreateInvoice.class);
    cmd.addSubcommand(ParseInvoice.class);
    cmd.addSubcommand(PayInvoice.class);
    cmd.addSubcommand(SendToNode.class);
    cmd.addSubcommand(SendToRoute.class);
    cmd.addSubcommand(GetSentInfo.class);
    cmd.addSubcommand(GetReceivedInfo.class);
    cmd.addSubcommand(GetInvoice.class);
    cmd.addSubcommand(ListInvoices.class);
    cmd.addSubcommand(ListPendingInvoices.class);

    // route
    cmd.addSubcommand(FindRoute.class);

    // 2 - exception handler
    cmd.setExecutionExceptionHandler(getErrorHandler());

    if (args.length == 0) {
      cmd.usage(System.out);
    } else {
      System.exit(cmd.execute(args));
    }
  }

  /**
   * Fetches default options values from ~/.eclair/eclair-cli.conf, if any.
   * These options are always overridden by manual input.
   */
  private void getOptsFromConf() {
    optsFromConf = new OptsFromConf();
    final File datadir = new File(System.getProperty("eclair.datadir", System.getProperty("user.home") + "/.eclair"));
    if (datadir.exists() && datadir.canRead()) {
      final File conf = new File(datadir, "cli.conf");
      if (conf.exists() && conf.canRead()) {
        try (Stream<String> stream = Files.lines(conf.toPath(), StandardCharsets.UTF_8)) {
          stream.forEach(line -> {
            final String[] arr = line.split("=");
            if (arr.length == 2) {
              switch (arr[0]) {
                case "address": {
                  this.optsFromConf.address = arr[1];
                  break;
                }
                case "password": {
                  this.optsFromConf.password = arr[1];
                  break;
                }
                case "raw": {
                  this.optsFromConf.raw = Boolean.valueOf(arr[1]);
                  break;
                }
              }
            }
          });
        } catch (IOException e) {
          print("There was an error when trying to read the cli.conf file from ~/.eclair.\nExpected format is: <option_name>=<option_value> (UTF-8 encoded).\n\n");
          e.printStackTrace();
        }
      }
    }
  }

  private static class OptsFromConf {
    String password = null;
    String address = null;
    Boolean raw = null;
  }

  @Override
  public void run() {
  }

  private EclairCli() {
    getOptsFromConf();
  }
}




