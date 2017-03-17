![Eclair Logo](.readme/logo.png)

[![Build Status](https://travis-ci.org/ACINQ/eclair.svg?branch=wip-bolts)](https://travis-ci.org/ACINQ/eclair)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

**Eclair** is a scala implementation of the Lightning Network. Eclair is french for Lightning.

This software follows the [Lightning Network Specifications](https://github.com/lightningnetwork/lightning-rfc), also known as BOLTs. Other implementations of BOLTs include:
- [lightning-c]
- [lnd]
- [lit]
 
 :construction: Both BOLTs and Eclair itself are a work in progress. Expect things to break/change!
 
 :warning: Eclair currently only runs on regtest or testnet.

---

## Overview

Eclair intends to be a full-fledged Lightning node. It can run with or without a GUI, and a JSON-RPC API is also available.

Available actions:
- Open a channel with another lightning node
- Display opened channels with the node (status, balance, capacity, ...)
- Receive payments from another node
- Send payments to another node

![Eclair Demo](.readme/screen-1.png)

## Installation

### Configuring Bitcoin Core

Eclair needs a synchronized, segwit-ready, non-pruning, tx-indexing [Bitcoin-core](https://github.com/bitcoin/bitcoin) node.

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

 method       |  params                             | description
 -------------|-------------------------------------|-----------------------------------------------------------
  connect     | host, port, anchor_amount           | opens a channel with another eclair or lightningd instance
  list        |                                     | lists existing channels
  addhtlc     | channel_id, amount, rhash, locktime | sends an htlc
  fulfillhtlc | channel_id, r                       | fulfills an htlc
  close       | channel_id                          | closes a channel
  help        |                                     | displays available methods

---

## Project Status
- [X] Network
- [X] Routing (simple IRC prototype)
- [X] Channel protocol
- [X] HTLC Scripts
- [X] Unilateral close handling
- [X] Relaying Payment
- [ ] Fee management
- [X] Blockchain watcher
- [ ] Storing states in a database

## Other implementations
Name         | Language | Compatible
-------------|----------|------------
[Amiko-Pay]  | Python   | no
[lightning-c]| C        | not yet
[lnd]        | Go       | not yet
[lit]        | Go       | not yet
[Thunder]    | Java     | no

## Resources
- [1]  [The Bitcoin Lightning Network: Scalable Off-Chain Instant Payments](https://lightning.network/lightning-network-paper.pdf) by Joseph Poon and Thaddeus Dryja
- [2]  [Reaching The Ground With Lightning](https://github.com/ElementsProject/lightning/raw/master/doc/deployable-lightning.pdf) by Rusty Russell

[Amiko-Pay]: https://github.com/cornwarecjp/amiko-pay
[lightning-c]: https://github.com/ElementsProject/lightning
[lnd]: https://github.com/LightningNetwork/lnd
[lit]: https://github.com/mit-dci/lit
[Thunder]: https://github.com/blockchain/thunder

