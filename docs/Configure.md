# Configuring Eclair

---

* [Configuration file](#configuration-file)
  * [Changing the data directory](#changing-the-data-directory)
  * [Splitting the configuration](#splitting-the-configuration)
* [Options reference](#options-reference)
  * [Network identity](#network-identity)
  * [XBT Knots compatibility](#xbt-knots-compatibility)
* [Customize features](#customize-features)
* [Customize feerate tolerance](#customize-feerate-tolerance)
* [Examples](#examples)
  * [Basic configuration](#basic-configuration)
  * [Regtest mode](#regtest-mode)
  * [Public node](#public-node)
  * [AB-testing for path-finding](#ab-testing-for-path-finding)

---

## Configuration file

The configuration file for eclair is named `eclair.conf`. It is located in the data directory, which is `~/.eclair` by
default (on Windows it is `C:\Users\YOUR_NAME\.eclair`). Note that eclair won't create a configuration file by itself: if you want to change eclair's configuration, you need to **actually create the configuration file first**. The encoding must be UTF-8.

```sh
# this is the default data directory, it will be created at eclair first startup
mkdir ~/.eclair 
vi ~/.eclair/eclair.conf
```

Options are set as key-value pairs and follow the [HOCON syntax](https://github.com/lightbend/config/blob/master/HOCON.md).
Values do not need to be surrounded by quotes, except if they contain special characters.

### Changing the data directory

You can change the data directory with the `eclair.datadir` parameter:

```sh
eclair-node.sh -Declair.datadir="/path/to/custom/eclair/data/folder"
```

### Splitting the configuration

Note that HOCON allows you to have files include other files. This allows you to split your configuration file into
several logical files, for easier management. For example, you could define a file `routing.conf` file with parameters
related to routing configuration, and include it from `eclair.conf`.

## Options reference

Here are some of the most common options:

name                         | description                                                          | default value
-----------------------------|----------------------------------------------------------------------|--------------
 eclair.chain                | Which blockchain to use: *regtest*, *testnet4* or *mainnet*          | mainnet
 eclair.server.port          | Lightning TCP port                                                   | 9735
 eclair.api.port             | API HTTP port                                                        | 8080
 eclair.api.enabled          | Enables the JSON API                                                 | false
 eclair.api.password         | Password protecting the API (BASIC auth)                             | _no default_
 eclair.bitcoind.auth        | XBT Knots RPC authentication method: *password* or *safecookie*      | password
 eclair.bitcoind.rpcuser     | XBT Knots RPC user                                                   | foo
 eclair.bitcoind.rpcpassword | XBT Knots RPC password                                               | bar
 eclair.bitcoind.cookie      | XBT Knots RPC cookie path                                            | ${user.home}"/.bitcoin/.cookie"
 eclair.bitcoind.zmqblock    | XBT Knots ZMQ block address                                          | "tcp://127.0.0.1:29000"
 eclair.bitcoind.zmqtx       | XBT Knots ZMQ transaction address                                    | "tcp://127.0.0.1:29000"
 eclair.bitcoind.wallet      | XBT Knots wallet name                                                | ""
 eclair.server.public-ips    | List of node public ip                                               | _no default_

&rarr; see [`reference.conf`](../eclair-core/src/main/resources/reference.conf) for the full reference.

### Network identity

Eclair keeps the XBT chain and Lightning network identities separate through a
network profile:

* `bitcoinGenesisHash` identifies the XBT chain genesis hash returned by the
  selected XBT Knots node's `getblockhash 0`; Eclair uses it for node validation
  and address decoding.
* `lightningChainHash` identifies the Lightning network used in protocol
  messages, channel state, gossip, invoices, and offers.

The current profiles are `mainnet`, `testnet4`, and `regtest`. The legacy
configuration value `testnet` is accepted as an alias for `testnet4`. XBT
currently uses the same genesis hash for both identities, but the separate
profile fields make the distinction explicit for future network extensions.

### XBT Knots compatibility

At startup, Eclair requires an XBT-compatible Bitcoin Knots node and selects the
XBT consensus profile for `eclair.chain`. It validates the reported chain and
genesis hash, the Knots marker in `getnetworkinfo`, and the BLAKE2b and
reduced-data deployment schedule reported by `getdeploymentinfo`. A standard
Bitcoin Core node is rejected, even when it reports the same chain and genesis
hash.

Eclair also invokes these required startup RPC methods:

* `getblockchaininfo`
* `getdeploymentinfo`
* `getblockhash`
* `getnetworkinfo`
* `getrawtransaction`
* `listwallets`
* `listunspent`

Compatibility is determined by the selected XBT profile, reported consensus
schedule, genesis identity, Knots software marker, and required RPC behavior.
Regtest keeps the
BLAKE2b fork and reduced-data deployment unscheduled by default, matching Knots.
The current `getdeploymentinfo` RPC does not expose the BLAKE2b target shift, so
Eclair uses the target shift pinned in its selected XBT profile and cannot
compare that value with the running node through startup RPC.

## Customize features

Eclair ships with a set of features that are activated by default, and some experimental or optional features that can be activated by users.
The list of supported features can be found in the [reference configuration](../eclair-core/src/main/resources/reference.conf).

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
    option_support_large_channel = disabled
}
```

It's usually risky to activate non-default features or disable default features: make sure you fully understand a feature (and the current implementation status, detailed in the release notes) before doing so.

Eclair supports per-peer features. Suppose you are connected to Alice and Bob, you can use a different set of features with Alice than the one you use with Bob.
When experimenting with non-default features, we recommend using this to scope the peers you want to experiment with.

This is done with the `override-init-features` configuration parameter in your `eclair.conf`:

```conf
eclair.features {
  var_onion_optin = mandatory
  payment_secret = mandatory
  option_support_large_channel = optional
  option_static_remotekey = optional
  option_channel_type = optional
}
eclair.override-init-features = [
    {
        nodeId = "<alice_node_id>"
        features {
            option_support_large_channel = disabled
            option_static_remotekey = mandatory
            option_anchors_zero_fee_htlc_tx = optional
        }
    },
    {
        nodeId = "<bob_node_id>"
        features {
            option_support_large_channel = mandatory
            option_static_remotekey = disabled
            option_channel_type = mandatory
        }
    },
]
```

In this example, the features that will be activated with Alice are:

```conf
var_onion_optin = mandatory
payment_secret = mandatory
option_static_remotekey = mandatory
option_anchors_zero_fee_htlc_tx = optional
option_channel_type = optional
```

The features that will be activated with Bob are:

```conf
var_onion_optin = mandatory
payment_secret = mandatory
option_support_large_channel = mandatory
option_channel_type = mandatory
```

## Customize feerate tolerance

To protect your channel funds, Eclair keeps an up-to-date estimate of on-chain fees based on the configured XBT Knots node or fee provider.
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
However, if you have a trust relationship with some specific peers, and you know they will never try to cheat you, you can increase the tolerance specifically for those peers.
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

To run with XBT Knots' `regtest` mode, set the `chain` option:

```conf
eclair.chain = "regtest"
```

You usually also need to disable the feerate mismatch

### Public node

To make your node public, add a public ip:

```conf
eclair.server.public-ips=[x.x.x.x]
```

You'll also have to make sure the node is accessible from the outside world (port forwarding, firewall,...).

### XBT Knots cookie authentication

If Eclair and XBT Knots run on the same computer, you can use cookie authentication. Remove `rpcpassword=***` and `rpcuser=***` from XBT Knots' `bitcoin.conf` and add the following to `eclair.conf`:

```conf
eclair.bitcoind.auth = "safecookie"
eclair.bitcoind.cookie = "PATH TO THE COOKIE FILE"
```

Setting `eclair.bitcoind.cookie` may not be necessary when XBT Knots uses its default datadir.

Eclair needs read access to XBT Knots' cookie file. Run both processes as the same user or grant the Eclair user read access.

### AB-testing for path-finding

The following configuration enables AB-testing by defining a set of `experiments`, and assigning a percentage of the
traffic to each experiment. The `control` experiment doesn't override any parameter, it uses the defaults.

Note that the percentages of all experiments sum to 100 %.

```conf
eclair {
  router {
    path-finding {
      experiments {
        control = ${eclair.router.path-finding.default} {
          percentage = 70
        }

        // To optimize for fees only:
        test-fees-only = ${eclair.router.path-finding.default} {
          // By setting everything to zero, only fees will be taken into account.
          locked-funds-risk = 0
          failure-cost {
            fee-base-msat = 0
            fee-proportional-millionths = 0
          }
          hop-cost {
            fee-base-msat = 0
            fee-proportional-millionths = 0
          }
          percentage = 10
        }

        // To optimize for shorter paths:
        test-short-paths = ${eclair.router.path-finding.default} {
          hop-cost {
            // High hop cost penalizes strongly longer paths
            fee-base-msat = 10000
            fee-proportional-millionths = 10000
          }
          percentage = 10
        }

        // To optimize for successful payments:
        test-pay-safe = ${eclair.router.path-finding.default} {
          failure-cost {
            // High failure cost will penalize paths that are less likely to succeed.
            fee-base-msat = 10000
            fee-proportional-millionths = 10000
          }
          percentage = 10
        }
      }
    }
  }
}
```
