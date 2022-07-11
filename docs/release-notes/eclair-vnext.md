# Eclair vnext

<insert here a high-level description of the release>

## Major changes

### Add support for channel aliases and zeroconf channels

:information_source: Those features are only supported for channels of type `AnchorOutputsZeroFeeHtlcTx`, which is the
newest channel type and the one enabled by default. If you are opening a channel with a node that doesn't run Eclair,
make sure they support `option_anchors_zero_fee_htlc_tx`.

#### Channel aliases

Channel aliases offer a way to use arbitrary channel identifiers for routing. This feature improves privacy by not
leaking the funding transaction of the channel during payments.

This feature is enabled by default, but your peer has to support it too, and it is not compatible with public channels.

#### Zeroconf channels

Zeroconf channels make it possible to use a newly created channel before the funding tx is confirmed on the blockchain.

:warning: Zeroconf requires the fundee to trust the funder. For this reason it is disabled by default, and you can
only enable it on a peer-by-peer basis.

##### Enabling through features

Below is how to enable zeroconf with a given peer in `eclair.conf`. With this config, your node will _accept_ zeroconf
channels from node `03864e...`.

```eclair.conf
override-init-features = [
  {
    nodeid = "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
    features = {
      // dependencies of zeroconf
      option_static_remotekey = optional
      option_anchors_zero_fee_htlc_tx = optional
      option_scid_alias = optional
      // enable zeroconf
      option_zeroconf = optional
    }
  }
]
```

Note that, as funder, Eclair will happily use an unconfirmed channel if the peer sends an early `channel_ready`, even if
the `option_zeroconf` feature isn't enabled, as long as the peer provides a channel alias.

##### Enabling through channel type

You can enable `option_scid_alias` and `option_zeroconf` features by requesting them in the channel type, even if those
options aren't enabled in your features.

The new channel types variations are:

- `anchor_outputs_zero_fee_htlc_tx+scid_alias`
- `anchor_outputs_zero_fee_htlc_tx+zeroconf`
- `anchor_outputs_zero_fee_htlc_tx+scid_alias+zeroconf`

Examples using the command-line interface:

- open a public zeroconf channel:

```shell
$ ./eclair-cli open --nodeId=03864e... --fundingSatoshis=100000 --channelType=anchor_outputs_zero_fee_htlc_tx+zeroconf --announceChannel=true
```

- open a private zeroconf channel with aliases:

```shell
$ ./eclair-cli open --nodeId=03864e... --fundingSatoshis=100000 --channelType=anchor_outputs_zero_fee_htlc_tx+scid_alias+zeroconf --announceChannel=false
```

### Changes to features override

Eclair supports overriding features on a per-peer basis, using the `eclair.override-init-features` field in `eclair.conf`.
These overrides will now be applied on top of the default features, whereas the previous behavior was to completely ignore default features.
We provide detailed examples in the [documentation](../Configure.md#customize-features).

### Remove support for Tor v2

Dropped support for version 2 of Tor protocol. That means:

- Eclair can't open control connection to Tor daemon version 0.3.3.5 and earlier anymore
- Eclair can't create hidden services for Tor protocol v2 with newer versions of Tor daemon

IMPORTANT: You'll need to upgrade your Tor daemon if for some reason you still use Tor v0.3.3.5 or earlier before
upgrading to this release.

### API changes

- `channelbalances`: retrieves information about the balances of all local channels (#2196)
- `stop`: stops eclair. Please note that the recommended way of stopping eclair is simply to kill its process (#2233)
- `channelbalances` and `usablebalances` return a `shortIds` object instead of a single `shortChannelId` (#2323)
- `onchainunspent`: retrieves the list of UTXOs (#2244)

### Miscellaneous improvements and bug fixes

#### Delay enforcement of new channel fees

When updating the relay fees for a channel, eclair can now continue accepting to relay payments using the old fee even
if they would be rejected with the new fee.
By default, eclair will still accept the old fee for 10 minutes, you can change it by
setting `eclair.relay.fees.enforcement-delay` to a different value.

If you want a specific fee update to ignore this delay, you can update the fee twice to make eclair forget about the
previous fee.

#### New minimum funding setting for private channels

New settings have been added to independently control the minimum funding required to open public and private channels
to your node.

The `eclair.channel.min-funding-satoshis` setting has been deprecated and replaced with the following two new settings
and defaults:

- `eclair.channel.min-public-funding-satoshis = 100000`
- `eclair.channel.min-private-funding-satoshis = 100000`

If your configuration file changes `eclair.channel.min-funding-satoshis` then you should replace it with both of these
new settings.

#### Expired incoming invoices now purged if unpaid

Expired incoming invoices that are unpaid will be searched for and purged from the database when Eclair starts up.
Thereafter searches for expired unpaid invoices to purge will run once every 24 hours. You can disable this feature, or
change the search interval with two new settings:

- `eclair.purge-expired-invoices.enabled = true
- `eclair.purge-expired-invoices.interval = 24 hours`

#### Skip anchor CPFP for empty commitment

When using anchor outputs and a channel force-closes without HTLCs in the commitment transaction, funds cannot be stolen by your counterparty.
In that case eclair can skip spending the anchor output to save on-chain fees, even if the transaction doesn't confirm.
This can be activated by setting the following value in your `eclair.conf`:

```conf
eclair.on-chain-fees.spend-anchor-without-htlcs = false
```

This is disabled by default, because there is still a risk of losing funds until bitcoin adds support for package relay.
If the mempool becomes congested and the feerate is too low, the commitment transaction may never reach miners' mempools because it's below the minimum relay feerate.

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

That should generate `eclair-node/target/eclair-node-<version>-XXXXXXX-bin.zip` with sha256 checksums that match the one
we provide and sign in `SHA256SUMS.asc`

(*) You may be able to build the exact same artefacts with other operating systems or versions of JDK 11, we have not
tried everything.

## Upgrading

This release is fully compatible with previous eclair versions. You don't need to close your channels, just stop eclair,
upgrade and restart.

## Changelog

<fill this section when publishing the release with `git log v0.7.0... --format=oneline --reverse`>
