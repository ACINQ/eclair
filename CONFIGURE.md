# Configure Eclair

Eclair reads its configuration from the `eclair.conf` file in the `datadir` directory. By default, `datadir` is `~/.eclair`, you can override this through a parameters when launching eclair. Detailed information is available in this page.

## Change your node's configuration

The first step is to actually create the configuration file. Go to `datadir` and create a file name `eclair.conf`. Encoding should be UTF-8.

Options are set as key-value pairs. The syntax follows the <TODO link>. Value do not need to be surrounded by quotes, except if they contain special characters.

This is a common configuration file which overrides the default server port, node's label and node's color:

```shell

# server port
eclair.server.port=9737

# node's label
eclair.node-alias="my node"

# rgb node's color
eclair.node-color=49daaa

```

## Options reference

Here are some of the most common options:

name                         | description               | default value
-----------------------------|---------------------------|--------------
 eclair.server.port          | Lightning TCP port        | 9735
 eclair.api.port             | API HTTP port             | 8080
 eclair.bitcoind.rpcuser     | Bitcoin Core RPC user     | foo
 eclair.bitcoind.rpcpassword | Bitcoin Core RPC password | bar
 eclair.bitcoind.zmq         | Bitcoin Core ZMQ address  | tcp://127.0.0.1:29000

&rarr; see [`reference.conf`](eclair-core/src/main/resources/reference.conf) for full reference. There are many more options!
