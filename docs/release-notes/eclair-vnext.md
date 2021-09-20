# Eclair vnext

<insert here a high-level description of the release>

## Major changes

### ZMQ changes

Eclair previously used ZMQ to receive full blocks from Bitcoin Core.
In this release, we instead switch to receive only block hashes over ZMQ.
This will save bandwidth and improve support for deployments with a remote Bitcoin Core node.

:warning: When updating eclair, you need to update your `bitcoin.conf` to have your Bitcoin Core node send block hashes via ZMQ.

The previous configuration was:

```conf
zmqpubrawblock=tcp://127.0.0.1:29000
```

You must remove that line from your `bitcoin.conf` and replace it with:

```conf
zmqpubhashblock=tcp://127.0.0.1:29000
```

### Per node relay fees

Relay fees are now set per node instead of per channel:

- If you set the relay fees for a node with the `updaterelayfee` API, all new channels with this node will use these fees.
- Otherwise the default relay fees set in `eclair.conf` will be used: this means that changing `eclair.conf` will update the fees for all channels where the fee was not manually set.

Note that you can use the `updaterelayfee` API *before* opening a channel to ensure that the channel doesn't use the default relay fees from `eclair.conf`.

:warning: When updating eclair, the relay fees for your existing channels will be reset to the value from your `eclair.conf`. You should use the `updaterelayfee` API to reconfigure relay fees if you don't want to use the default fees for every node you're connected to.

### Beta support for anchor outputs

Anchor outputs is still disabled by default, but users willing to try it can activate it by adding the following line to `eclair.conf`:

```conf
eclair.features.option_anchors_zero_fee_htlc_tx = optional
```

Once activated, eclair will keep the commitment feerate below 10 sat/byte regardless of the current on-chain feerate and will not close channels when there is a feerate mismatch between you and your peer.

