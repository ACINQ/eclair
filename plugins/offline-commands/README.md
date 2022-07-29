# Offline commands plugin

This plugin allows node operators to prepare commands that will be executed once the target peer is online.

## Build

To build this plugin, run the following command in this directory:

```sh
mvn package
```

## Run

To run eclair with this plugin, start eclair with the following command:

```sh
eclair-node-<version>/bin/eclair-node.sh <path-to-plugin-jar>/offline-commands-plugin-<version>.jar
```
