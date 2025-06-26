# Eclair vnext

<insert here a high-level description of the release>

## Major changes

<insert changes>

### Package relay

With Bitcoin Core 28.1, eclair starts relying on the `submitpackage` RPC during channel force-close.
When using anchor outputs, allows propagating our local commitment transaction to peers who are also running Bitcoin Core 28.x or newer, even if the commitment feerate is low (package relay).

This removes the need for increasing the commitment feerate based on mempool conditions, which ensures that channels won't be force-closed anymore when nodes disagree on the current feerate.

### Attribution data

Eclair now supports attributable failures which allow nodes to prove they are not the source of the failure.
Previously a failing node could choose not to report the failure and we would penalize all nodes of the route.
If all nodes of the route support attributable failures, we only need to penalize two nodes (there is still some uncertainty as to which of the two nodes is the failing one).
See https://github.com/lightning/bolts/pull/1044 for more details.

Attribution data also provides hold times from payment relayers, both for fulfilled and failed HTLCs.

Support is enabled by default.
It can be disabled by setting `eclair.features.option_attribution_data = disabled`.

### Local reputation and HTLC endorsement

To protect against jamming attacks, eclair gives a reputation to its neighbors and uses it to decide if a HTLC should be relayed given how congested the outgoing channel is.
The reputation is basically how much this node paid us in fees divided by how much they should have paid us for the liquidity and slots that they blocked.
The reputation is per incoming node and endorsement level.
The confidence that the HTLC will be fulfilled is transmitted to the next node using the endorsement TLV of the `update_add_htlc` message.
Note that HTLCs that are considered dangerous are still relayed: this is the first phase of a network-wide experimentation aimed at collecting data.

To configure, edit `eclair.conf`:

```eclair.conf
// We assign reputations to our peers to prioritize payments during congestion.
// The reputation is computed as fees paid divided by what should have been paid if all payments were successful.
eclair.relay.peer-reputation {
    // Set this parameter to false to disable the reputation algorithm and simply relay the incoming endorsement
    // value, as described by https://github.com/lightning/blips/blob/master/blip-0004.md,
    enabled = true
    // Reputation decays with the following half life to emphasize recent behavior.
    half-life = 15 days
    // Payments that stay pending for longer than this get penalized
    max-relay-duration = 12 seconds
    // Pending payments are counted as failed, and because they could potentially stay pending for a very long time,
    // the following multiplier is applied.
    pending-multiplier = 200 // A pending payment counts as a thousand failed ones.
}
```

### API changes

- `listoffers` now returns more details about each offer.
- `parseoffer` is added to display offer fields in a human-readable format. 


### Configuration changes

- The default for `eclair.features.option_channel_type` is now  `mandatory` instead of `optional`. This change prepares nodes to always assume the behavior of `option_channel_type` from peers when Bolts PR [#1232](https://github.com/lightning/bolts/pull/1232) is adopted. Until [#1232](https://github.com/lightning/bolts/pull/1232) is adopted you can still set `option_channel_type` to `optional` in your `eclair.conf` file for specific peers that do not yet support this option, see `Configure.md` for more information.

- We added a configuration parameter to facilitate custom signet use. The parameter `eclair.bitcoind.signet-check-tx` should be set to the txid of a transaction that exists in your signet or set to "" to skip this check. See issue [#3079](https://github.com/ACINQ/eclair/issues/3078) for details.

### Miscellaneous improvements and bug fixes

#### Add `max-closing-feerate` configuration parameter

We added a new configuration value to `eclair.conf` to limit the feerate used for force-close transactions where funds aren't at risk: `eclair.on-chain-fees.max-closing-feerate`.
This ensures that you won't end up paying a lot of fees during mempool congestion: your node will wait for the feerate to decrease to get your non-urgent transactions confirmed.
If you need those transactions to confirm because you are low on liquidity, you should update `eclair.on-chain-fees.max-closing-feerate` and restart your node: `eclair` will automatically RBF all available transactions.

#### Remove confirmation scaling based on funding amount

We previously scaled the number of confirmations based on the channel funding amount.
However, this doesn't work with splicing, where the channel capacity may change drastically.
It's much simpler to always use the same number of confirmations, while choosing a value that is large enough to protect against malicious reorgs.
We now by default use 8 confirmations, which can be modified in `eclair.conf`:

```conf
// Minimum number of confirmations for channel transactions to be safe from reorgs.
eclair.channel.min-depth-blocks = 8
```

Note however that we require `min-depth` to be at least 6 blocks, since the BOLTs require this before announcing channels.
See #3044 for more details.

## Verifying signatures

You will need `gpg` and our release signing key E04E48E72C205463. Note that you can get it:

- from our website: https://acinq.co/pgp/drouinf2.asc
- from github user @sstone, a committer on eclair: https://api.github.com/users/sstone/gpg_keys

To import our signing key:

```sh
$ gpg --import drouinf2.asc
```

To verify the release file checksums and signatures:

```sh
$ gpg -d SHA256SUMS.asc > SHA256SUMS.stripped
$ sha256sum -c SHA256SUMS.stripped
```

## Building

Eclair builds are deterministic. To reproduce our builds, please use the following environment (*):

- Ubuntu 24.04.1
- Adoptium OpenJDK 21.0.6

Use the following command to generate the eclair-node package:

```sh
./mvnw clean install -DskipTests
```

That should generate `eclair-node/target/eclair-node-<version>-XXXXXXX-bin.zip` with sha256 checksums that match the one we provide and sign in `SHA256SUMS.asc`

(*) You may be able to build the exact same artefacts with other operating systems or versions of JDK 21, we have not tried everything.

## Upgrading

This release is fully compatible with previous eclair versions. You don't need to close your channels, just stop eclair, upgrade and restart.

## Changelog

<fill this section when publishing the release with `git log v0.12.0... --format=oneline --reverse`>