You can modify that threshold by setting `eclair.on-chain-fees.feerate-tolerance.anchor-output-max-commit-feerate` in your `eclair.conf`.
Head over to [reference.conf](https://github.com/ACINQ/eclair/blob/master/eclair-core/src/main/resources/reference.conf) for more details.

In case the channel is unilaterally closed, eclair will use CPFP and RBF to ensure that transactions confirm in a timely manner.
You **MUST** ensure you have some utxos available in your Bitcoin Core wallet for fee bumping, otherwise there is a risk that an attacker steals some of your funds.

Do note that anchor outputs may still be unsafe in high-fee environments until the Bitcoin network provides support for [package relay](https://bitcoinops.org/en/topics/package-relay/).

### Path-finding improvements

This release contains many improvements to path-finding and paves the way for future experimentation.

A noteworthy addition is a new heuristic that can be used to penalize long paths by setting a virtual cost per additional hop in the route (#1815). This can be freely configured by node operators by setting fields in the `eclair.router.path-finding.default.hop-cost` section.

We also added support for A/B testing to experiment with various configurations of the available heuristics.
A/B testing can be activated directly from `eclair.conf`, by configuring some `experiments`, for example:

```conf
eclair.router.path-finding.experiments {
  control = ${eclair.router.path-finding.default} {
    percentage = 75 // 75% of the traffic will use the default configuration
  }

  use-shorter-paths = ${eclair.router.path-finding.default} {
    percentage = 25 // 25% of the traffic will use this custom configuration
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
  }
}
```

Have a look at [reference.conf](https://github.com/ACINQ/eclair/blob/master/eclair-core/src/main/resources/reference.conf) for more examples.

You can also force a specific payment to use an experimental path-finding configuration by specifying the experiment name in the various path-finding APIs:

```sh
eclair-cli payinvoice --invoice=<xxx> --pathFindingExperimentName=use-shorter-paths
```

The results are stored in the `audit` database, inside the `path_finding_metrics` table.
You can then analyze the results after sending a large enough number of payments to decide what configuration yields the best results for your usage of lightning.

### Tor support for blockchain watchdogs

Eclair introduced blockchain watchdogs in v0.5.0, where secondary blockchain sources are regularly queried to detect whether your node is being eclipsed.

Most of these watchdogs were previously queried over HTTPS, which exposes your IP address.
This is fixed in this release: when using Tor, the watchdogs will now also be queried through Tor, keeping your IP address private.

You can also now choose to disable some watchdogs by removing them from the `eclair.blockchain-watchdog.sources` list in `eclair.conf`.
Head over to [reference.conf](https://github.com/ACINQ/eclair/blob/master/eclair-core/src/main/resources/reference.conf) for more details.

### Sample GUI removed

We previously included code for a sample GUI: `eclair-node-gui`.
This GUI was only meant to be used for demo purposes, not for mainnet node administration.

However some users were using it on mainnet, which lead to several issues (e.g. channel closure and potentially loss of funds).
We completely removed it from this release to prevent it from happening again.

### API changes

This release contains many API updates:

- `open` lets you specify the channel type through the `--channelType` parameter, which can be one of `standard`, `static_remotekey`, `anchor_outputs` or `anchor_outputs_zero_fee_htlc_tx` (#1867)
- `open` doesn't support the `--feeBaseMsat` and `--feeProportionalMillionths` parameters anymore: you should instead set these with the `updaterelayfee` API, which can now be called before opening a channel (#1890)
- `updaterelayfee` must now be called with nodeIds instead of channelIds and will update the fees for all channels with the given node(s) at once (#1890)
- `close` lets you specify a fee range when using quick close through the `--preferredFeerateSatByte`, `--minFeerateSatByte` and `--maxFeerateSatByte` (#1768)
- `createinvoice` now lets you provide a `--descriptionHash` instead of a `--description` (#1919)
- `sendtonode` doesn't support providing a `paymentHash` anymore since it uses `keysend` to send the payment (#1840)
- `payinvoice`, `sendtonode`, `findroute`, `findroutetonode` and `findroutebetweennodes` let you specify `--pathFindingExperimentName` when using path-finding A/B testing (#1930)
- the `--maxFeePct` parameter used in `payinvoice` and `sendtonode` must now be an integer between 0 and 100: it was previously a value between 0 and 1, which was misleading for a percentage (#1930)
- `findroute`, `findroutetonode` and `findroutebetweennodes` let you choose the format of the route returned with the `--routeFormat` parameter (supported values are `nodeId` and `shortChannelId`) (#1943)
- `findroute`, `findroutetonode` and `findroutebetweennodes` now accept `--includeLocalChannelCost` to specify if you want to count the fees from your node like trampoline payments do (#1942) 

Have a look at our [API documentation](https://acinq.github.io/eclair) for more details.

### Miscellaneous improvements and bug fixes

- Eclair nodes may now use different relay fees for unannounced channels (#1893)
- Relay fees are now set per node and automatically apply to all channels with that node (#1890)
- Eclair now supports [explicit channel type negotiation](https://github.com/lightningnetwork/lightning-rfc/pull/880)
- Eclair now supports [quick close](https://github.com/lightningnetwork/lightning-rfc/pull/847), which provides more control over what feerate will be used when closing channels

## Verifying signatures

You will need `gpg` and our release signing key 7A73FE77DE2C4027. Note that you can get it:

- from our website: https://acinq.co/pgp/drouinf.asc
- from github user @sstone, a committer on eclair: https://api.github.com/users/sstone/gpg_keys

To import our signing key:

```sh
$ gpg --import drouinf.asc
```

To verify the release file checksums and signatures:

```sh
$ gpg -d SHA256SUMS.asc > SHA256SUMS.stripped
$ sha256sum -c SHA256SUMS.stripped
```

## Building

Eclair builds are deterministic. To reproduce our builds, please use the following environment (*):

- Ubuntu 20.04
- AdoptOpenJDK 11.0.6
- Maven 3.8.1

Use the following command to generate the eclair-node package:

```sh
mvn clean install -DskipTests
```

That should generate `eclair-node/target/eclair-node-<version>-XXXXXXX-bin.zip` with sha256 checksums that match the one we provide and sign in `SHA256SUMS.asc`

(*) You may be able to build the exact same artefacts with other operating systems or versions of JDK 11, we have not tried everything.

## Upgrading

This release is fully compatible with previous eclair versions. You don't need to close your channels, just stop eclair, upgrade and restart.

## Changelog

<fill this section when publishing the release with `git log v0.6.1... --format=oneline --reverse`>
