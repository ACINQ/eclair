# Run a full eclair node on desktop

## Supported platforms

Eclair is supported on the following platforms:

* Linux - as a self executable JAR file
* Mac OS - as a self executable JAR file
* Windows - as a self executable JAR file. An installer is also available.

## Prerequisites

* Java [JRE 1.8](http://www.oracle.com/technetwork/java/javase/downloads/jre8-downloads-2133155.html). Note that if you are using the windows installer, the JRE is included in the package.
* [Bitcoin Core](https://github.com/bitcoin/bitcoin) 0.14+: _synchronized_, _segwit-ready_, **_zeromq-enabled_**, _non-pruning_, _tx-indexing_ [Bitcoin Core](https://github.com/bitcoin/bitcoin) node. This means that on Windows you will need Bitcoin Core 0.14+. See below for the required bitcoin configuration.

```
testnet=1
server=1
rpcuser=XXX
rpcpassword=XXX
txindex=1
zmqpubrawblock=tcp://127.0.0.1:29000
zmqpubrawtx=tcp://127.0.0.1:29000
```

## Installation

### Manual installation (Linux, MacOS, Windows)

Download the latest JAR files available in https://github.com/ACINQ/eclair/releases.

The eclair node JAR file are named as follows:

```shell
# Without a user interface
eclair-node-<version>.jar

# With a User Interface
eclair-node-gui-<version>.jar
```

:warning: On linux if you are using the OpenJDK JRE and want to run Eclair with a GUI, you will need to build OpenJFX yourself.

Then move the JAR to your preferred folder.

### Autoinstaller (Windows only)

The installer is available for Windows only. It will install Eclair in your Windows user's `AppData` folder and create a shortcut on your desktop pointing to the Eclair node `.exe` file.

## Run your node

For the Windows autoinstall version, running the node is pretty straightforward. The following instructions will assume that you are using a manual installation.

Go to the folder containing the Eclair JAR file and run the following command:


```shell
# Without a user interface
java -jar eclair-node-<version>.jar

# With a User Interface
java -jar eclair-node-gui-<version>.jar
```

## Run Eclair node with advanced options

For advanced used case, some options are available, they can be changed through Java environment variables. Most users won't need this and can skip this section.

For users with the autoinstall version, you can set Java parameter by right clicking on the Eclair shortcut and add the parameter at the end of the target input field.

### Run Eclair GUI in headless mode

You can run a GUI version of eclair without the GUI by providing the `eclair.headless` parameter.

```shell
# Headless mode with the GUI JaR
java -jar eclair-node-gui-<version>.jar -Declair.headless
```

### Change the data directory

The default data directory is `~/.eclair`. You can change this with the `eclair.datadir` parameter.

```shell
java -jar eclair-node-<version>.jar -Declair.datadir="/path/to/custom/eclair/data/folder"
```

### Run several instances of Eclair on the same host

If you want to run several instance of eclair on the same machine, you'll have to use a separate `datadir` for each node. Use the `eclair.datadir` parameter as seen above.

You'll also have to use different ports for each running instance of Eclair. To do so, please take a look at the Eclair options page.

### Print to stdout when running

If you wish to log in the console in addition to `eclair.log`, add the `eclair.printToConsole` parameter.


## Eclair logs

Eclair logs are found in the `datadir` directory.

## Eclair JSON-RPC API

Eclair API listens API on the `eclair.api.port` port set in the configuration. This API exposes all the necessary methods to read the current state of the node, open/close channels and send/receive payments.

Please take a look a the Eclair API page.

## Configuring Eclair

Please take a look at the Eclair options page

## Use Eclair GUI

Please take a look at the Eclair usage page.
