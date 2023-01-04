![Eclair Logo](.readme/logo.png)

[![Build Status](https://github.com/ACINQ/eclair/workflows/Build%20&%20Test/badge.svg)](https://github.com/ACINQ/eclair/actions?query=workflow%3A%22Build+%26+Test%22)
[![codecov](https://codecov.io/gh/acinq/eclair/branch/master/graph/badge.svg)](https://codecov.io/gh/acinq/eclair)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

**Eclair** (French for Lightning) is a Scala implementation of the Lightning Network.

This software follows the [Lightning Network Specifications (BOLTs)](https://github.com/lightning/bolts).
Other implementations include [core lightning](https://github.com/ElementsProject/lightning), [lnd](https://github.com/LightningNetwork/lnd), [electrum](https://github.com/spesmilo/electrum/), and [ldk](https://github.com/lightningdevkit/rust-lightning).

---

* [Lightning Network Specification Compliance](#lightning-network-specification-compliance)
* [JSON API](#json-api)
* [Documentation](#documentation)
* [Installation](#installation)
  * [Prerequisite: Bitcoin Core](#prerequisite-bitcoin-core)
  * [Installing Eclair](#installing-eclair)
* [Configuration](#configuration)
  * [Configuration file](#configuration-file)
  * [Configure Bitcoin Core wallet](#configure-bitcoin-core-wallet)
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

Please see the latest [release note](https://github.com/ACINQ/eclair/releases) for detailed information on BOLT compliance.

## JSON API

Eclair offers a feature-rich HTTP API that enables application developers to easily integrate.

For more information please visit the [API documentation website](https://acinq.github.io/eclair).

:rotating_light: Eclair's JSON API should **NOT** be accessible from the outside world (similarly to Bitcoin Core API)

## Documentation

Please visit our [docs](./docs) folder to find detailed instructions on how to [configure](./docs/Configure.md) your
node, connect to other nodes, open channels, send and receive payments, and help with more advanced scenarios.

You will also find detailed [guides](./docs/Guides.md) and [frequently asked questions](./docs/FAQ.md) there.

## Installation

### Prerequisite: Bitcoin Core

Eclair relies on Bitcoin Core to interface with and monitor the blockchain and to manage on-chain funds: Eclair does not include an on-chain wallet, channel opening transactions are funded by your Bitcoin Core node, and channel closing transactions return funds to your Bitcoin Core node.

This means that instead of re-implementing them, Eclair benefits from the verifications and optimisations (including fee management with RBF/CPFP, ...) that are implemented by Bitcoin Core. Eclair uses our own [bitcoin library](https://github.com/ACINQ/bitcoin-kmp) to verify data provided by Bitcoin Core.

:warning: This also means that Eclair has strong requirements on how your Bitcoin Core node is configured (see below), and that you must back up your Bitcoin Core wallet as well as your Eclair node (see [here](#configure-bitcoin-core-wallet)):

* Eclair needs a _synchronized_, _segwit-ready_, **_zeromq-enabled_**, _wallet-enabled_, _non-pruning_, _tx-indexing_ [Bitcoin Core](https://github.com/bitcoin/bitcoin) node.
* You must configure your Bitcoin node to use `bech32` or `bech32m` (segwit) addresses. If your wallet has "non-segwit UTXOs" (outputs that are neither `p2sh-segwit`, `bech32` or `bech32m`), you must send them to a `bech32` or `bech32m` address before running Eclair.
* Eclair requires Bitcoin Core 23.1 or higher. If you are upgrading an existing wallet, you may need to create a new address and send all your funds to that address.

Run bitcoind with the following minimal `bitcoin.conf`:

```conf
server=1
rpcuser=foo
rpcpassword=bar
txindex=1
addresstype=bech32
changetype=bech32
zmqpubhashblock=tcp://127.0.0.1:29000
zmqpubrawtx=tcp://127.0.0.1:29000
```

Depending on the actual hardware configuration, it may be useful to provide increased `dbcache` parameter value for faster verification and `rpcworkqueue` parameter value for better handling of API requests on `bitcoind` side.

```conf
# UTXO database cache size, in MiB
dbcache=2048
# Number of allowed pending RPC requests (default is 16)
rpcworkqueue=128

# How many seconds bitcoin will wait for a complete RPC HTTP request.
# after the HTTP connection is established.
rpcclienttimeout=30
```

### Installing Eclair

Eclair is developed in [Scala](https://www.scala-lang.org/), a powerful functional language that runs on the JVM, and is packaged as a ZIP archive.

To run Eclair, you first need to install Java, we recommend that you use [OpenJDK 11](https://adoptopenjdk.net/?variant=openjdk11&jvmVariant=hotspot). Other runtimes also work, but we don't recommend using them.

Then download our latest [release](https://github.com/ACINQ/eclair/releases), unzip the archive and run the following command:

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
 eclair.chain                | Which blockchain to use: *regtest*, *testnet*, *signet* or *mainnet* | mainnet
 eclair.server.port          | Lightning TCP port                                                   | 9735
 eclair.api.enabled          | Enable/disable the API                                               | false. By default the API is disabled. If you want to enable it, you must set a password.
 eclair.api.port             | API HTTP port                                                        | 8080
 eclair.api.password         | API password (BASIC)                                                 | "" (must be set if the API is enabled)
 eclair.bitcoind.rpcuser     | Bitcoin Core RPC user                                                | foo
 eclair.bitcoind.rpcpassword | Bitcoin Core RPC password                                            | bar
 eclair.bitcoind.zmqblock    | Bitcoin Core ZMQ block address                                       | "tcp://127.0.0.1:29000"
 eclair.bitcoind.zmqtx       | Bitcoin Core ZMQ tx address                                          | "tcp://127.0.0.1:29000"
 eclair.bitcoind.wallet      | Bitcoin Core wallet name                                             | ""

Quotes are not required unless the value contains special characters. Full syntax guide [here](https://github.com/lightbend/config/blob/master/HOCON.md).

&rarr; see [here](./docs/Configure.md) for more configuration options.

### Configure Bitcoin Core wallet

Eclair will use the default loaded Bitcoin Core wallet to fund any channels you choose to open.
If you want to use a different wallet from the default one, you must set `eclair.bitcoind.wallet` accordingly in your `eclair.conf`.

:warning: Once a wallet is configured, you must be very careful if you want to change it: changing the wallet when you have channels open may result in a loss of funds (or a complex recovery procedure).

Eclair will return BTC from closed channels to the wallet configured.
Any BTC found in the wallet can be used to fund the channels you choose to open.

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

* your Bitcoin Core wallet
* your Eclair channels

For Bitcoin Core, you need to backup the wallet file for the wallet that Eclair is using. You only need to do this once, when the wallet is
created. See [Managing Wallets](https://github.com/bitcoin/bitcoin/blob/master/doc/managing-wallets.md) in the Bitcoin Core documentation for more information.

For Eclair, the files that you need to backup are located in your data directory. You must backup:

* your seeds (`node_seed.dat` and `channel_seed.dat`)
* your channel database (`eclair.sqlite.bak` under directory `mainnet`, `testnet`, `signet` or `regtest` depending on which chain you're running on)

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

A [Dockerfile](Dockerfile) x86_64 image is built on each commit on [docker hub](https://hub.docker.com/r/acinq/eclair) for running a dockerized eclair-node.
For arm64 platforms you can use an [arm64 Dockerfile](contrib/arm64v8.Dockerfile) to build your own arm64 container.

You can use the `JAVA_OPTS` environment variable to set arguments to `eclair-node`.

```shell
docker run -ti --rm -e "JAVA_OPTS=-Xmx512m -Declair.api.binding-ip=0.0.0.0 -Declair.node-alias=node-pm -Declair.printToConsole" acinq/eclair
```

If you want to persist the data directory, you can make the volume to your host with the `-v` argument, as the following example:

```shell
docker run -ti --rm -v "/path_on_host:/data" -e "JAVA_OPTS=-Declair.printToConsole" acinq/eclair
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

You can find more details about plugins in the [eclair-plugins](https://github.com/ACINQ/eclair-plugins) repository.

## Testnet usage

Eclair is configured to run on mainnet by default, but you can still run it on testnet (or regtest/signet): start your Bitcoin node in
 testnet mode (add `testnet=1` in `bitcoin.conf` or start with `-testnet`), and change Eclair's chain parameter and Bitcoin RPC port:

```conf
eclair.chain=testnet
eclair.bitcoind.rpcport=18332
```

For regtest, add `regtest=1` in `bitcoin.conf` or start with `-regtest`, and modify `eclair.conf`:

```conf
eclair.chain = "regtest"
eclair.bitcoind.rpcport=18443
```

For signet, add `signet=1` in `bitcoin.conf` or start with `-signet`, and modify `eclair.conf`:

```conf
eclair.chain = "signet"
eclair.bitcoind.rpcport=38332
```

You may also want to take advantage of the new configuration sections in `bitcoin.conf` to manage parameters that are network specific,
so you can easily run your Bitcoin node on both mainnet and testnet. For example you could use:

```conf
server=1
txindex=1
addresstype=bech32
changetype=bech32

[main]
rpcuser=<your-mainnet-rpc-user-here>
rpcpassword=<your-mainnet-rpc-password-here>
zmqpubhashblock=tcp://127.0.0.1:29000
zmqpubrawtx=tcp://127.0.0.1:29000

[test]
rpcuser=<your-testnet-rpc-user-here>
rpcpassword=<your-testnet-rpc-password-here>
zmqpubhashblock=tcp://127.0.0.1:29001
zmqpubrawtx=tcp://127.0.0.1:29001
```

## Tools

* [Demo Shop](https://starblocks.acinq.co/) - an example testnet Lightning web shop.
* [Network Explorer](https://explorer.acinq.co/) - a Lightning network visualization tool.

## Resources

* [1] [The Bitcoin Lightning Network: Scalable Off-Chain Instant Payments](https://lightning.network/lightning-network-paper.pdf) by Joseph Poon and Thaddeus Dryja
* [2] [Reaching The Ground With Lightning](https://github.com/ElementsProject/lightning/raw/master/doc/deployable-lightning.pdf) by Rusty Russell
* [3] [Lightning Network Explorer](https://explorer.acinq.co) - Explore testnet LN nodes you can connect to
