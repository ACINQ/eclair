[![Build Status](https://travis-ci.org/ACINQ/eclair.svg?branch=master)](https://travis-ci.org/ACINQ/eclair)

# eclair

A scala implementation of the Lightning Network. Eclair is french for Lightning.

This software follows the [BOLT specifications](https://github.com/rustyrussell/lightning-rfc), therefore it is compatible with Blockstream's [lightning-c](https://github.com/ElementsProject/lightning).

## Overview
The general idea is to have an actor per channel, everything being non-blocking.

A "blockchain watcher" is responsible for monitoring the blockchain, and sending events (eg. when the anchor is spent).

## Modules
* lightning-types: scala code generation using protobuf's compiler (wire protocol)
* eclair-node: actual implementation

## Usage

Prerequisites:
- A JRE or JDK depending on wether you want to compile yourself or not (preferably > 1.8)
- A running bitcoin-demo (testnet or regtest)

:warning: **eclair currently runs on segnet only. Do not try and modify it to run on bitcoin mainnet!**

Either run from source:
```
mvn exec:java -Dexec.mainClass=fr.acinq.eclair.Boot
```
Or grab the latest released jar and run:
```
java -jar eclair-core_2.11-*-capsule-fat.jar
```

*See [TESTING.md](TESTING.md) for more details on how to use this software.*

Available jvm options (see `application.conf` for full reference):
```
eclair.server.port (default: 45000)
eclair.http.port (default: 8080)
eclair.bitcoind.rpcuser (default: foo)
eclair.bitcoind.rpcpassword (default: bar)
```

## JSON-RPC API

 method       |  params                             | description
 -------------|-------------------------------------|-----------------------------------------------------------
  connect     | host, port, anchor_amount           | opens a channel with another eclair or lightningd instance
  list        |                                     | lists existing channels
  addhtlc     | channel_id, amount, rhash, locktime | sends an htlc
  fulfillhtlc | channel_id, r                       | fulfills an htlc
  close       | channel_id                          | closes a channel
  help        |                                     | displays available methods

## Status
- [X] Network
- [ ] Routing
- [X] Channel protocol
- [X] HTLC Scripts
- [ ] Unilateral close handling
- [ ] Relaying Payment
- [ ] Fee management
- [X] Blockchain watcher
- [ ] Storing states in a database

## Resources
- [1]  [The Bitcoin Lightning Network: Scalable Off-Chain Instant Payments](https://lightning.network/lightning-network-paper.pdf) by Joseph Poon and Thaddeus Dryja
- [2]  [Reaching The Ground With Lightning](https://github.com/ElementsProject/lightning/raw/master/doc/deployable-lightning.pdf) by Rusty Russell

## Other implementations
Name         | Language | Compatible
-------------|----------|------------
[Amiko-Pay]  | Python   | no
[lightning-c]| C        | yes
[lnd]        | Go       | no
[Thunder]    | Java     | no

[Amiko-Pay]: https://github.com/cornwarecjp/amiko-pay
[lightning-c]: https://github.com/ElementsProject/lightning
[lnd]: https://github.com/LightningNetwork/lnd
[Thunder]: https://github.com/blockchain/thunder
