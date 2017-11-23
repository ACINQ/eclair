# Usage instructions

## Your node's informations

### With a GUI

The GUI displays a status bar on the bottom on the window with the following data:

* Node's public key. Right click on this node to copy the node's public key or the node's URI.
* Node's server port
* Node's API port
* Bitcoin core's version
* Current chain (regtest, testnet, livenet)

### Command line

```shell

./eclair.cli openchannel <node_pubkey> <ip> <port> <capacity_in_satoshi> <push_in_millisatoshi>

```

## Open a channel

A channel is a bitcoin transaction between two parties, funded by one party and signed by both the funder and the fundee. To establish a channel between two nodes, the funder must set the address of the fundee and the amount of money he wants to lock in the channel.

* URI of the targeted node. This URI looks like this:

```shell

target_node_public_key@host:ip

```

Note: a node's public key is the unique identifier or the node on the network.

* Channel's capacity: this is the amount in Bitcoin that the funder wants to lock in the channel. When opening the channel, the balance will be equal to the capacity (unless the funder set a `push` amount)

* (optional) Push amount: This amount is sent by the funder to the fundee. Default value is 0. The `push` amount is deducted from the balance of the channel on the funder side. This behaviour is akin to opening a channel with a 0 `push` amount and send a payment to the fundee as soon as the channel reaches a ready state, and should be used by advanced users only.

### Dual funding

A channel can not be funded by the two parties yet. This feature will be available in the 1.1 version of the Lightning Network specifications.

### Channel's lifecycle

This are the possible states of the channel:

* **WAIT_FOR_INIT_INTERNAL**: 
* **WAIT_FOR_OPEN_CHANNEL**: channel is initializing, internal operations only
* **WAIT_FOR_ACCEPT_CHANNEL**: waiting for the fundee to accept the channel
* **WAIT_FOR_FUNDING_CREATED**: the funding transaction is being created by the two parties
* **WAIT_FOR_FUNDING_SIGNED**: both parties must sign the transaction
* **WAIT_FOR_FUNDING_LOCKED**: the transaction must have at least 1 confirmation
* **NORMAL**: the channel is opened and ready to receive, forward or send payments

### With the GUI

* Click on `File` > `Open Channel`.
* Enter the URI of the node you want to open a channel with.
* Enter the capacity of the channel. This amount must be lower than 0,167 Bitcoins.
* (optional) Enter a push amount
* Click OK
* Wait for 6 confirmations. Your channel should reach the `NORMAL` state.
* You can now send/receive/forward payments

### Command line (Linux only)

Run the following command:

```shell

./eclair.cli openchannel <node_pubkey> <ip> <port> <capacity_in_satoshi> <push_in_millisatoshi>

```

### WIth the JSON-RPC API

Link to the API.

## Close a channel



### With the GUI

### Command line

### WIth the JSON-RPC API

## Receive a payment

### With the GUI

### Command line

### WIth the JSON-RPC API

## Send a payment

### With the GUI

### Command line

### WIth the JSON-RPC API

