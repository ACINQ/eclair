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

## Usage

This plugin adds a new API to `eclair` called `offlineclose`, with the following arguments:

```sh
eclair-cli offlineclose --channelIds=<comma-separated list of channels to close>
    --forceCloseAfterHours=<force close after this delay if peer is unresponsive>
    --scriptPubKey=<optional closing script>
    --preferredFeerateSatByte=<closing tx feerate>
    --minFeerateSatByte=<closing tx min feerate>
    --maxFeerateSatByte=<closing tx max feerate>
```

The `channelIds` argument is mandatory; all other arguments are optional.
The plugin will attempt to close the requested channels once they're online and will write a line in the logs when it succeeds.
