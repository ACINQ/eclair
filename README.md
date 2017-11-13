![Eclair Logo](.readme/logo.png)

[![Build Status](https://travis-ci.org/ACINQ/eclair.svg?branch=master)](https://travis-ci.org/ACINQ/eclair)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Gitter chat](https://img.shields.io/badge/chat-on%20gitter-rose.svg)](https://gitter.im/ACINQ/eclair)

**Eclair** (french for Lightning) is a scala implementation of the Lightning Network. It can run with or without a GUI, and a JSON-RPC API is also available.

This software follows the [Lightning Network Specifications (BOLTs)](https://github.com/lightningnetwork/lightning-rfc). Other implementations include [lightning-c], [lit], and [lnd].
 
 ---
 
 :construction: Both the BOLTs and Eclair itself are a work in progress. Expect things to break/change!
 
 :warning: Eclair currently only runs on regtest or testnet. We recommend testing in regtest, as it allows you to generate blocks manually and not wait for confirmations.
 
 :rotating_light: We had reports of Eclair being tested on various segwit-enabled blockchains. Keep in mind that Eclair is still alpha quality software, by using it with actual coins you are putting your funds at risk!

---

## Lightning Network Specification Compliance
Please see the latest [release note](https://github.com/ACINQ/eclair/releases) for detailed information on BOLT compliance.

## Overview

![Eclair Demo](.readme/screen-1.png)

## Installation

:warning: **Those are valid for the most up-to-date, unreleased, version of eclair. Here are the [instructions for Eclair 0.2-alpha5](https://github.com/ACINQ/eclair/blob/v0.2-alpha5/README.md#installation)**.

### Configuring Bitcoin Core

Eclair needs a _synchronized_, _segwit-ready_, **_zeromq-enabled_**, _non-pruning_, _tx-indexing_ [Bitcoin Core](https://github.com/bitcoin/bitcoin) node. This means that on Windows you will need Bitcoin Core 0.14+.

Run bitcoind with the following minimal `bitcoin.conf`:
```
regtest=1
server=1
rpcuser=XXX
rpcpassword=XXX
txindex=1
zmqpubrawblock=tcp://127.0.0.1:29000
zmqpubrawtx=tcp://127.0.0.1:29000
```

### Installing Eclair

The released binaries can be downloaded [here](https://github.com/ACINQ/eclair/releases).

#### Windows

Just use the windows installer, it should create a shortcut on your desktop.

#### Linux, macOS or manual install on Windows

You need to first install java, more precisely a [JRE 1.8](http://www.oracle.com/technetwork/java/javase/downloads/jre8-downloads-2133155.html).

 :warning: If you are using the OpenJDK JRE, you will need to build OpenJFX yourself, or run the application in headless mode (see below).

Then download the latest fat jar and depending on whether or not you want a GUI run the following command:
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

Eclair reads its configuration file, and write its logs, to a `datadir` directory, located in `~/.eclair` by default.

To change your node's configuration, create a file named `eclair.conf` in `datadir`. Here's an example configuration file:

```
eclair.server.port=9735
eclair.node-alias=eclair
eclair.node-color=49daaa
```

Here are some of the most common options:

name                         | description               | default value
-----------------------------|---------------------------|--------------
 eclair.server.port          | Lightning TCP port        | 9735
 eclair.api.port             | API HTTP port             | 8080
 eclair.bitcoind.rpcuser     | Bitcoin Core RPC user     | foo
 eclair.bitcoind.rpcpassword | Bitcoin Core RPC password | bar
 eclair.bitcoind.zmq         | Bitcoin Core ZMQ address  | tcp://127.0.0.1:29000

The options syntax follows [the HOCON format](https://github.com/lightbend/config/blob/master/HOCON.md). Quotes are not required unless the value contains special characters.

&rarr; see [`reference.conf`](eclair-core/src/main/resources/reference.conf) for full reference. There are many more options!

#### Java Environment Variables

Some advanced parameters can be changed with java environment variables. Most users won't need this and can skip this section.

:warning: Using separate `datadir` is mandatory if you want to run **several instances of eclair** on the same machine. You will also have to change ports in eclair.conf (see above).

name                  | description                                | default value
----------------------|--------------------------------------------|--------------
eclair.datadir        | Path to the data directory                 | ~/.eclair
eclair.headless       | Run eclair without a GUI                   | 
eclair.printToConsole | Log to stdout (in addition to eclair.log)  |

For example, to specify a different data directory you would run the following command:
```shell
java -Declair.datadir=/tmp/node1 -jar eclair-node-gui-<version>-<commit_id>.jar
```

## JSON-RPC API

 method       |  params                                       | description
 -------------|-----------------------------------------------|-----------------------------------------------------------
  getinfo     |                                               | return basic node information (id, chain hash, current block height) 
  connect     | host, port, nodeId                            | connect to another lightning node through a secure connection
  open        | host, port, nodeId, fundingSatoshis, pushMsat | opens a channel with another lightning node
  peers       |                                               | list existing local peers
  channels    |                                               | list existing local channels
  channel     | channelId                                     | retrieve detailed information about a given channel
  allnodes    |                                               | list all known nodes
  allchannels |                                               | list all known channels
  receive     | amountMsat, description                       | generate a payment request for a given amount
  send        | amountMsat, paymentHash, nodeId               | send a payment to a lightning node
  send        | paymentRequest                                | send a payment to a lightning node using a BOLT11 payment request
  send        | paymentRequest, amountMsat                    | send a payment to a lightning node using a BOLT11 payment request and a custom amount
  close       | channelId                                     | close a channel
  close       | channelId, scriptPubKey (optional)            | close a channel and send the funds to the given scriptPubKey
  help        |                                               | display available methods

## Resources
- [1]  [The Bitcoin Lightning Network: Scalable Off-Chain Instant Payments](https://lightning.network/lightning-network-paper.pdf) by Joseph Poon and Thaddeus Dryja
- [2]  [Reaching The Ground With Lightning](https://github.com/ElementsProject/lightning/raw/master/doc/deployable-lightning.pdf) by Rusty Russell

[Amiko-Pay]: https://github.com/cornwarecjp/amiko-pay
[lightning-c]: https://github.com/ElementsProject/lightning
[lnd]: https://github.com/LightningNetwork/lnd
[lit]: https://github.com/mit-dci/lit
[Thunder]: https://github.com/blockchain/thunder

