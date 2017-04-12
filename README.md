![Eclair Logo](.readme/logo.png)

[![Build Status](https://travis-ci.org/ACINQ/eclair.svg?branch=master)](https://travis-ci.org/ACINQ/eclair)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Gitter chat](https://img.shields.io/badge/chat-on%20gitter-rose.svg)](https://gitter.im/ACINQ/eclair)

**Eclair** (french for Lightning) is a scala implementation of the Lightning Network. It can run with or without a GUI, and a JSON-RPC API is also available.

This software follows the [Lightning Network Specifications (BOLTs)](https://github.com/lightningnetwork/lightning-rfc). Other implementations include [lightning-c], [lit], and [lnd].
 
 ---
 
 :construction: Both the BOLTs and Eclair itself are a work in progress. Expect things to break/change!
 
 :warning: Eclair currently only runs on regtest or testnet. We recommend testing in regtest, as it allows you to generate blocks manually and not wait for confirmations.

---

## Lightning Network Specification Compliance
Please see the latest [release note](https://github.com/ACINQ/eclair/releases) for detailed information on BOLT compliance.

## Overview

![Eclair Demo](.readme/screen-1.png)

## Installation

:warning: **Those are valid for the most up-to-date, unreleased, version of eclair. Here are the [instructions for Eclair 0.2-alpha2](https://github.com/ACINQ/eclair/blob/v0.2-alpha2/README.md#installation)**.

### Configuring Bitcoin Core

Eclair needs a _synchronized_, _segwit-ready_, **_zeromq-enabled_**, _non-pruning_, _tx-indexing_ [Bitcoin Core](https://github.com/bitcoin/bitcoin) node. This means that on Windows you will need Bitcoin Core 0.14.0.

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

#### Windows

Just use the windows installer, it should create a shortcut on your desktop.

#### Linux, macOS or manual install on Windows

You need to first install java, more precisely a [JRE 1.8](http://www.oracle.com/technetwork/java/javase/downloads/jre8-downloads-2133155.html).

 :warning: If you are using the OpenJDK JRE, you will need to build OpenJFX yourself, or run the application in `--headless` mode.

Then download the latest fat jar and run:
```shell
java -jar eclair-node_xxxxxx-fat.jar
```

### Configuring Eclair

#### Command-line parameters

option         | description                     | default value
---------------|---------------------------------|--------------
--datadir      | Path to the data directory      | ~/.eclair
--headless     | Run Eclair without the GUI      |
--help, -h     | Display usage text              |


:warning: Using separate `datadir` is mandatory if you want to run **several instances of eclair** on the same machine. You will also have to change ports in the configuration (see below).

#### Configuration file

To change your node configuration, edit the file `eclair.conf` in `datadir`.

option                       | description               | default value
-----------------------------|---------------------------|--------------
 eclair.server.port          | TCP port                  | 9735
 eclair.api.port             | HTTP port                 | 8080
 eclair.bitcoind.rpcuser     | Bitcoin Core RPC user     | foo
 eclair.bitcoind.rpcpassword | Bitcoin Core RPC password | bar
 eclair.bitcoind.zmq         | Bitcoin Core ZMQ address  | tcp://127.0.0.1:29000

&rarr; see [`application.conf`](eclair-node/src/main/resources/application.conf) for full reference.

## JSON-RPC API

 method       |  params                                       | description
 -------------|-----------------------------------------------|-----------------------------------------------------------
  connect     | host, port, pubkey                            | connect to another lightning node through a secure connection
  open        | host, port, pubkey, fundingSatoshis, pushMsat | opens a channel with another lightning node
  peers       |                                               | list existing local peers
  channels    |                                               | list existing local channels
  channel     | channelId                                     | retrieve detailed information about a given channel
  network     |                                               | list all nodes that have been announced
  receive     | amountMsat                                    | generate a payment request for a given amount
  send        | amountMsat, paymentHash, nodeId               | send a payment to a lightning node
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

