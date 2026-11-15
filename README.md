![Eclair Logo](.readme/logo.png)

[![XBT Knots regression tests](https://github.com/ashbornelabs/eclair-xbt/actions/workflows/xbt-knots-regression.yml/badge.svg)](https://github.com/ashbornelabs/eclair-xbt/actions/workflows/xbt-knots-regression.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

**Eclair** (French for Lightning) is a Scala implementation of the Lightning Network.

This software follows the [Lightning Network Specifications (BOLTs)](https://github.com/lightning/bolts).
This fork uses XBT Knots as its only supported on-chain backend. Do not connect it to the Bitcoin chain or use Bitcoin-chain Lightning peers, explorers, or test services.

---

* [Lightning Network Specification Compliance](#lightning-network-specification-compliance)
* [JSON API](#json-api)
* [Documentation](#documentation)
* [Installation](#installation)
  * [Prerequisite: XBT Knots](#prerequisite-xbt-knots)
  * [Installing Eclair](#installing-eclair)
* [Configuration](#configuration)
  * [Configuration file](#configuration-file)
  * [Configure XBT Knots wallet](#configure-xbt-knots-wallet)
  * [Java Environment Variables](#java-environment-variables)
  * [Logging](#logging)
  * [Backup](#backup)
* [Docker](#docker)
* [Plugins](#plugins)
* [Testnet usage](#testnet-usage)
* [Tools](#tools)
* [Resources](#resources)

---

## Lightning Network Specification Compliance

Please see the [XBT Eclair releases](https://github.com/ashbornelabs/eclair-xbt/releases) for release-specific information. Eclair uses BOLTs as its Lightning protocol base, with XBT-specific behavior tracked separately in this project.

## JSON API

Eclair offers a feature-rich HTTP API that enables application developers to easily integrate.

For more information please visit the [API documentation website](https://acinq.github.io/eclair).

:rotating_light: Eclair's JSON API should **NOT** be accessible from the outside world.

## Documentation

Please visit our [docs](./docs) folder to find detailed instructions on how to [configure](./docs/Configure.md) your
node, connect to other nodes, open channels, send and receive payments, and help with more advanced scenarios.

You will also find detailed [guides](./docs/Guides.md) and [frequently asked questions](./docs/FAQ.md) there.

## Installation

### Prerequisite: XBT Knots

This XBT Eclair fork requires an XBT-compatible [Bitcoin Knots](https://github.com/bitcoinknots/bitcoin) node to monitor the XBT chain and manage on-chain funds. Eclair does not include an on-chain wallet: channel opening transactions are funded by the Knots wallet, and channel closing transactions return funds to that wallet.

Eclair relies on the node's on-chain verification and transaction management, and uses the XBT Bitcoin library to verify data provided by the node.

:warning: At startup, Eclair checks the node's Knots identity, selected XBT network, genesis hash, and advertised BLAKE2b activation and reduced-data schedule. A standard Bitcoin Core node is rejected. Back up the Knots wallet as well as your Eclair node (see [here](#configure-xbt-knots-wallet)).

* Eclair needs a _synchronized_, _segwit-ready_, **_zeromq-enabled_**, _wallet-enabled_, _non-pruning_, _tx-indexing_ XBT Knots node.
* Configure your XBT Knots wallet to use `bech32` or `bech32m` (segwit) addresses. If the wallet has non-segwit UTXOs, send them to a `bech32` or `bech32m` address before running Eclair.
* Eclair checks the XBT Knots RPC methods and consensus schedule at startup; it does not use a Bitcoin Core release-number check as a substitute for XBT compatibility.

Run `bitcoind` from the XBT Knots distribution with the following minimal `bitcoin.conf`:

```conf
server=1
rpcuser=foo
rpcpassword=bar
txindex=1
addresstype=bech32m
changetype=bech32m
zmqpubhashblock=tcp://127.0.0.1:29000
zmqpubrawtx=tcp://127.0.0.1:29000
```

Depending on the hardware, increasing `dbcache` may speed up verification and increasing `rpcworkqueue` may help XBT Knots handle more RPC requests.

```conf
# UTXO database cache size, in MiB
dbcache=2048
# Number of allowed pending RPC requests (default is 16)
rpcworkqueue=128

# How many seconds XBT Knots will wait for a complete RPC HTTP request.
# after the HTTP connection is established.
rpcclienttimeout=30
```

:warning: We recommend running XBT Knots on the same machine as Eclair.
:warning: If you run XBT Knots remotely, use an authenticated, encrypted tunnel for RPC and ZMQ traffic.

XBT Knots does not provide built-in TLS for RPC or ZMQ. The node operator must secure remote traffic with a tunnel such as SSH or WireGuard.

### Installing Eclair

Eclair is developed in [Scala](https://www.scala-lang.org/), a powerful functional language that runs on the JVM, and is packaged as a ZIP archive.

To run Eclair, you first need to install Java. Eclair targets Java 21 and will run on any compatible Java runtime, we recommend that you use [OpenJDK 21](https://adoptium.net/temurin/releases/?package=jdk&version=21).

Download an XBT Eclair package from this repository's [releases](https://github.com/ashbornelabs/eclair-xbt/releases), unzip it, and run:

```shell
eclair-node-<version>-<commit_id>/bin/eclair-node.sh
```

You can then control your node via [eclair-cli](./docs/Usage.md) or the [API](./docs/API.md).

:warning: Be careful when following tutorials/guides that may be outdated or incomplete. You must thoroughly read the official eclair documentation before running your own node.

## Configuration

### Configuration file

Eclair reads its configuration file, and write its logs, to `~/.eclair` by default.

To change your node's configuration, create a file named `eclair.conf` in `~/.eclair`. Here's an example configuration file:

```conf
eclair.node-alias=eclair
eclair.node-color=49daaa
```

Here are some of the most common options:

name                         | description                                                          | default value
-----------------------------|----------------------------------------------------------------------|--------------
 eclair.chain                | Which blockchain to use: *regtest*, *testnet4* or *mainnet*          | mainnet
 eclair.server.port          | Lightning TCP port                                                   | 9735
 eclair.api.enabled          | Enable/disable the API                                               | false. By default the API is disabled. If you want to enable it, you must set a password.
 eclair.api.port             | API HTTP port                                                        | 8080
 eclair.api.password         | API password (BASIC)                                                 | "" (must be set if the API is enabled)
 eclair.bitcoind.rpcuser     | XBT Knots RPC user                                                   | foo
 eclair.bitcoind.rpcpassword | XBT Knots RPC password                                               | bar
 eclair.bitcoind.zmqblock    | XBT Knots ZMQ block address                                          | "tcp://127.0.0.1:29000"
 eclair.bitcoind.zmqtx       | XBT Knots ZMQ transaction address                                    | "tcp://127.0.0.1:29000"
 eclair.bitcoind.wallet      | XBT Knots wallet name                                                | ""

Quotes are not required unless the value contains special characters. Full syntax guide [here](https://github.com/lightbend/config/blob/master/HOCON.md).

&rarr; see [here](./docs/Configure.md) for more configuration options.

### Configure XBT Knots wallet

Eclair uses the default loaded XBT Knots wallet to fund channels. To use another wallet, set `eclair.bitcoind.wallet` in `eclair.conf`.

:warning: Once a wallet is configured, you must be very careful if you want to change it: changing the wallet when you have channels open may result in a loss of funds (or a complex recovery procedure).

Eclair returns XBT from closed channels to the configured wallet. XBT in that wallet can fund new channels.

We also recommend these settings in XBT Knots' `bitcoin.conf`:

```conf
# This parameter ensures that your wallet will not create chains of unconfirmed
# transactions that would be rejected by other nodes.
walletrejectlongchains=1
# The following parameters set the maximum length of chains of unconfirmed
# transactions to 20 instead of the default value of 25.
limitancestorcount=20
limitdescendantcount=20
```

Setting these parameters lets you unblock long chains of unconfirmed channel funding transactions by using child-pays-for-parent (CPFP) to make them confirm.

With the default XBT Knots parameters, a chain of 25 unconfirmed low-fee funding transactions may prevent a CPFP transaction from being accepted by the XBT network.

You can also configure Eclair to manage XBT Knots wallet keys. See the [guides](./docs/Guides.md) for details.

### Java Environment Variables

Some advanced parameters can be changed with java environment variables. Most users won't need this and can skip this section.

However, if you're seeing Java heap size errors, you can try increasing the maximum memory allocated to the JVM with the `-Xmx` parameter.

You can for example set it to use up to 512 MB (or any value that fits the amount of RAM on your machine) with:

```shell
export JAVA_OPTS=-Xmx512m
```

:warning: Using separate `datadir` is mandatory if you want to run **several instances of eclair** on the same machine. You will also have to change ports in `eclair.conf` (see above).

name                  | description                                | default value
----------------------|--------------------------------------------|--------------
eclair.datadir        | Path to the data directory                 | ~/.eclair
eclair.printToConsole | Log to stdout (in addition to eclair.log)  |

For example, to specify a different data directory you would run the following command:

```shell
eclair-node-<version>-<commit_id>/bin/eclair-node.sh -Declair.datadir=/tmp/node1
```

### Logging

Eclair uses [`logback`](https://logback.qos.ch) for logging. To use a [different configuration](./docs/Logging.md), and override the internal logback.xml, run:

```shell
eclair-node-<version>-<commit_id>/bin/eclair-node.sh -Dlogback.configurationFile=/path/to/logback-custom.xml
```

### Backup

You need to backup:

* your XBT Knots wallet
* your Eclair channels

Back up the wallet file used by Eclair when the XBT Knots wallet is created. See [Knots wallet management](https://github.com/bitcoinknots/bitcoin/blob/v29.4.3.knots20260508/doc/managing-wallets.md) for details.

For Eclair, the files that you need to backup are located in your data directory. You must backup:

* your seeds (`node_seed.dat` and `channel_seed.dat`)
* your channel database (`eclair.sqlite.bak` under directory `mainnet`, `testnet4` or `regtest` depending on which chain you're running on)

Your seeds never change once they have been created, but your channels will change whenever you receive or send payments. Eclair will
create and maintain a snapshot of its database, named `eclair.sqlite.bak`, in your data directory, and update it when needed. This file is
always consistent and safe to use even when Eclair is running, and this is what you should back up regularly.

For example, you could configure a `cron` task for your backup job. Or you could configure an optional notification script to be called by eclair once a new database snapshot has been created, using the following option:

```conf
eclair.file-backup.notify-script = "/absolute/path/to/script.sh"
```

Make sure your script is executable and uses an absolute path name for `eclair.sqlite.bak`.

Note that depending on your filesystem, in your backup process we recommend first moving `eclair.sqlite.bak` to some temporary file
before copying that file to your final backup location.

## Docker

The upstream ACINQ container targets Bitcoin and is not an XBT Eclair image. Build this fork's image locally:

```shell
docker build -t eclair-xbt:local .
```

For arm64 platforms, use the [arm64 Dockerfile](contrib/arm64v8.Dockerfile).

You can use the `JAVA_OPTS` environment variable to set arguments to `eclair-node`.

```shell
docker run -ti --rm -e "JAVA_OPTS=-Xmx512m -Declair.printToConsole" eclair-xbt:local
```

If you want to persist the data directory, you can make the volume to your host with the `-v` argument, as the following example:

```shell
docker run -ti --rm -v "/path_on_host:/data" -e "JAVA_OPTS=-Declair.printToConsole" eclair-xbt:local
```

If you enabled the API you can check the status of Eclair using the command line tool:

```shell
docker exec <container_name> eclair-cli -p foobar getinfo
```

## Plugins

For advanced usage, Eclair supports plugins written in Scala, Java, or any JVM-compatible language.

A valid plugin is a jar that contains an implementation of the [Plugin](eclair-node/src/main/scala/fr/acinq/eclair/Plugin.scala) interface, and a manifest entry for `Main-Class` with the FQDN of the implementation.

Here is how to run Eclair with plugins:

```shell
eclair-node-<version>/bin/eclair-node.sh <plugin1.jar> <plugin2.jar> <...>
```

Plugins from upstream Eclair are not guaranteed to support this XBT-only fork. Check each plugin's compatibility before using it with an XBT node.

## Testnet usage

Eclair is configured to run on XBT Mainnet by default. For XBT Testnet4, start XBT Knots with `testnet4=1` (or `-testnet4`) and set Eclair's chain and RPC port:

```conf
eclair.chain=testnet4
eclair.bitcoind.rpcport=48332
```

For XBT Regtest, add `regtest=1` in XBT Knots' `bitcoin.conf` (or start with `-regtest`) and modify `eclair.conf`:

```conf
eclair.chain = "regtest"
eclair.bitcoind.rpcport=18443
```

You may also use network-specific sections in XBT Knots' `bitcoin.conf` to manage Mainnet and Testnet4 settings. For example:

```conf
server=1
txindex=1

addresstype=bech32
changetype=bech32

walletrejectlongchains=1
limitancestorcount=20
limitdescendantcount=20

[main]
rpcuser=<your-mainnet-rpc-user-here>
rpcpassword=<your-mainnet-rpc-password-here>
zmqpubhashblock=tcp://127.0.0.1:29000
zmqpubrawtx=tcp://127.0.0.1:29000

[testnet4]
rpcuser=<your-testnet-rpc-user-here>
rpcpassword=<your-testnet-rpc-password-here>
zmqpubhashblock=tcp://127.0.0.1:29001
zmqpubrawtx=tcp://127.0.0.1:29001
```

## Resources

* [1] [The Bitcoin Lightning Network: Scalable Off-Chain Instant Payments](https://lightning.network/lightning-network-paper.pdf) by Joseph Poon and Thaddeus Dryja
* [2] [Reaching The Ground With Lightning](https://github.com/ElementsProject/lightning/blob/10b10eb981a2c3f2385c3c9ae2244a1767eeca86/doc/miscellaneous/deployable-lightning.pdf) by Rusty Russell
