# Peerswap plugin

This plugin allows implements the PeerSwap protocol: https://github.com/ElementsProject/peerswap-spec/blob/main/peer-protocol.md

## Build

To build this plugin, run the following command in this directory:

```sh
mvn package
```

## Run

To run eclair with this plugin, start eclair with the following command:

```sh
eclair-node-<version>/bin/eclair-node.sh <path-to-plugin-jar>/peerswap-plugin-<version>.jar
```

## Commands

```sh
eclair-cli swapin --shortChannelId=<short-channel-id>> --amountSat=<amount>
eclair-cli swapout --shortChannelId=<short-channel-id>> --amountSat=<amount>
eclair-cli listswaps
eclair-cli cancelswap --swapId=<swap-id>
```