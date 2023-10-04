# Eclair vnext

<insert here a high-level description of the release>

## Major changes

### Liquidity Ads

This release includes an early prototype for [liquidity ads](https://github.com/lightning/bolts/pull/1153).
Liquidity ads allow nodes to sell their liquidity in a trustless and decentralized manner.
Every node advertizes the rates at which they sell their liquidity, and buyers connect to sellers that offer interesting rates.

The liquidity ads specification is still under review and will likely change.
This feature isn't meant to be used on mainnet yet and is thus disabled by default.

### Simplified mutual close

This release includes support for the latest [mutual close protocol](https://github.com/lightning/bolts/pull/1096).
This protocol allows both channel participants to decide exactly how much fees they're willing to pay to close the channel.
Each participant obtains a channel closing transaction where they are paying the fees.

Once closing transactions are broadcast, they can be RBF-ed by calling the `close` RPC again with a higher feerate:

```sh
./eclair-cli close --channelId=<channel_id> --preferredFeerateSatByte=<rbf_feerate>
```

### Update minimal version of Bitcoin Core

With this release, eclair requires using Bitcoin Core 27.1.
Newer versions of Bitcoin Core may be used, but have not been extensively tested.

This version introduces a new coin selection algorithm called  [CoinGrinder](https://github.com/bitcoin/bitcoin/blob/master/doc/release-notes/release-notes-27.0.md#wallet) that will reduce on-chain transaction costs when feerates are high.

To enable CoinGrinder at all fee rates and prevent the automatic consolidation of UTXOs, add the following line to your `bitcoin.conf` file:

```conf
consolidatefeerate=0
```

### Incoming obsolete channels will be rejected

Eclair will not allow remote peers to open new `static_remote_key` channels. These channels are obsolete, node operators should use `option_anchors` channels now.
Existing `static_remote_key` channels will continue to work. You can override this behaviour by setting `eclair.channel.accept-incoming-static-remote-key-channels` to true.

Eclair will not allow remote peers to open new obsolete channels that do not support `option_static_remotekey`.

### API changes

- `channelstats` now takes optional parameters `--count` and `--skip` to control pagination. By default, it will return first 10 entries. (#2890)
- `createinvoice` now takes an optional `--privateChannelIds` parameter that can be used to add routing hints through private channels. (#2909)
- `nodes` allows filtering nodes that offer liquidity ads (#2848)

### Miscellaneous improvements and bug fixes

<insert changes>

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

- Ubuntu 22.04
- AdoptOpenJDK 11.0.22
- Maven 3.9.2

Use the following command to generate the eclair-node package:

```sh
mvn clean install -DskipTests
```

That should generate `eclair-node/target/eclair-node-<version>-XXXXXXX-bin.zip` with sha256 checksums that match the one we provide and sign in `SHA256SUMS.asc`

(*) You may be able to build the exact same artefacts with other operating systems or versions of JDK 11, we have not tried everything.

## Upgrading

This release is fully compatible with previous eclair versions. You don't need to close your channels, just stop eclair, upgrade and restart.

## Changelog

<fill this section when publishing the release with `git log v0.10.0... --format=oneline --reverse`>
