# Eclair vnext

<insert here a high-level description of the release>

## Major changes

### Taproot Channels (without announcements)

This release adds support for taproot channels, as specified in [the BOLTs](https://github.com/lightning/bolts/pull/995).
Taproot channels improve privacy and cost less on-chain fees by using musig2 for the channel output.
This release is fully compatible with the `lnd` implementation of taproot channels.

We don't support public taproot channels yet, as the gossip mechanism for this isn't finalized yet.
It is thus only possible to open "private" (unannounced) taproot channels.
You may follow progress on the specification for public taproot channels [here](https://github.com/lightning/bolts/pull/1059).

This feature is active by default. To disable it, add the following to your `eclair.conf`:

```conf
eclair.features.option_simple_taproot = disabled
```

To open a taproot channel with a node that supports the `option_simple_taproot` feature, use the following command:

```sh
$ eclair-cli open --nodeId=<node_id> --fundingSatoshis=<funding_amount> --channelType=simple_taproot_channel --announceChannel=false
```

### Remove support for non-anchor channels

We remove the code used to support legacy channels that don't use anchor outputs or taproot.
If you still have such channels, eclair won't start: you will need to close those channels, and will only be able to update eclair once they have been successfully closed.

### Channel jamming accountability

We update our channel jamming mitigation to match the latest draft of the [spec](https://github.com/lightning/bolts/pull/1280).
Note that we use a different architecture for channel bucketing and confidence scoring than what is described in the BOLTs.
We don't yet fail HTLCs that don't meet these restrictions: we're only collecting data so far to evaluate how the algorithm performs.

If you want to disable this feature entirely, you can set the following values in `eclair.conf`:

```conf
eclair.relay.peer-reputation.enabled = false
eclair.relay.reserved-for-accountable = 0.0
```

### Configuration changes

<insert changes>

### API changes

- `findroute`, `findroutetonode` and `findroutebetweennodes` now include a `maxCltvExpiryDelta` parameter (#3234)

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

<fill this section when publishing the release with `git log v0.13.1... --format=oneline --reverse`>
