# Eclair v0.10.0

This release adds official support for the dual-funding feature, an up-to-date implementation of Bolt 12 offers, and a fully working splicing prototype.
We're waiting for the specification work to be finalized for some of those features, and other implementations to be ready for cross-compatibility tests.
You should only activate them if you know what you're doing, and are ready to handle backwards-incompatible changes!

This release also contains various on-chain fee improvements, more configuration options, performance enhancements and various minor bug fixes.

## Major changes

### Dual funding

After many years of work and refining the protocol, [dual funding](https://github.com/lightning/bolts/pull/851) has been added to the BOLTs.

This release of eclair activates dual funding, and it will automatically be used with [cln](https://github.com/ElementsProject/lightning/) nodes. When opening channels to nodes that don't support dual funding, the older funding protocol will be used automatically.

One of the immediate benefits of dual funding is that the funding transaction can now be RBF-ed, using the `rbfopen` RPC.

There is currently no way to automatically add funds to channels that are being opened to your node, as deciding whether to do so or not really depends on each node operator's peering strategy.
We have however created a [sample plugin](https://github.com/ACINQ/eclair-plugins/tree/master/channel-funding) that node operators can fork to implement their own strategy for contributing to inbound channels.

### Update minimal version of Bitcoin Core

With this release, eclair requires using Bitcoin Core 24.1.
Newer versions of Bitcoin Core may be used, but haven't been extensively tested.

### Bolt12

Eclair supports the latest state of the [Bolt 12 specification](https://github.com/lightning/bolts/pull/798).
The specification may still change at that point, so support is experimental, but you may try it using:

- the `payoffer` RPC to pay a Bolt 12 offer
- the [tip jar plugin](https://github.com/ACINQ/eclair-plugins/tree/master/bolt12-tip-jar) to generate Bolt 12 offers

This may also be compatible with other implementations, but there's no guarantee at that point.

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

We also limit the feerate used to be at most of the same order of magnitude as the `fastest` feerate provided by our fee estimator.

### Managing Bitcoin Core wallet keys

You can now use Eclair to manage the private keys for on-chain funds monitored by a Bitcoin Core watch-only wallet.

See `docs/ManagingBitcoinCoreKeys.md` for more details.

### Advertise low balance with `htlc_maximum_msat`

Eclair used to disable a channel when there was no liquidity on our side so that other nodes stop trying to use it.
However, other implementations use disabled channels as a sign that the other peer is offline.
To be consistent with other implementations, we now only disable channels when our peer is offline and signal that a channel has very low balance by setting `htlc_maximum_msat` to a low value.
The balance thresholds at which to update `htlc_maximum_msat` are configurable like this:

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

#### Use bitcoinheaders.net v2

Eclair uses <https://bitcoinheaders.net/> as one of its sources of blockchain data to detect when our node is being eclipsed.
The format of this service is changing, and the older format will be deprecated soon.
We thus encourage eclair nodes to update to ensure that they still have access to this blockchain watchdog.

#### Force-closing anchor channels fee management

Various improvements have been made to force-closing channels that need fees to be attached using CPFP or RBF.
Those changes ensure that eclair nodes don't end up paying unnecessarily high fees to force-close channels, even when the mempool is full.

#### Improve DB usage when closing channels

When channels that have relayed a lot of HTLCs are closed, we can forget the revocation data for all of those HTLCs and free up space in our DB. We previously did that synchronously, which meant deleting potentially millions of rows synchronously. This isn't a high priority task, so we're now asynchronously deleting that data in smaller batches.

Node operators can control the rate at which that data is deleted by updating the following values in `eclair.conf`:

```conf
// During normal channel operation, we need to store information about past HTLCs to be able to punish our peer if
// they publish a revoked commitment. Once a channel closes or a splice transaction confirms, we can clean up past
// data (which reduces the size of our DB). Since there may be millions of rows to delete and we don't want to slow
// down the node, we delete those rows in batches at regular intervals.
eclair.db.revoked-htlc-info-cleaner {
  // Number of rows to delete per batch: a higher value will clean up the DB faster, but may have a higher impact on performance.
  batch-size = 50000
  // Frequency at which batches of rows are deleted: a lower value will clean up the DB faster, but may have a higher impact on performance.
  interval = 15 minutes
}
```

See <https://github.com/ACINQ/eclair/pull/2705> for more details.

#### Correctly unlock wallet inputs during transaction eviction

When the mempool is full and transactions are evicted, and potentially double-spent, the Bitcoin Core wallet cannot always safely unlock inputs.

Eclair is now automatically detecting such cases and telling Bitcoin Core to unlock inputs that are safe to use. This ensures that node operators don't end up with unavailable liquidity.

See <https://github.com/ACINQ/eclair/pull/2817> and <https://github.com/ACINQ/eclair/pull/2818> for more details.

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

- [210b919](https://github.com/ACINQ/eclair/commit/210b9198b3269194f2d79c2896c6de4d7f6660a0) Back to dev (#2694)
- [194f5dd](https://github.com/ACINQ/eclair/commit/194f5dd2b89bd78fcb319cd37e7220ea4059068f) Find route for messages (#2656)
- [da98e19](https://github.com/ACINQ/eclair/commit/da98e195402b6b52dae702442b984604d21cd06d) Simplify on-chain fee management  (#2696)
- [1519dd0](https://github.com/ACINQ/eclair/commit/1519dd07a4fda2000a861891e03c00e44023499b) Log requests for unknown channels as debug (#2700)
- [9db0063](https://github.com/ACINQ/eclair/commit/9db00630791fa5255ef4a0a7d2f7a478580e3921) Record begin and end timestamps for relays (#2701)
- [4c98e1c](https://github.com/ACINQ/eclair/commit/4c98e1c23712fdd0f90ee851f50758ea0c5a7638) Correctly fail blinded payments after restart (#2704)
- [4216431](https://github.com/ACINQ/eclair/commit/42164319a3f2cda8ef527d781b0fb3ae09048874) Use apache archives for mvn in Docker build (#2706)
- [abf1dd3](https://github.com/ACINQ/eclair/commit/abf1dd3747d1e53e8d0a719ed18f0da5c2f769f8) Fix AuditDb test (#2707)
- [cf46b64](https://github.com/ACINQ/eclair/commit/cf46b649053a2e48b61366bab01d1a2640243fdc) Ignore `commit_sig` for aborted splice (#2709)
- [3e43611](https://github.com/ACINQ/eclair/commit/3e436114a451495aab7060f970834f29d199502c) Update dependencies (#2710)
- [47e0b83](https://github.com/ACINQ/eclair/commit/47e0b834383ad7dcdb280c07cbf75bf486e06ee7) Add quiescence negotiation (#2680)
- [4496ea7](https://github.com/ACINQ/eclair/commit/4496ea77bcc0d527d42c0b0eef7b4ddcb84b2031) Add more details to `InvalidCommitmentSignatures` (#2722)
- [c7e47ba](https://github.com/ACINQ/eclair/commit/c7e47ba751dc1ed4a96bcb4b7e5fcd49d78cfb78) Propagate next remote commit failed htlcs upstream (#2718)
- [42249d5](https://github.com/ACINQ/eclair/commit/42249d5ffab9c6ddb5a30340d374b13e97b8089b) Update to bitcoind 24.1 (#2711)
- [ef25e32](https://github.com/ACINQ/eclair/commit/ef25e3287aca889f05130a0d1cba1469b7dbf726) Increase timeout for offer tests (#2725)
- [3547f87](https://github.com/ACINQ/eclair/commit/3547f87f664c5e956a6d13af530a3d1cb6fc1052) Use bitcoin-lib 0.29 (#2708)
- [8d42052](https://github.com/ACINQ/eclair/commit/8d4205271dc426b1661a17c45d54b5e06ce9d0a0) Update kanela-agent version in starter scripts to match the version set in pom.xml (#2730)
- [841a8d9](https://github.com/ACINQ/eclair/commit/841a8d9b191aaab9e1a6a63bd1c7fb1abdf8fd4a) Ignore pre-generated shutdown script when possible (#2738)
- [404e3d5](https://github.com/ACINQ/eclair/commit/404e3d5ea6e004c1874ddf4cc0eb2ba04f7369aa) Set child splices as hints in watch-funding-spent (#2734)
- [948b4b9](https://github.com/ACINQ/eclair/commit/948b4b91dba77bd129f34563fb782923051c30fd) Don't send splice_locked before tx_signatures (#2741)
- [148fc67](https://github.com/ACINQ/eclair/commit/148fc673d46aad5c2e483d074db4e8c28165edd1) Add RPC to bump local commit fees (#2743)
- [6f87137](https://github.com/ACINQ/eclair/commit/6f8713788c0010336afcf880436a4361436fcab9) Delegate Bitcoin Core's private key management to Eclair (#2613)
- [4e339aa](https://github.com/ACINQ/eclair/commit/4e339aa841b11e68d16d7a892ebeaf776e7c1c96) Allow specifying a bitcoin wallet with an empty name (#2737)
- [96ebbfe](https://github.com/ACINQ/eclair/commit/96ebbfe78918799d809a29f2c7a2f1e57e25d84f) Limit how far we look into the blockchain (#2731)
- [59c612e](https://github.com/ACINQ/eclair/commit/59c612eb210ccfaa36ded31fd44904b056e61cbd) Fix flaky coin selection tests (#2749)
- [55f9698](https://github.com/ACINQ/eclair/commit/55f9698714fed099893976dc29e642dec2f7fde7) Fix `tx_signatures` retransmission (#2748)
- [d4c502a](https://github.com/ACINQ/eclair/commit/d4c502a7d6269f7863af4b99cd26f3852dce5db6) Use `bumpforceclose` RPC to also bump remote commit fees (#2744)
- [70d150b](https://github.com/ACINQ/eclair/commit/70d150bff601a4e272c6f848c84a9dc101c117c8) Fix tests that expect network minimum feerate to be less than other rates (#2751)
- [d274fc1](https://github.com/ACINQ/eclair/commit/d274fc193981e5e9ffdd2db2b60c888967f79676) Allow splicing on non dual-funded channels (#2727)
- [e3ba524](https://github.com/ACINQ/eclair/commit/e3ba5243061bf7af2556c4cb2855191bf61cbede) Improve startup resource usage (#2733)
- [3e1cc9d](https://github.com/ACINQ/eclair/commit/3e1cc9d39cd571454c1b1c29832f50bc312167e7) Update splice to handle pending committed htlcs (#2720)
- [37f3fbe](https://github.com/ACINQ/eclair/commit/37f3fbe4b590843bf0b162e014a640ac054b30d7) Assume widely supported features (#2732)
- [5dfa0be](https://github.com/ACINQ/eclair/commit/5dfa0be80d50a29edb0abb27112426f9631afd4f) Remove `ChannelFeatures`->`ChannelType` conversion (#2753)
- [0e4985c](https://github.com/ACINQ/eclair/commit/0e4985c0e07993a9db522bc05c282f78e02c9543) Poll bitcoind at startup instead of trying only once (#2739)
- [a3b1f9f](https://github.com/ACINQ/eclair/commit/a3b1f9fb234efd18f49c751ca3e19783debab9c2) Handle splice with local/remote index mismatch (#2757)
- [f6dbefd](https://github.com/ACINQ/eclair/commit/f6dbefd0936c622b8d59daa63617e6089865a13e) Add metrics on splicing (#2756)
- [e199d15](https://github.com/ACINQ/eclair/commit/e199d15d9fbad713c0565582a433483353f0b65b) Strip witness of interactive tx parents (#2755)
- [e4103a2](https://github.com/ACINQ/eclair/commit/e4103a2a659dfadf84e37fdc3b93f416ed4ca8b1) Simplify dual-funded min-depth calculation (#2758)
- [db8e0f9](https://github.com/ACINQ/eclair/commit/db8e0f9ba6b6ae646703308e90d29ed52fc836ef) Fixup! Add metrics on splicing (#2756) (#2759)
- [9ca9227](https://github.com/ACINQ/eclair/commit/9ca922716f452f8e80b16e35d132dd95c1e591c5) Adapt max HTLC amount to balance (#2703)
- [63a3c42](https://github.com/ACINQ/eclair/commit/63a3c4297f6575a3f19425fe78eaefedb52220d3) Ignore disabled edges for routing messages (#2750)
- [2879a54](https://github.com/ACINQ/eclair/commit/2879a54ad62b5935d744ac79ee7fefa4182226d1) Fix a flaky async payment triggerer test (#2764)
- [ca3f681](https://github.com/ACINQ/eclair/commit/ca3f6814a4e3359fb8f464ae6b5849c6593c022b) Allow using outgoing channel id for onion messages (#2762)
- [a3d90ad](https://github.com/ACINQ/eclair/commit/a3d90ad18aa3e1d351817a6c0f1e06d8a9479a71) Use local dust limit in local commit fee computation (#2765)
- [5b11f76](https://github.com/ACINQ/eclair/commit/5b11f76e00db4c7e46a37ded6239f2f6810e4616) Ignore duplicate dual-funding signatures (#2770)
- [5a37059](https://github.com/ACINQ/eclair/commit/5a37059b5b4f130817dc21c46e07c81947e389f7) Fix `tx_signatures` ordering for splices (#2768)
- [12adf87](https://github.com/ACINQ/eclair/commit/12adf87bb9214adc38974804daddd6049aa1c775) Abort interactive-tx during funding (#2769)
- [830335f](https://github.com/ACINQ/eclair/commit/830335f917f518d063178a64ff45d4dc834cbc98) Avoid unusable channels after a large splice (#2761)
- [35e318e](https://github.com/ACINQ/eclair/commit/35e318e951f60777ffcd8efeddab8faab80bfa4e) Remove reserve check if splice contribution is positive (#2771)
- [73a2578](https://github.com/ACINQ/eclair/commit/73a257800071c5c2e558ff61d2befb71fdf53b9e) Fix wallet name in `BitcoinCoreClientSpec` (#2772)
- [5fa7d4b](https://github.com/ACINQ/eclair/commit/5fa7d4b1bba1720a54a60816a767d07173af08b6) Nits (#2773)
- [0a833a5](https://github.com/ACINQ/eclair/commit/0a833a5578c0b18129c2924d990559a0d154bb8d) Disable channel when closing (#2774)
- [7be7d5d](https://github.com/ACINQ/eclair/commit/7be7d5d5247b1051213c39e7eca5682c2b45a379) Don't rebroadcast channel updates from `update_fail_htlc` (#2775)
- [772e2b2](https://github.com/ACINQ/eclair/commit/772e2b20f85bc2361c0852273b2bfb38dd5f93cc) Add support for sciddir_or_pubkey (#2752)
- [e20b736](https://github.com/ACINQ/eclair/commit/e20b736bf3467e327284a05c7c6074a0c082411f) Add hints when features check fail (#2777)
- [e73c1cf](https://github.com/ACINQ/eclair/commit/e73c1cf45c50a2ca4134be9cd780d66c39b9288a) Use typed TxId (#2742)
- [f0cb58a](https://github.com/ACINQ/eclair/commit/f0cb58aed4280e88b0b6657a7145df360de09c41) Add a txOut field to our InteractiveTxBuilder.Input interface (#2791)
- [d4a498c](https://github.com/ACINQ/eclair/commit/d4a498cdd63c6bc4463a73159d1b0384effb3a9e) Use bitcoinheaders.net v2 format (#2787)
- [be4ed3c](https://github.com/ACINQ/eclair/commit/be4ed3c68bcef461b0db24f7fa015d74bf421421) Update logback-classic (#2796)
- [9c4aad0](https://github.com/ACINQ/eclair/commit/9c4aad0688ba25bc108aeeb5c8b212ff7628caea) Dip into remote initiator reserve only for splices (#2797)
- [eced7b9](https://github.com/ACINQ/eclair/commit/eced7b909af84fbebdfc09d6974fa840b7513fd4) Update bitcoin-lib (#2800)
- [e05ed03](https://github.com/ACINQ/eclair/commit/e05ed03f2fc652e429f688aca9aa2bc084108c3d) Fix some typos in CircularRebalancing documentation (#2804)
- [63a1d77](https://github.com/ACINQ/eclair/commit/63a1d77baac906fc8e7c654fe738fb3c5deac03e) Fix typos in various comments (#2805)
- [3a49f5d](https://github.com/ACINQ/eclair/commit/3a49f5dd432aed8fdaba45b1214525d166864d57) FIX eclair-cli error code in case of HTTP problem (#2798)
- [61f1e1f](https://github.com/ACINQ/eclair/commit/61f1e1f82e4722eb3264544323d403734247f4da) Relay HTLC failure when inactive revoked commit confirms (#2801)
- [a9b5903](https://github.com/ACINQ/eclair/commit/a9b590365cc76f7a2a7a3d0fff9d6c4aac84147e) Fixes for quiescence back ported from lightning-kmp (#2779)
- [3bd3d07](https://github.com/ACINQ/eclair/commit/3bd3d07369966b37d47e4fc24d539d112ec05db1) Send `batch_size` on `commit_sig` retransmit (#2809)
- [c37df26](https://github.com/ACINQ/eclair/commit/c37df26c7a16e1d8fadb3265593c6d7549d160c4) Add a funding fee budget (#2808)
- [5fb9fef](https://github.com/ACINQ/eclair/commit/5fb9fef97872598b66376e437746d1c21d76a62e) Variable size for trampoline onion (#2810)
- [e66e6d2](https://github.com/ACINQ/eclair/commit/e66e6d2a1451f64315e4d5a0563fc1953a0a434f) Trampoline to blinded (types only) (#2813)
- [aae16cf](https://github.com/ACINQ/eclair/commit/aae16cf79f2b071fae8c1442aa07ee709dc07c96) Trampoline to blinded (#2811)
- [5b1c69c](https://github.com/ACINQ/eclair/commit/5b1c69cc8483626564b03805c2d92b11d0930e93) Add configurable threshold on maximum anchor fee (#2816)
- [3d8dc88](https://github.com/ACINQ/eclair/commit/3d8dc88089d69f144dee3c018e04702c6551cb40) Abandon transactions whose ancestors have been double spent (#2818)
- [b5e83a6](https://github.com/ACINQ/eclair/commit/b5e83a60929ea074e7a1df550b2cdda33abe6f04) Add closing test reconnect with 3rd-stage txs (#2820)
- [599f9af](https://github.com/ACINQ/eclair/commit/599f9afa2ce2d7cfa11bdcbcf54859f44d860083) Test bitcoind wallet behavior during mempool eviction (#2817)
- [f41bd22](https://github.com/ACINQ/eclair/commit/f41bd22d69f870856301d59c9dfecc240ba2770e) Asynchronously clean up obsolete HTLC info from DB (#2705)
- [62b739a](https://github.com/ACINQ/eclair/commit/62b739aa22c07f4487a3b7760d51be20d5be8370) fixup! Asynchronously clean up obsolete HTLC info from DB (#2705) (#2824)
- [86c4837](https://github.com/ACINQ/eclair/commit/86c483708a31c593b3da86edd3b14206b1614e12) Relay onion messages to compact node id (#2821)
- [e32044f](https://github.com/ACINQ/eclair/commit/e32044f05f44409af9ecfd9fdd4d5d5e4c999c78) Unlock utxos on reconnect when signatures haven't been sent (#2827)
- [0393cfc](https://github.com/ACINQ/eclair/commit/0393cfc27527a639d5a6910b3ef6bf522802f596) Add `require_confirmed_inputs` to RBF messages (#2783)
- [cd4d9fd](https://github.com/ACINQ/eclair/commit/cd4d9fd4b026180cd642c41b8e828470ad982e97) Unlock non-wallet inputs (#2828)
- [5d6a1db](https://github.com/ACINQ/eclair/commit/5d6a1db9fb39ed004a157fe79aa6aed748df8ed9) Skip anchor tx when remote commit has been evicted (#2830)
- [36a3c88](https://github.com/ACINQ/eclair/commit/36a3c8897cae18fe3ef219e24140b8d9c25bf239) More fine grained support for fee diff errors (#2815)
- [fd0cdf6](https://github.com/ACINQ/eclair/commit/fd0cdf6fc19205639c606a1f663af022cf614f8e) Allow plugins to set a dual funding contribution (#2829)
- [8723d35](https://github.com/ACINQ/eclair/commit/8723d355c4187fcdc984e81e5b51a745a896ea90) Activate dual funding by default (#2825)
