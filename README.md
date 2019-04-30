![Eclair Logo](.readme/logo.png)

[![Build Status](https://travis-ci.org/araspitzu/eclair.svg?branch=master)](https://travis-ci.org/araspitzu/eclair)
[![codecov](https://codecov.io/gh/araspitzu/eclair/branch/master/graph/badge.svg)](https://codecov.io/gh/araspitzu/eclair)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Gitter chat](https://img.shields.io/badge/chat-on%20gitter-red.svg)](https://gitter.im/ACINQ/eclair)

**Eclair** (French for Lightning) is a Scala implementation of the Lightning Network. It can run with or without a GUI, and a JSON API is also available.

This software follows the [Lightning Network Specifications (BOLTs)](https://github.com/lightningnetwork/lightning-rfc). Other implementations include [c-lightning](https://github.com/ElementsProject/lightning) and [lnd](https://github.com/LightningNetwork/lnd).
 
 ---
 
 :construction: Both the BOLTs and Eclair itself are still a work in progress. Expect things to break/change!
 
 :rotating_light: If you intend to run Eclair on mainnet:
 - Keep in mind that it is beta-quality software and **don't put too much money** in it
 - Eclair's JSON API should **NOT** be accessible from the outside world (similarly to Bitcoin Core API)
 - Specific [configuration instructions for mainnet](#mainnet-usage) are provided below (by default Eclair runs on testnet)
 
---

## Lightning Network Specification Compliance
Please see the latest [release note](https://github.com/ACINQ/eclair/releases) for detailed information on BOLT compliance.

## Overview

![Eclair Demo](.readme/screen-1.png)```

## JSON API

Eclair offers a feature rich HTTP API that enables application developers to easily integrate.

For more information please visit the [API documentation website](https://acinq.github.io/eclair).

:warning: You can still use the old API by setting the `eclair.api.use-old-api=true` parameter, but it is now deprecated and will soon be removed. The old documentation is still available [here](OLD-API-DOCS.md).

## Installation

### Configuring Bitcoin Core

:warning: Eclair requires Bitcoin Core 0.17.1 or higher. If you are upgrading an existing wallet, you need to create a new address and send all your funds to that address.

Eclair needs a _synchronized_, _segwit-ready_, **_zeromq-enabled_**, _wallet-enabled_, _non-pruning_, _tx-indexing_ [Bitcoin Core](https://github.com/bitcoin/bitcoin) node. 
Eclair will use any BTC it finds in the Bitcoin Core wallet to fund any channels you choose to open. Eclair will return BTC from closed channels to this wallet.

Run bitcoind with the following minimal `bitcoin.conf`:
```
testnet=1
server=1
rpcuser=foo
rpcpassword=bar
txindex=1
zmqpubrawblock=tcp://127.0.0.1:29000
zmqpubrawtx=tcp://127.0.0.1:29000
addresstype=p2sh-segwit
```

### Installing Eclair

Eclair is developed in [Scala](https://www.scala-lang.org/), a powerful functional language that runs on the JVM, and is packaged as a JAR (Java Archive) file. We provide 2 different packages, which internally use the same core libraries:
* eclair-node, which is a headless application that you can run on servers and desktops, and control from the command line
* eclair-node-gui, which also includes a JavaFX GUI

To run Eclair, you first need to install Java, we recommend that you use [OpenJDK 11](https://jdk.java.net/11/). Eclair will also run on Oracle JDK 1.8, Oracle JDK 11, and other versions of OpenJDK but we don't recommend using them.

Then download our latest [release](https://github.com/ACINQ/eclair/releases) and depending on whether or not you want a GUI run the following command:
* with GUI:
```shell
java -jar eclair-node-gui-<version>-<commit_id>.jar
```
* without GUI:
```shell
java -jar eclair-node-<version>-<commit_id>.jar
```

### Configuring Eclair

#### Configuration file

Eclair reads its configuration file, and write its logs, to `~/.eclair` by default.

To change your node's configuration, create a file named `eclair.conf` in `~/.eclair`. Here's an example configuration file:

```
eclair.chain=testnet
eclair.node-alias=eclair
eclair.node-color=49daaa
```

Here are some of the most common options:

name                         | description                                                                           | default value
-----------------------------|---------------------------------------------------------------------------------------|--------------
 eclair.chain                | Which blockchain to use: *regtest*, *testnet* or *mainnet*                            | testnet
 eclair.server.port          | Lightning TCP port                                                                    | 9735
 eclair.api.enabled          | Enable/disable the API                                                                | false. By default the API is disabled. If you want to enable it, you must set a password.
 eclair.api.port             | API HTTP port                                                                         | 8080
 eclair.api.password         | API password (BASIC)                                                                  | "" (must be set if the API is enabled)
 eclair.bitcoind.rpcuser     | Bitcoin Core RPC user                                                                 | foo
 eclair.bitcoind.rpcpassword | Bitcoin Core RPC password                                                             | bar
 eclair.bitcoind.zmqblock    | Bitcoin Core ZMQ block address                                                        | "tcp://127.0.0.1:29000"
 eclair.bitcoind.zmqtx       | Bitcoin Core ZMQ tx address                                                           | "tcp://127.0.0.1:29000"
 eclair.gui.unit             | Unit in which amounts are displayed (possible values: msat, sat, bits, mbtc, btc)     | btc

Quotes are not required unless the value contains special characters. Full syntax guide [here](https://github.com/lightbend/config/blob/master/HOCON.md).

&rarr; see [`reference.conf`](eclair-core/src/main/resources/reference.conf) for full reference. There are many more options!

#### Java Environment Variables

Some advanced parameters can be changed with java environment variables. Most users won't need this and can skip this section.

:warning: Using separate `datadir` is mandatory if you want to run **several instances of eclair** on the same machine. You will also have to change ports in `eclair.conf` (see above).

name                  | description                                | default value
----------------------|--------------------------------------------|--------------
eclair.datadir        | Path to the data directory                 | ~/.eclair
eclair.headless       | Run eclair without a GUI                   | 
eclair.printToConsole | Log to stdout (in addition to eclair.log)  |

For example, to specify a different data directory you would run the following command:
```shell
java -Declair.datadir=/tmp/node1 -jar eclair-node-gui-<version>-<commit_id>.jar
```

#### Logging

Eclair uses [`logback`](https://logback.qos.ch) for logging. To use a different configuration, and override the internal logback.xml, run:

```shell
java -Dlogback.configurationFile=/path/to/logback-custom.xml -jar eclair-node-gui-<version>-<commit_id>.jar
```

#### Backup

The files that you need to backup are located in your data directory. You must backup:
- your seed (`seed.dat`)
- your channel database (`eclair.sqlite.bak` under directory `mainnet`, `testnet` or `regtest` depending on which chain you're running on)

Your seed never changes once it has been created, but your channels will change whenever you receive or send payments. Eclair will
create and maintain a snapshot of its database, named `eclair.sqlite.bak`, in your data directory, and update it when needed. This file is 
always consistent and safe to use even when Eclair is running, and this is what you should backup regularly.

For example you could configure a `cron` task for your backup job. Or you could configure an optional notification script to be called by eclair once a new database snapshot has been created, using the following option:
```
eclair.backup-notify-script = "/absolute/path/to/script.sh"
```
Make sure that your script is executable and uses an absolute path name for `eclair.sqlite.bak`.

Note that depending on your filesystem, in your backup process we recommend first moving `eclair.sqlite.bak` to some temporary file 
before copying that file to your final backup location.


## Docker

A [Dockerfile](Dockerfile) image is built on each commit on [docker hub](https://hub.docker.com/r/acinq/eclair) for running a dockerized eclair-node.

You can use the `JAVA_OPTS` environment variable to set arguments to `eclair-node`.

```
docker run -ti --rm -e "JAVA_OPTS=-Xmx512m -Declair.api.binding-ip=0.0.0.0 -Declair.node-alias=node-pm -Declair.printToConsole" acinq/eclair
```

If you want to persist the data directory, you can make the volume to your host with the `-v` argument, as the following example:

```
docker run -ti --rm -v "/path_on_host:/data" -e "JAVA_OPTS=-Declair.printToConsole" acinq/eclair
```

## Plugins

For advanced usage, Eclair supports plugins written in Scala, Java, or any JVM-compatible language.

A valid plugin is a jar that contains an implementation of the [Plugin](eclair-node/src/main/scala/fr/acinq/eclair/Plugin.scala) interface.

Here is how to run Eclair with plugins:
```shell
java -jar eclair-node-<version>-<commit_id>.jar <plugin1.jar> <plugin2.jar> <...>
```

## Mainnet usage

Following are the minimum configuration files you need to use for Bitcoin Core and Eclair.

### Bitcoin Core configuration

```
testnet=0
server=1
rpcuser=<your-rpc-user-here>
rpcpassword=<your-rpc-password-here>
txindex=1
zmqpubrawblock=tcp://127.0.0.1:29000
zmqpubrawtx=tcp://127.0.0.1:29000
addresstype=p2sh-segwit
```

:warning: If you are using Bitcoin Core 0.17.0 you need to add following line to your `bitcoin.conf`:
```
deprecatedrpc=signrawtransaction
```

You may also want to take advantage of the new configuration sections in `bitcoin.conf` to manage parameters that are network specific, so you can easily run your bitcoin node on both mainnet and testnet. For example you could use:

```
server=1
txindex=1
addresstype=p2sh-segwit
deprecatedrpc=signrawtransaction
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

### Eclair configuration

```
eclair.chain=mainnet
eclair.bitcoind.rpcport=8332
eclair.bitcoind.rpcuser=<your-mainnet-rpc-user-here>
eclair.bitcoind.rpcpassword=<your-mainnet-rpc-password-here>
```

## Resources
- [1] [The Bitcoin Lightning Network: Scalable Off-Chain Instant Payments](https://lightning.network/lightning-network-paper.pdf) by Joseph Poon and Thaddeus Dryja
- [2] [Reaching The Ground With Lightning](https://github.com/ElementsProject/lightning/raw/master/doc/deployable-lightning.pdf) by Rusty Russell
- [3] [Lightning Network Explorer](https://explorer.acinq.co) - Explore testnet LN nodes you can connect to
