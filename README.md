![Eclair Logo](.readme/logo.png)

[![Build Status](https://travis-ci.org/ACINQ/eclair.svg?branch=master)](https://travis-ci.org/ACINQ/eclair)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

**Eclair** (french for Lightning) is a scala implementation of the Lightning Network. It can run with or without a GUI, and a JSON-RPC API is also available.

This software follows the [Lightning Network Specifications (BOLTs)](https://github.com/lightningnetwork/lightning-rfc). Other implementations include [lightning-c], [lit], and [lnd].
 
 ---
 
 :construction: Both the BOLTs and Eclair itself are a work in progress. Expect things to break/change!
 
 :warning: Eclair currently only runs on regtest or testnet.

---

## Lightning Network Specification Compliance
Eclair 0.2-Alpha1 is compliant with the BOLTs at commit [06a5e6cbdbb4c6f8b8dab444de497cdb9c7d7f02](https://github.com/lightningnetwork/lightning-rfc/commit/06a5e6cbdbb4c6f8b8dab444de497cdb9c7d7f02), with the following caveats:

  - [X] BOLT 1: Base Protocol
  - [X] BOLT 2: Peer Protocol for Channel Management
  - [X] BOLT 3: Bitcoin Transaction and Script Formats
  - [X] BOLT 4: Onion Routing Protocol
  - [X] BOLT 5: Recommendations for On-chain Transaction Handling
    * If a revoked commitment tx is published, only the offender's main output will be stolen as punishment, not the HTLCs.
  - [X] BOLT 7: P2P Node and Channel Discovery
  - [X] BOLT 8: Encrypted and Authenticated Transport
  - [X] BOLT 9: Assigned Feature Flags

## Overview

![Eclair Demo](.readme/screen-1.png)

## Installation

### Configuring Bitcoin Core

Eclair needs a _synchronized_, _segwit-ready_, _non-pruning_, _tx-indexing_ [Bitcoin-core](https://github.com/bitcoin/bitcoin) node.

Run bitcoind with the following `bitcoin.conf`:
```
testnet=1
server=1
rpcuser=XXX
rpcpassword=XXX
txindex=1
```

### Installing Eclair

#### Windows

Just use the windows installer, it should create a shortcut on your desktop.

#### Linux, MacOs or manual install on Windows

You need to first install java, more precisely a JRE 1.8+.

Then just grab the latest fat jar and run:
```shell
java -jar eclair-node_xxxxxx-fat.jar
```

### Configuring Eclair

Eclair will create a directory in `~/.eclair` by default. You may change this directory's location using `--datadir <dir>` command line argument.

If you want to change configuration parameters, create a file named `eclair.conf` in eclair's home directory.


option                       | description               | default value
-----------------------------|---------------------------|--------------
 eclair.server.port          | TCP port                  | 9735
 eclair.http.port            | HTTP port                 | 8080
 eclair.bitcoind.rpcuser     | Bitcoin Core RPC user     | foo
 eclair.bitcoind.rpcpassword | Bitcoin Core RPC password | bar

&rarr; see [`application.conf`](eclair-node/src/main/resources/application.conf) for full reference.

## JSON-RPC API

 method       |  params                                      | description
 -------------|----------------------------------------------|-----------------------------------------------------------
  connect     | host, port, pubkey                           | connect to another lightning node through a secure connection
  open        | host, port, pubkey, fundingSatoshi, pushMsat | opens a channel with another lightning node
  peers       |                                              | list existing local peers
  channels    |                                              | list existing local channels
  channel     | channelIdHex                                 | retrieve detailed information about a given channel
  network     |                                              | list all nodes that have been announced
  genh        |                                              | generate a payment H
  send        | amountMsat, paymentHash, pubkey              | send a payment to a lightning node
  close       | channelIdHex                                 | close a channel
  close       | channelIdHex, scriptPubKey                   | close a channel and send the funds to the given scriptPubKey
  help        |                                              | display available methods

## Resources
- [1]  [The Bitcoin Lightning Network: Scalable Off-Chain Instant Payments](https://lightning.network/lightning-network-paper.pdf) by Joseph Poon and Thaddeus Dryja
- [2]  [Reaching The Ground With Lightning](https://github.com/ElementsProject/lightning/raw/master/doc/deployable-lightning.pdf) by Rusty Russell

[Amiko-Pay]: https://github.com/cornwarecjp/amiko-pay
[lightning-c]: https://github.com/ElementsProject/lightning
[lnd]: https://github.com/LightningNetwork/lnd
[lit]: https://github.com/mit-dci/lit
[Thunder]: https://github.com/blockchain/thunder

