# Configuring Eclair

Eclair reads its configuration file, and writes its logs, to `~/.eclair` by default.
You can change this behavior with the `eclair.datadir` parameter:

```sh
eclair-node.sh -Declair.datadir="/path/to/custom/eclair/data/folder"
```

## Change your node's configuration

The first step is to **actually create the configuration file**.
Go to `eclair.datadir` and create a file named `eclair.conf`.
The encoding should be UTF-8.

Options are set as key-value pairs and follow the [HOCON syntax](https://github.com/lightbend/config/blob/master/HOCON.md).
Values do not need to be surrounded by quotes, except if they contain special characters.

## Options reference

Here are some of the most common options:

name                         | description                                                | default value
-----------------------------|------------------------------------------------------------|--------------
 eclair.chain                | Which blockchain to use: *regtest*, *testnet* or *mainnet* | mainnet
 eclair.server.port          | Lightning TCP port                                         | 9735
 eclair.api.port             | API HTTP port                                              | 8080
 eclair.api.enabled          | Enables the JSON API                                       | false
 eclair.api.password         | Password protecting the API (BASIC auth)                   | _no default_
 eclair.bitcoind.rpcuser     | Bitcoin Core RPC user                                      | foo
 eclair.bitcoind.rpcpassword | Bitcoin Core RPC password                                  | bar
 eclair.bitcoind.zmqblock    | Bitcoin Core ZMQ block address                             | "tcp://127.0.0.1:29000"
 eclair.bitcoind.zmqtx       | Bitcoin Core ZMQ tx address                                | "tcp://127.0.0.1:29000"
 eclair.bitcoind.wallet      | Bitcoin Core wallet name                                   | ""
 eclair.server.public-ips    | List of node public ip                                     | _no default_

&rarr; see [`reference.conf`](https://github.com/ACINQ/eclair/blob/master/eclair-core/src/main/resources/reference.conf) for full reference. There are many more options!

## Customize features

Eclair ships with a set of features that are activated by default, and some experimental or optional features that can be activated by users.
The list of supported features can be found in the [reference configuration](https://github.com/ACINQ/eclair/blob/master/eclair-core/src/main/resources/reference.conf).

To enable a non-default feature, you simply need to add the following to your `eclair.conf`:

```conf
eclair.features {
    official_feature_name = optional|mandatory
}
```

For example, to activate `option_static_remotekey`:

```conf
eclair.features {
    option_static_remotekey = optional
}
```

Note that you can also disable some default features:

```conf
eclair.features {
    initial_routing_sync = disabled
}
```

It's usually risky to activate non-default features or disable default features: make sure you fully understand a feature (and the current implementation status, detailed in the release notes) before doing so.

Eclair supports per-peer features. Suppose you are connected to Alice and Bob, you can use a different set of features with Alice than the one you use with Bob.
When experimenting with non-default features, we recommend using this to scope the peers you want to experiment with.

This is done with the `override-features` configuration parameter in your `eclair.conf`:

```conf
eclair.override-features = [
    {
        nodeId = "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"
        features {
            initial_routing_sync = disabled
            option_static_remotekey = optional
        }
    },
    {
        nodeId = "<another nodeId>"
        features {
            option_static_remotekey = optional
            option_support_large_channel = optional
        }
    },
]
```

## Customize feerate tolerance

In order to secure your channels' funds against attacks, your eclair node keeps an up-to-date estimate of on-chain feerates (based on your Bitcoin node's estimations).
When that estimate deviates from what your peers estimate, eclair may automatically close channels that are at risk to guarantee the safety of your funds.

Since predicting the future is hard and imperfect, eclair has a tolerance for deviations, governed by the following parameters:

```conf
on-chain-fees {
    feerate-tolerance {
      ratio-low = 0.5 // will allow remote fee rates as low as half our local feerate
      ratio-high = 10.0 // will allow remote fee rates as high as 10 times our local feerate
    }
}
```

We do not recommend changing these values unless you really know what you're doing.
But if you have a trust relationship with some specific peers and know they will never try to cheat you, you can increase the tolerance specifically for those peers.
On the other hand, if you have channels with peers you suspect may try to attack you, you can decrease the tolerance specifically for those peers.

```conf
on-chain-fees {
    override-feerate-tolerance = [
        {
            nodeid = "<nodeId of a trusted peer>"
            feerate-tolerance {
                ratio-low = 0.1 // will allow remote fee rates as low as 10% our local feerate
                ratio-high = 15.0 // will allow remote fee rates as high as 15 times our local feerate
            }
        },
        {
            nodeid = "<nodeId of a peer we don't trust at all>"
            feerate-tolerance {
                // will only allow remote fees between 75% and 200% of our local feerate
                ratio-low = 0.75
                ratio-high = 2.0
            }
        }
    ]
}
```

## Examples

### Basic configuration

This is a common configuration file which overrides the default server port, node's label and node's color and enables the API (needed to interact with your node with `eclair-cli`):

```conf
# server port
eclair.server.port=9737

# node's label
eclair.node-alias="my node"
# rgb node's color
eclair.node-color=49daaa

eclair.api.enabled=true
# You should set a real password here.
eclair.api.password=foobar
# Make sure this port isn't accessible from the internet!
eclair.api.port=8080
```

### Regtest mode

To run with Bitcoin's `regtest` mode, you need to set the `chain` reference:

```conf
eclair.chain = "regtest"
```

You usually also need to disable the feerate mismatch

### Public node

To make your node public, add a public ip:

```conf
eclair.server.public-ips=[x.x.x.x]
```

You'll also have to make sure that the node is accessible from the outside world (port forwarding, firewall,...).
