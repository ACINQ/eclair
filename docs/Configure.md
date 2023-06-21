# Configuring Eclair

---

* [Configuration file](#configuration-file)
  * [Changing the data directory](#changing-the-data-directory)
  * [Splitting the configuration](#splitting-the-configuration)
* [Options reference](#options-reference)
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
 eclair.chain                | Which blockchain to use: *regtest*, *testnet*, *signet* or *mainnet* | mainnet
 eclair.server.port          | Lightning TCP port                                                   | 9735
 eclair.api.port             | API HTTP port                                                        | 8080
 eclair.api.enabled          | Enables the JSON API                                                 | false
 eclair.api.password         | Password protecting the API (BASIC auth)                             | _no default_
 eclair.bitcoind.auth        | Bitcoin Core RPC authentication method: *password* or *safecookie*   | password
 eclair.bitcoind.rpcuser     | Bitcoin Core RPC user                                                | foo
 eclair.bitcoind.rpcpassword | Bitcoin Core RPC password                                            | bar
 eclair.bitcoind.cookie      | Bitcoin Core RPC cookie path                                         | ${user.home}"/.bitcoin/.cookie"
 eclair.bitcoind.zmqblock    | Bitcoin Core ZMQ block address                                       | "tcp://127.0.0.1:29000"
 eclair.bitcoind.zmqtx       | Bitcoin Core ZMQ tx address                                          | "tcp://127.0.0.1:29000"
 eclair.bitcoind.wallet      | Bitcoin Core wallet name                                             | ""
 eclair.server.public-ips    | List of node public ip                                               | _no default_

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

You'll also have to make sure the node is accessible from the outside world (port forwarding, firewall,...).

### Bitcoin Core cookie authentication

If you run Eclair and Bitcoin on the same computer an alternative way to handle the Bitcoin Core RPC authentication.
is to use the safecookie. To use safecookie authentication, you need to remove `rpcpassword=***` and `rpcuser=***` from your `bitcoin.conf` and add the following to `eclair.conf`:

```conf
eclair.bitcoind.auth = "safecookie"
eclair.bitcoind.cookie = "PATH TO THE COOKIE FILE"
```

Setting `eclair.bitcoind.cookie` might not be necessary if Bitcoin is running on mainnet and using the default datadir.

Eclair will need read access to Bitcoin Core's cookie file.
You can either run Eclair and Bitcoin Core with the same user, or grant read permissions to the Eclair user.

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
          percentage = 50
        }

        // alternative routing heuristics (replaces ratios)
        test-failure-cost = ${eclair.router.path-finding.default} {
          use-ratios = false

          locked-funds-risk = 1e-8 // msat per msat locked per block. It should be your expected interest rate per block multiplied by the probability that something goes wrong and your funds stay locked.
          // 1e-8 corresponds to an interest rate of ~5% per year (1e-6 per block) and a probability of 1% that the channel will fail and our funds will be locked.

          // Virtual fee for failed payments
          // Corresponds to how much you are willing to pay to get one less failed payment attempt
          failure-cost {
            fee-base-msat = 2000
            fee-proportional-millionths = 500
          }
          percentage = 10
        }

        // To optimize for fees only:
        test-fees-only = ${eclair.router.path-finding.default} {
          ratios {
            base = 1
            cltv = 0
            channel-age = 0
            channel-capacity = 0
          }
          hop-cost {
            fee-base-msat = 0
            fee-proportional-millionths = 0
          }
          percentage = 10
        }

        // To optimize for shorter paths:
        test-short-paths = ${eclair.router.path-finding.default} {
          ratios {
            base = 1
            cltv = 0
            channel-age = 0
            channel-capacity = 0
          }
          hop-cost {
            // High hop cost penalizes strongly longer paths
            fee-base-msat = 10000
            fee-proportional-millionths = 10000
          }
          percentage = 10
        }

        // To optimize for successful payments:
        test-pay-safe = ${eclair.router.path-finding.default} {
          ratios {
            base = 0
            cltv = 0
            channel-age = 0.5 // Old channels should have less risk of failures
            channel-capacity = 0.5 // High capacity channels are more likely to have enough liquidity to relay our payment
          }
          hop-cost {
            // Less hops means less chances of failures
            fee-base-msat = 1000
            fee-proportional-millionths = 1000
          }
          percentage = 10
        }

        // To optimize for fast payments:
        test-pay-fast = ${eclair.router.path-finding.default} {
          ratios {
            base = 0.2
            cltv = 0.5 // In case of failure we want our funds back as fast as possible
            channel-age = 0.3 // Older channels are more likely to run smoothly
            channel-capacity = 0
          }
          hop-cost {
            // Shorter paths should be faster
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
