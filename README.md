![Eclair Logo](.readme/logo.png)

[![Build Status](https://github.com/ACINQ/eclair/workflows/Build%20&%20Test/badge.svg)](https://github.com/ACINQ/eclair/actions?query=workflow%3A%22Build+%26+Test%22)
[![codecov](https://codecov.io/gh/acinq/eclair/branch/master/graph/badge.svg)](https://codecov.io/gh/acinq/eclair)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Gitter chat](https://img.shields.io/badge/chat-on%20gitter-red.svg)](https://gitter.im/ACINQ/eclair)

**Eclair** (French for Lightning) is a Scala implementation of the Lightning Network.

This software follows the [Lightning Network Specifications (BOLTs)](https://github.com/lightningnetwork/lightning-rfc). Other implementations include [c-lightning](https://github.com/ElementsProject/lightning) and [lnd](https://github.com/LightningNetwork/lnd).

---

:construction: Both the BOLTs and Eclair itself are still a work in progress. Expect things to break/change!

:rotating_light: If you run Eclair on mainnet (which is the default setting):

* Keep in mind that it is beta-quality software and **don't put too much money** in it
* Eclair's JSON API should **NOT** be accessible from the outside world (similarly to Bitcoin Core API)

---

## Lightning Network Specification Compliance

Please see the latest [release note](https://github.com/ACINQ/eclair/releases) for detailed information on BOLT compliance.

## JSON API

Eclair offers a feature rich HTTP API that enables application developers to easily integrate.

For more information please visit the [API documentation website](https://acinq.github.io/eclair).

## Documentation

Please visit our [docs](./docs) and [wiki](https://github.com/acinq/eclair/wiki) to find detailed instructions on how to configure your
node, connect to other nodes, open channels, send and receive payments and more advanced scenario.

You will find detailed guides and frequently asked questions there.

## Installation

### Configuring Bitcoin Core

:warning: Eclair requires Bitcoin Core 0.18.1, 0.19.1 or 0.20.1. If you are upgrading an existing wallet, you may need to create a new address and send all your funds to that address.

Eclair needs a _synchronized_, _segwit-ready_, **_zeromq-enabled_**, _wallet-enabled_, _non-pruning_, _tx-indexing_ [Bitcoin Core](https://github.com/bitcoin/bitcoin) node.

You can configure your Bitcoin node to use either `p2sh-segwit` addresses or `bech32` addresses, Eclair is compatible with both modes.
If your wallet has "non-segwit UTXOs" (outputs that are neither `p2sh-segwit` or `bech32`), you must send them to a `p2sh-segwit` or `bech32` address before running eclair.

Run bitcoind with the following minimal `bitcoin.conf`:

```conf
server=1
rpcuser=foo
rpcpassword=bar
txindex=1
zmqpubrawblock=tcp://127.0.0.1:29000
zmqpubrawtx=tcp://127.0.0.1:29000
```

### Installing Eclair

Eclair is developed in [Scala](https://www.scala-lang.org/), a powerful functional language that runs on the JVM, and is packaged as a ZIP archive.

To run Eclair, you first need to install Java, we recommend that you use [OpenJDK 11](https://adoptopenjdk.net/?variant=openjdk11&jvmVariant=hotspot). Other runtimes also work but we don't recommend using them.

Then download our latest [release](https://github.com/ACINQ/eclair/releases), unzip the archive and run the following command:

```shell
eclair-node-<version>-<commit_id>/bin/eclair-node.sh
```

You can then control your node via the [eclair-cli](https://github.com/ACINQ/eclair/wiki/Usage) or the [API](https://github.com/ACINQ/eclair/wiki/API).

### Configuring Eclair

#### Configuration file

Eclair reads its configuration file, and write its logs, to `~/.eclair` by default.

To change your node's configuration, create a file named `eclair.conf` in `~/.eclair`. Here's an example configuration file:

```conf
eclair.node-alias=eclair
eclair.node-color=49daaa
```

Here are some of the most common options:

name                         | description                                                                           | default value
-----------------------------|---------------------------------------------------------------------------------------|--------------
 eclair.chain                | Which blockchain to use: *regtest*, *testnet* or *mainnet*                            | mainnet
 eclair.server.port          | Lightning TCP port                                                                    | 9735
 eclair.api.enabled          | Enable/disable the API                                                                | false. By default the API is disabled. If you want to enable it, you must set a password.
 eclair.api.port             | API HTTP port                                                                         | 8080
 eclair.api.password         | API password (BASIC)                                                                  | "" (must be set if the API is enabled)
 eclair.bitcoind.rpcuser     | Bitcoin Core RPC user                                                                 | foo
 eclair.bitcoind.rpcpassword | Bitcoin Core RPC password                                                             | bar
 eclair.bitcoind.zmqblock    | Bitcoin Core ZMQ block address                                                        | "tcp://127.0.0.1:29000"
 eclair.bitcoind.zmqtx       | Bitcoin Core ZMQ tx address                                                           | "tcp://127.0.0.1:29000"
 eclair.bitcoind.wallet      | Bitcoin Core wallet name                                                              | ""

Quotes are not required unless the value contains special characters. Full syntax guide [here](https://github.com/lightbend/config/blob/master/HOCON.md).

&rarr; see [here](./docs/Configure.md) for more configuration options.

#### Configure Bitcoin Core wallet

Eclair will use the default loaded Bitcoin Core wallet to fund any channels you choose to open.
If you want to use a different wallet from the default one, you must set `eclair.bitcoind.wallet` accordingly in your `eclair.conf`.

:warning: Once a wallet is configured, you must be very careful if you want to change it: changing the wallet when you have channels open may result in a loss of funds (or a complex recovery procedure).

Eclair will return BTC from closed channels to the wallet configured.
Any BTC found in the wallet can be used to fund the channels you choose to open.

#### Java Environment Variables

Some advanced parameters can be changed with java environment variables. Most users won't need this and can skip this section.

:warning: Using separate `datadir` is mandatory if you want to run **several instances of eclair** on the same machine. You will also have to change ports in `eclair.conf` (see above).

name                  | description                                | default value
----------------------|--------------------------------------------|--------------
eclair.datadir        | Path to the data directory                 | ~/.eclair
eclair.printToConsole | Log to stdout (in addition to eclair.log)  |

For example, to specify a different data directory you would run the following command:

```shell
eclair-node-<version>-<commit_id>/bin/eclair-node.sh -Declair.datadir=/tmp/node1
```

#### Logging

Eclair uses [`logback`](https://logback.qos.ch) for logging. To use a different configuration, and override the internal logback.xml, run:

```shell
eclair-node-<version>-<commit_id>/bin/eclair-node.sh -Dlogback.configurationFile=/path/to/logback-custom.xml
```

#### Backup

The files that you need to backup are located in your data directory. You must backup:

* your seeds (`node_seed.dat` and `channel_seed.dat`)
* your channel database (`eclair.sqlite.bak` under directory `mainnet`, `testnet` or `regtest` depending on which chain you're running on)

Your seeds never change once they have been created, but your channels will change whenever you receive or send payments. Eclair will
create and maintain a snapshot of its database, named `eclair.sqlite.bak`, in your data directory, and update it when needed. This file is
always consistent and safe to use even when Eclair is running, and this is what you should backup regularly.

For example you could configure a `cron` task for your backup job. Or you could configure an optional notification script to be called by eclair once a new database snapshot has been created, using the following option:

```conf
eclair.backup-notify-script = "/absolute/path/to/script.sh"
```

Make sure that your script is executable and uses an absolute path name for `eclair.sqlite.bak`.

Note that depending on your filesystem, in your backup process we recommend first moving `eclair.sqlite.bak` to some temporary file
before copying that file to your final backup location.

## Docker

A [Dockerfile](Dockerfile) image is built on each commit on [docker hub](https://hub.docker.com/r/acinq/eclair) for running a dockerized eclair-node.

You can use the `JAVA_OPTS` environment variable to set arguments to `eclair-node`.

```shell
docker run -ti --rm -e "JAVA_OPTS=-Xmx512m -Declair.api.binding-ip=0.0.0.0 -Declair.node-alias=node-pm -Declair.printToConsole" acinq/eclair
```

If you want to persist the data directory, you can make the volume to your host with the `-v` argument, as the following example:

```shell
docker run -ti --rm -v "/path_on_host:/data" -e "JAVA_OPTS=-Declair.printToConsole" acinq/eclair
```

If you enabled the API you can check the status of eclair using the command line tool:

```shell
docker exec <container_name> eclair-cli -p foobar getinfo
```

## Plugins

For advanced usage, Eclair supports plugins written in Scala, Java, or any JVM-compatible language.

A valid plugin is a jar that contains an implementation of the [Plugin](eclair-node/src/main/scala/fr/acinq/eclair/Plugin.scala) interface, and 
a manifest entry for `Main-Class` with the FQDN of the implementation.

Here is how to run Eclair with plugins:

```shell
eclair-node-<version>-<commit_id>/bin/eclair-node.sh <plugin1.jar> <plugin2.jar> <...>
```

## Testnet usage

Eclair is configured to run on mainnet by default, but you can still run it on testnet (or regtest): start your Bitcoin Node in
 testnet mode (add `testnet=1` in `bitcoin.conf` or start with `-testnet`), and change Eclair's chain parameter and Bitcoin RPC port:

```conf
eclair.chain=testnet
eclair.bitcoind.rpcport=18332
```

You may also want to take advantage of the new configuration sections in `bitcoin.conf` to manage parameters that are network specific,
so you can easily run your bitcoin node on both mainnet and testnet. For example you could use:

```conf
server=1
txindex=1
[main]
rpcuser=<your-mainnet-rpc-user-here>
rpcpassword=<your-mainnet-rpc-password-here>
zmqpubrawblock=tcp://127.0.0.1:29000
zmqpubrawtx=tcp://127.0.0.1:29000
[test]
rpcuser=<your-testnet-rpc-user-here>
rpcpassword=<your-testnet-rpc-password-here>
zmqpubrawblock=tcp://127.0.0.1:29001
zmqpubrawtx=tcp://127.0.0.1:29001
```

## Resources

* [1] [The Bitcoin Lightning Network: Scalable Off-Chain Instant Payments](https://lightning.network/lightning-network-paper.pdf) by Joseph Poon and Thaddeus Dryja
* [2] [Reaching The Ground With Lightning](https://github.com/ElementsProject/lightning/raw/master/doc/deployable-lightning.pdf) by Rusty Russell
* [3] [Lightning Network Explorer](https://explorer.acinq.co) - Explore testnet LN nodes you can connect to
