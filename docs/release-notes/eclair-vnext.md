# Eclair vnext

<insert here a high-level description of the release>

## Major changes

### Channel Splicing

With this release, we add support for the final version of [splicing](https://github.com/lightning/bolts/pull/1160) that was recently added to the BOLTs.
Splicing allows node operators to change the size of their existing channels, which makes it easier and more efficient to allocate liquidity where it is most needed.
Most node operators can now have a single channel with each of their peer, which costs less on-chain fees and resources, and makes path-finding easier.

The size of an existing channel can be increased with the `splicein` API:

```sh
eclair-cli splicein --channelId=<channel_id> --amountIn=<amount_satoshis>
```

Once that transaction confirms, the additional liquidity can be used to send outgoing payments.
If the transaction doesn't confirm, the node operator can speed up confirmation with the `rbfsplice` API:

```sh
eclair-cli rbfsplice --channelId=<channel_id> --targetFeerateSatByte=<feerate_satoshis_per_byte> --fundingFeeBudgetSatoshis=<maximum_on_chain_fee_satoshis>
```

If the node operator wants to reduce the size of a channel, or send some of the channel funds to an on-chain address, they can use the `spliceout` API:

```sh
eclair-cli spliceout --channelId=<channel_id> --amountOut=<amount_satoshis> --scriptPubKey=<on_chain_address>
```

That operation can also be RBF-ed with the `rbfsplice` API to speed up confirmation if necessary.

Note that when 0-conf is used for the channel, it is not possible to RBF splice transactions.
Node operators should instead create a new splice transaction (with `splicein` or `spliceout`) to CPFP the previous transaction.

Note that eclair had already introduced support for a splicing prototype in v0.9.0, which helped improve the BOLT proposal.
We're removing support for the previous splicing prototype feature: users that depended on this protocol must upgrade to create official splice transactions.

### New MPP splitting strategy

Eclair can send large payments using multiple low-capacity routes by sending as much as it can through each route (if `randomize-route-selection = false`) or some random fraction (if `randomize-route-selection = true`).
These splitting strategies are now specified using `mpp.splitting-strategy = "full-capacity"` or `mpp.splitting-strategy = "randomize"`.
In addition, a new strategy is available: `mpp.splitting-strategy = "max-expected-amount"` will send through each route the amount that maximizes the expected delivered amount (amount sent multiplied by the success probability).

Eclair's path-finding algorithm can be customized by modifying the `eclair.router.path-finding.experiments.*` sections of your `eclair.conf`.
The new `mpp.splitting-strategy` goes in these sections, or in `eclair.router.path-finding.default` from which they inherit.

### Remove support for legacy channel codecs

We remove the code used to deserialize channel data from versions of eclair prior to v0.13.
Node operators running a version of `eclair` older than v0.13 must first upgrade to v0.13 to migrate their channel data, and then upgrade to the latest version.

### Move closed channels to dedicated database table

We previously kept closed channels in the same database table as active channels, with a flag indicating that it was closed.
This creates performance issues for nodes with a large history of channels, and creates backwards-compatibility issues when changing the channel data format.

We now store closed channels in a dedicated table, where we only keep relevant information regarding the channel.
When restarting your node, the channels table will automatically be cleaned up and closed channels will move to the new table.
This may take some time depending on your channels history, but will only happen once.

### Update minimal version of Bitcoin Core

With this release, eclair requires using Bitcoin Core 29.1.
Newer versions of Bitcoin Core may be used, but have not been extensively tested.

### Configuration changes

<insert changes>

### API changes

- the `closedchannels` API now returns human-readable channel data

### Miscellaneous improvements and bug fixes

<insert changes>

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

Then use the following command to generate the eclair-node packages:

```sh
./mvnw clean install -DskipTests
```

That should generate `eclair-node/target/eclair-node-<version>-XXXXXXX-bin.zip` with sha256 checksums that match the one we provide and sign in `SHA256SUMS.asc`

(*) You may be able to build the exact same artefacts with other operating systems or versions of JDK 21, we have not tried everything.

## Upgrading

This release is fully compatible with previous eclair versions. You don't need to close your channels, just stop eclair, upgrade and restart.

## Changelog

<fill this section when publishing the release with `git log v0.13.0... --format=oneline --reverse`>
