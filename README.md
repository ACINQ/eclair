[![Build Status](https://travis-ci.org/ACINQ/eclair.svg?branch=master)](https://travis-ci.org/ACINQ/eclair)

# eclair

A scala implementation of the Lightning Network. Eclair is french for Lightning.

More precisely, this is an implementation of Rusty's [deployable lightning](https://github.com/ElementsProject/lightning/raw/master/doc/deployable-lightning.pdf). In particular it uses the same wire protocol, and almost the same state machine.

## Overview
The general idea is to have an actor per channel, everything being non-blocking.

A "blockchain watcher" is responsible for monitoring the blockchain, and sending events (eg. when the anchor is spent).

## Modules
* lightning-types: scala code generation using protobuf's compiler (wire protocol)
* eclair-demo: actual implementation

## Usage

Prerequisites:
- A JRE or JDK depending on wether you want to compile yourself or not (preferably > 1.8)
- A running bitcoin-core (testnet or regtest)

:warning: **eclair demo currently runs on testnet or regtest only. Do not try and modify it to run on bitcoin mainnet!**

Either run from source:
```
mvn exec:java -Dexec.mainClass=fr.acinq.eclair.Boot
```
Or grab the latest released jar and run:
```
java -jar eclair-demo_2.11-*-capsule-fat.jar
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
- [X] Channel state machine
- [X] HTLC Scripts
- [ ] Unilateral close handling
- [ ] Relaying Payment
- [X] Blockchain watcher
- [ ] Storing states in a database

## Ressources

- [1] Lightning Network by Joseph Poon and Thaddeus Dryja ([website](http://lightning.network)), [github repository (golang)](https://github.com/LightningNetwork/lnd)
- [2] Deployable Lightning by Rusty Russel (Blockstream), [github repository (C)](https://github.com/ElementsProject/lightning)
- [3] Thunder Network by Mats Jerratsch (Blockchain.info), [github repository (Java)](https://github.com/matsjj/thundernetwork)
