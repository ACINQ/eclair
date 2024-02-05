# Eclair vnext

<insert here a high-level description of the release>

## Major changes

### Use priority instead of block target for feerates

Eclair now uses a `slow`/`medium`/`fast` notation for feerates (in the style of mempool.space),
instead of block targets. Only the funding and closing priorities can be configured, the feerate
for commitment transactions is managed by eclair, so is the fee bumping for htlcs in force close
scenarii. Note that even in a force close scenario, when an output is only spendable by eclair, then
the normal closing priority is used.

Default setting is `medium` for both funding and closing. Node operators may configure their values like so:

```eclair.conf
eclair.on-chain-fees.confirmation-priority {
    funding = fast
    closing = slow
}
```

This configuration section replaces the previous `eclair.on-chain-fees.target-blocks` section.

### Add configurable maximum anchor fee

Whenever an anchor outputs channel force-closes, we regularly bump the fees of the commitment transaction to get it to confirm.
We previously ensured that the fees paid couldn't exceed 5% of our channel balance, but that may already be extremely high for large channels.
Without package relay, our anchor transactions may not propagate to miners, and eclair may end up bumping the fees more than the actual feerate, because it cannot know why the transaction isn't confirming.

We introduced a new parameter to control the maximum fee that will be paid during fee-bumping, that node operators may configure:

```eclair.conf
// maximum amount of fees we will pay to bump an anchor output when we have no HTLC at risk
eclair.on-chain-fees.anchor-without-htlcs-max-fee-satoshis = 10000
```

We also limit the feerate used to be at most the same order of magnitude of `fatest` feerate provided by our fee estimator.

### Managing Bitcoin Core wallet keys

You can now use Eclair to manage the private keys for on-chain funds monitored by a Bitcoin Core watch-only wallet.

See `docs/BitcoinCoreKeys.md` for more details.

### Advertise low balance with htlc_maximum_msat

Eclair used to disable a channel when there was no liquidity on our side so that other nodes stop trying to use it.
However, other implementations use disabled channels as a sign that the other peer is offline.
To be consistent with other implementations, we now only disable channels when our peer is offline and signal that a channel has very low balance by setting htlc_maximum_msat to a low value.
The balance thresholds at which to update htlc_maximum_msat are configurable like this:

```eclair.conf
eclair.channel.channel-update {
    balance-thresholds = [{
        available-sat = 1000  // If our balance goes below this,
        max-htlc-sat =  0     // set the maximum HTLC amount to this (or htlc-minimum-msat if it's higher).
    },{
        available-sat = 10000
        max-htlc-sat =  1000
    }]

    min-time-between-updates = 1 hour // minimum time between channel updates because the balance changed
}
```

This feature leaks a bit of information about the balance when the channel is almost empty, if you do not wish to use it, set `eclair.channel.channel-update.balance-thresholds = []`.

### API changes

- `bumpforceclose` can be used to make a force-close confirm faster, by spending the anchor output (#2743)
- `open` now takes an optional parameter `--fundingFeeBudgetSatoshis` to define the maximum acceptable value for the mining fee of the funding transaction. This mining fee can sometimes be unexpectedly high depending on available UTXOs in the wallet. Default value is 0.1% of the funding amount (#2808)
- `rbfopen` now takes a mandatory parameter `--fundingFeeBudgetSatoshis`, with the same semantics as for `open` (#2808)

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
- AdoptOpenJDK 11.0.6
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

<fill this section when publishing the release with `git log v0.9.0... --format=oneline --reverse`>
