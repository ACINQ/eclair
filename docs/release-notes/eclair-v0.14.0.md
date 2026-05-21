# Eclair v0.14.0

This release contains the final version of channel splicing, taproot channels and zero-fee commitments.
We remove support for channels that don't use anchor outputs: see the release notes of eclair v0.13.1 for instructions on how to properly close those channels if needed.
We introduce an experimental peer scoring algorithm to optimize routing.

This release is packed with improvements to the performance of large nodes when restarting: let us know if that helps!
And of course, we also included a few bug fixes.

:warning: We also update our dependency on Bitcoin Core to v30.x to benefit from v3/TRUC transactions and ephemeral dust.

## Major changes

### Update minimal version of Bitcoin Core

With this release, eclair requires using Bitcoin Core 30.x.
Newer versions of Bitcoin Core may be used, but have not been extensively tested.

### Remove support for non-anchor channels

We remove the code used to support legacy channels that don't use anchor outputs or taproot.
If you still have such channels, eclair won't start: you will need to close those channels, and will only be able to update eclair once they have been successfully closed.

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

### Zero-fee Channels (experimental)

This release adds support for zero-fee commitments (using v3/TRUC transactions), as specified in [the BOLTs](https://github.com/lightning/bolts/pull/1228).
With this new type of channels, commitment transactions don't pay any mining fee (in most cases), which gets rid of the `update_fee` mechanism and all of the related channel reserve issues.
It also gets rid of the undesired channel force-closes that happen when the mempool feerate spikes and channel participants disagree on what feerate to use, which has been a major source of wasted on-chain space.
It also offers better protection against pinning attacks (thanks to package relay) and reduces the on-chain footprint compared to anchor output channels.

This feature is not actived by default, because we haven't tested yet that v3/TRUC transactions always propagate to miners.
To enable it, add the following to your `eclair.conf`:

```conf
eclair.features.zero_fee_commitments = optional
```

To open a zero-fee channel with a node that supports the `zero_fee_commitments` feature, use the following command:

```sh
$ eclair-cli open --nodeId=<node_id> --fundingSatoshis=<funding_amount> --channelType=zero_fee_commitments
```

### Auto-refresh relay fees from configuration can be disabled

There is a performance hit on restart if there is a large number (> 100s) of channels, so it can be disabled with a new `eclair.relay.fees.reset-existing-channels` setting.

The default behavior is unchanged.

### Channel lifecyle events rework

Eclair emits several events during a channel lifecycle, which can be received by plugins or through the websocket.
We reworked these events to be compatible with splicing and consistent with 0-conf:

- we removed the `channel-opened` event
- we introduced a `channel-funding-created` event
- we introduced a `channel-confirmed` event
- we introduced a `channel-ready` event

The `channel-funding-created` event is emitted when the funding transaction or a splice transaction has been signed and can be published.
Listeners can use the `fundingTxIndex` to detect whether this is the initial channel funding (`fundingTxIndex = 0`) or a splice (`fundingTxIndex > 0`).

The `channel-confirmed` event is emitted when the funding transaction or a splice transaction has enough confirmations.
Listeners can use the `fundingTxIndex` to detect whether this is the initial channel funding (`fundingTxIndex = 0`) or a splice (`fundingTxIndex > 0`).

The `channel-ready` event is emitted when the channel is ready to process payments, which generally happens after the `channel-confirmed` event.
However, when using zero-conf, this event may be emitted before the `channel-confirmed` event.

See #3237 for more details.

### Major changes to the AuditDb

We make a collection of backwards-incompatible changes to all tables of the `audit` database.
The main change is that it is way more relevant to track statistics for peer nodes instead of individual channels, so we want to track the `node_id` associated with each event.
We also track more data about transactions we make and relayed payments, to more easily score peers based on the fees we're earning vs the fees we're paying (for on-chain transactions or for liquidity purchases).

Note that we cannot migrate existing data (since it is lacking information that we now need), so we simply rename older tables with a `_before_v14` suffix and create new ones.
Past data will thus not be accessible through the APIs, but can be queried directly using SQL if necessary.
It should be acceptable, since liquidity decisions should be taken based on relatively recent data (a few weeks) in order to be economically relevant (nodes that generated fees months ago but aren't generating any new fees since then are probably not good peers).

We expose a now `relaystats` API that ranks peers based on the routing fees they're generating.
See #3245 for more details.

### Experimental peer scoring

We're introducing a `PeerScorer` actor that gathers payment statistics about every peer we have a channel with.
It tracks fees earned, on-chain fees paid and liquidity fees (earned or paid) and how payment volume evolves over time.
It then ranks peers based on those statistics and identifies peers that may need additional liquidity to optimize revenue.
It will print tables of actions that should be taken, which can be extracted from `eclair.log` with:

```sh
grep PeerScorer eclair.log
```

It will provide recommendations for:

- channels to fund based on payment activity
- relay fees based on payment activity and fees earned
- channels to close to reclaim inefficient liquidity allocation

It is disabled by default: it can be enabled by adding the following line to `eclair.conf`:

```conf
eclair.peer-scoring.enabled = true
```

It contains a lot of parameters that need to be set to match your node's strategy. Here are the default values:

```conf
eclair.peer-scoring {
  // Set this to true if you want to start collecting data to score peers.
  enabled = false
  // Frequency at which we run our peer scoring algorithm.
  frequency = 1 hour
  // Maximum number of peers to select as candidates for liquidity and relay fee updates.
  top-peers-count = 10
  // A list of node_ids with whom we will try to maintain liquidity.
  top-peers-whitelist = []
  // On restart, we read all past events from the previous week from the DB, which is expensive.
  // We do this in 56 3-hour chunks to avoid performance issues, with a delay between each chunk.
  // We only read the first chunk after an initial delay, since this isn't critical and there are already a lot of DB
  // reads when restarting an eclair node that have higher priority.
  past-events {
    init-delay = 10 minutes
    chunk-delay = 10 seconds
  }
  // We can automatically allocate liquidity to our top peers when necessary.
  liquidity {
    // If true, we will automatically fund channels.
    auto-fund = false
    // If true, we will automatically close unused channels to reclaim liquidity.
    auto-close = false
    // We only fund channels if at least this amount is necessary.
    min-funding-amount-satoshis = 1000000 // 0.01 btc
    // We never fund channels with more than this amount.
    max-funding-amount-satoshis = 50000000 // 0.5 btc
    // Maximum total capacity (across all channels) per peer.
    max-per-peer-capacity-satoshis = 1000000000 // 10 btc
    // We won't close channels if our local balance is below this amount.
    local-balance-closing-threshold-satoshis = 10000000 // 0.1 btc
    // We won't close channels where the remote balance exceeds this amount.
    remote-balance-closing-threshold-satoshis = 5000000 // 0.05 btc
    // We won't close channels if this percentage of the channel capacity has been used during the past week.
    idle-channel-closing-threshold-percent = 0.075 // 7.5%
    // We stop funding channels if our on-chain balance is below this amount.
    min-on-chain-balance-satoshis = 50000000 // 0.5 btc
    // We stop funding channels if the on-chain feerate is above this value.
    max-feerate-sat-per-byte = 5
    // Rate-limit the number of funding transactions we make per day (on average).
    max-funding-tx-per-day = 6
    // If true, we will occasionally try to fund idle large capacity peers that have most funds on their side.
    revive-old-peers = false
    // Minimum time between funding the same peer, to evaluate whether the previous funding was effective.
    funding-cooldown = 72 hours
  }
  // We can automatically update our relay fees to our top peers when necessary.
  relay-fees {
    // If true, we will automatically update our relay fees based on variations in outgoing payment volume.
    auto-update = false
    // We will not lower our fees below these values.
    min-fee-base-msat = 1
    min-fee-proportional-millionths = 500
    // We will not increase our fees above these values.
    max-fee-base-msat = 10000
    max-fee-proportional-millionths = 5000
    // We only increase fees if the daily outgoing payment volume exceeds this threshold or daily-payment-volume-threshold-percent.
    daily-payment-volume-threshold-satoshis = 10000000 // 0.1 btc
    // We only increase fees if the daily outgoing payment volume exceeds this percentage of our peer capacity or daily-payment-volume-threshold.
    daily-payment-volume-threshold-percent = 0.05
  }
}
```

Node operators should adjust these values until the recommendations made by the peer scorer start making sense.
At that point, node operators may consider letting the peer scorer automatically perform actions by setting:

```conf
eclair.peer-scoring.liquidity.auto-fund = true
eclair.peer-scoring.liquidity.auto-close = true
eclair.peer-scoring.relay-fees.auto-update = true
```

This is highly experimental, and it is extremely hard to ensure that heuristics perform well for all types of nodes.
Use this at your own risk!

### Plugin validation of interactive transactions

We add a new `ValidateInteractiveTxPlugin` trait that can be extended by plugins that want to perform custom validation of remote inputs and outputs added to interactive transactions.
This can be used for example to reject transactions that send to specific addresses or use specific UTXOs.

Here is the trait definition:

```scala
/**
 * Plugins implementing this trait will be called to validate the remote inputs and outputs used in interactive-tx.
 * This can be used for example to reject interactive transactions that send to specific addresses before signing them.
 */
trait ValidateInteractiveTxPlugin extends PluginParams {
  /**
   * This function will be called for every interactive-tx, before signing it. The plugin should return:
   *  - [[Future.successful(())]] to accept the transaction
   *  - [[Future.failed(...)]] to reject it: the error message will be sent to the remote node, so make sure you don't
   *    include information that should stay private.
   *
   * Note that eclair will run standard validation on its own: you don't need for example to verify that inputs exist
   * and aren't already spent. This function should only be used for custom, non-standard validation that node operators
   * want to apply.
   */
  def validateSharedTx(remoteNodeId: PublicKey, remoteInputs: Map[OutPoint, TxOut], remoteOutputs: Seq[TxOut]): Future[Unit]
}
```

See #3258 for more details.

### Channel jamming accountability

We update our channel jamming mitigation to match the latest draft of the [spec](https://github.com/lightning/bolts/pull/1280).
Note that we use a different architecture for channel bucketing and confidence scoring than what is described in the BOLTs.
We don't yet fail HTLCs that don't meet these restrictions: we're only collecting data so far to evaluate how the algorithm performs.

If you want to disable this feature entirely, you can set the following values in `eclair.conf`:

```conf
eclair.relay.peer-reputation.enabled = false
eclair.relay.reserved-for-accountable = 0.0
```

### API changes

- `findroute`, `findroutetonode` and `findroutebetweennodes` now include a `maxCltvExpiryDelta` parameter (#3234)
- `findroute`, `findroutetonode` and `findroutebetweennodes` now include a `fee` field for each route in their full format response (#3283)
- `channel-opened` was removed from the websocket in favor of `channel-funding-created`, `channel-confirmed` and `channel-ready` (#3237 and #3256)
- `networkfees` and `channelstats` are removed in favor in `relaystats` (#3245)

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

- [ddf75bc](https://github.com/ACINQ/eclair/commit/ddf75bc64bd67425ef49b334a13287850684af2b) Back to dev (#3197)
- [26d0350](https://github.com/ACINQ/eclair/commit/26d035070fe4c427d44b34ff72947ae67581f2d6) Require closed channels migration before starting (#3198)
- [9911cb7](https://github.com/ACINQ/eclair/commit/9911cb73a377d46a7d3c03a4e090f2aeff000249) Remove support for non-anchor channels (#3173)
- [3b69013](https://github.com/ACINQ/eclair/commit/3b6901316b5d786c330e2d6af44eb87ffe93048f) Stop sending `update_fee` for mobile wallets (#3186)
- [6dd9f76](https://github.com/ACINQ/eclair/commit/6dd9f769c307498d2f6c83750d76eada84873975) Add more tolerance in tests (#3199)
- [7ef3c4b](https://github.com/ACINQ/eclair/commit/7ef3c4b204440aa350624fc8af7b45b34c9b641e) Nits (#3203)
- [767bae8](https://github.com/ACINQ/eclair/commit/767bae8b63024fe9d421f8e05e4153825f25cbe3) Add more logs around `commit_sig` (#3202)
- [a014211](https://github.com/ACINQ/eclair/commit/a014211b5a648793947042293ae04b75d4f4caae) Enable detailed monitoring for singleton actors (#3200)
- [235e95d](https://github.com/ACINQ/eclair/commit/235e95df8f7a549f02d871cf9cafc3fc919638c9) Add `CommitSigBatch` codec (#3205)
- [ff1ce1f](https://github.com/ACINQ/eclair/commit/ff1ce1fe1722259edd3b0011772481f43767b541) Check that relay fees are nonnegative (#3209)
- [5e1a488](https://github.com/ACINQ/eclair/commit/5e1a48827f76711fe7cf4c2c375001a6ce12b708) Allow aborting liquidity purchases after signing (#3206)
- [bfe34ab](https://github.com/ACINQ/eclair/commit/bfe34ab1da9c37f8aeb41ad239e2ca7b9fba8631) Use 73 bytes der-encoded signatures in weight estimation (#3210)
- [df75ed5](https://github.com/ACINQ/eclair/commit/df75ed5a8374e22c84f5c67b244c953d28386fe1) Update `bitcoin-lib` (#3213)
- [f6a5a3c](https://github.com/ACINQ/eclair/commit/f6a5a3cafd7dfb6088a60ee58be9ff66b483f9dc) Allow high remote `dust_limit_satoshis` (#3215)
- [fa7e2ee](https://github.com/ACINQ/eclair/commit/fa7e2ee3ba6c70041b0f6a6ca14da3598a22d9a4) Update default configuration for revoked HTLC clean-up (#3212)
- [a87963b](https://github.com/ACINQ/eclair/commit/a87963b191333cf82e8f119f2c347a71f1dea0e3) Monitor the internal state of `ReputationRecorder` (#3216)
- [e1775ee](https://github.com/ACINQ/eclair/commit/e1775eeba696494e41c5afd791e8a2103032f127) Fix links to 'Reaching The Ground With Lightning' (#3219)
- [38dc407](https://github.com/ACINQ/eclair/commit/38dc4072ee6d444f26b8eba73ce3203e02f13e3b) Add API methods to spend funds sent to taproot channel addresses (#3220)
- [0318fb7](https://github.com/ACINQ/eclair/commit/0318fb78970b7c854b0c7d516565cc21cd348a6a) Unwatch previous funding tx after splice  (#3218)
- [856e236](https://github.com/ACINQ/eclair/commit/856e236f67fb7c5753201bd5ff8af90694e05490) Identify failing node by its index (#3224)
- [5aea514](https://github.com/ACINQ/eclair/commit/5aea514256a75329a260c3847238b11c2df0c535) Allow remote `dust_limit_satoshis` up to 5000 sats (#3227)
- [36822bb](https://github.com/ACINQ/eclair/commit/36822bb67aeab04f833ad2e27c9b10a0afb718ac) Don't scan the blockchain for spent external channels (#3226)
- [53747cc](https://github.com/ACINQ/eclair/commit/53747cc5e65d2cff03878407bf7f8412692354d4) Use bitcoin-lib 0.46 (taproot tweak refactor) (#3225)
- [288c541](https://github.com/ACINQ/eclair/commit/288c541805a67f446c07e7bf2b31decedba9190d) fixup! Add API methods to spend funds sent to taproot channel addresses (#3220) (#3228)
- [3b2bc57](https://github.com/ACINQ/eclair/commit/3b2bc57058ea5b0ca35578cc5068cb1d044d0ccc) Add `maxCltvExpiryDelta` parameter to `findRoute*` APIs (#3234)
- [1bc0976](https://github.com/ACINQ/eclair/commit/1bc09764381dea842e058705599ca1391f85aa4f) Validate Bolt 11 fallback addresses (#3232)
- [e3fd186](https://github.com/ACINQ/eclair/commit/e3fd18671c37f65c31cf62d1d9c2f2d7ecc2b5ab) Accountable HTLCs (#3217)
- [c214b07](https://github.com/ACINQ/eclair/commit/c214b075df19e157dbfb66b0401e9e078bde6103) Don't rebroadcast announcements for spent channels (#3235)
- [7137eac](https://github.com/ACINQ/eclair/commit/7137eac2800757329ea12278429659601b27855e) Stop storing channel errors in `AuditDb` (#3236)
- [473b46d](https://github.com/ACINQ/eclair/commit/473b46d8ad7295a57ee3b548eb8813ec2fc97fdb) Rework channel lifecyle events (#3237)
- [3ac122b](https://github.com/ACINQ/eclair/commit/3ac122b9e39428b0d443881740ed0c8cc6a65b13) CI: fix test with latest bitcoind (#3239)
- [9ed0014](https://github.com/ACINQ/eclair/commit/9ed0014a2561bac2bd82091c7adb27f0bdb2a4e1) Use fallback feerates on testnets (#3233)
- [0214a1e](https://github.com/ACINQ/eclair/commit/0214a1e790c57d7192f32f246f66d4f15d659745) More tests for accountability (#3240)
- [632713a](https://github.com/ACINQ/eclair/commit/632713a655d2240b6fddc4bf62f0a01c343efc0a) Add test vector for Bolt12 invalid bech32 padding (#3242)
- [9856db8](https://github.com/ACINQ/eclair/commit/9856db854b3cd717d81dbec1f1c77847fc6c22d6) Add duration information to payment events (#3241)
- [ff7a24c](https://github.com/ACINQ/eclair/commit/ff7a24c5eb260032f99c49e814726105941ecb81) Include the `node_id` of channel peers in payment events (#3243)
- [369f042](https://github.com/ACINQ/eclair/commit/369f042d783ffb4c2596bc425506204eb971a7a5) Add event for failed payment relay (#3244)
- [d735e0b](https://github.com/ACINQ/eclair/commit/d735e0b55bda9e3f19800e97403c665eed60cb2f) Improve channel and payment events (#3246)
- [aff16b2](https://github.com/ACINQ/eclair/commit/aff16b264b0b551f9240bbade07ed97fcf924529) Prioritize private channels when relaying payments (#3248)
- [5c5b9f9](https://github.com/ACINQ/eclair/commit/5c5b9f90f9f62d55af95a3612d00cebea5472412) Add type to local/remote error metrics (#3249)
- [ea183c3](https://github.com/ACINQ/eclair/commit/ea183c35f84be8ee0d91ba5bca882efed851b546) (Minor) Upgrade postgres libs (#3238)
- [7c52250](https://github.com/ACINQ/eclair/commit/7c52250ef76521b166ed8809d9c47941bd7dc6fd) Fix balance fuzz tests (#3251)
- [4461c04](https://github.com/ACINQ/eclair/commit/4461c0407eed9759e59ae7bc3d1b02033c8e4a3b) Select `channel_type` for automatic channel creation (#3250)
- [11a8604](https://github.com/ACINQ/eclair/commit/11a86046c0403b45fa459aa8aad405115ef475b5) Add claude files to `.gitignore` (#3252)
- [9709a91](https://github.com/ACINQ/eclair/commit/9709a916ab06c0fa3b5a0540ea47fa171ee6e0c6) Fix flaky wallet funding tests (#3253)
- [d473b53](https://github.com/ACINQ/eclair/commit/d473b53aba42df4a43687d5d18565f7a04d4f799) Add basic CLAUDE.md (#3254)
- [546028f](https://github.com/ACINQ/eclair/commit/546028fdfae609812aab004b4a07887bc13eed2c) Don't automatically use `scid_alias` for public channels (#3255)
- [5f934ea](https://github.com/ACINQ/eclair/commit/5f934ea0a3bd3b1241e6c5e0a779f55cf83753b1) Lazily load peer storage at node restart (#3257)
- [656e503](https://github.com/ACINQ/eclair/commit/656e5039017828da20063e18fcec07ad234cd296) Add `ChannelFundingCreated` event (#3256)
- [0a9853b](https://github.com/ACINQ/eclair/commit/0a9853bf28d0cb19511df66b3cfa1335be6b8b11) Auto-refresh relay fees from conf can be disabled (#3260)
- [28f3545](https://github.com/ACINQ/eclair/commit/28f3545af4da83bf03b16777dabc2538b30e5c9d) Plugin validation of interactive transactions (#3258)
- [eef7c32](https://github.com/ACINQ/eclair/commit/eef7c3268de307abbe853258b7196203daac5a60) Remove support for zlib encoding for channel queries (#3263)
- [16ebf01](https://github.com/ACINQ/eclair/commit/16ebf01a82f88502d913b00ac27028c441e55b8c) Reject offers with amount set to `0` (#3265)
- [1543e7c](https://github.com/ACINQ/eclair/commit/1543e7ca5b60619984c432b950696db6bf315713) Fix flaky onion message test (#3266)
- [a4f4abd](https://github.com/ACINQ/eclair/commit/a4f4abd94a5e7a7dc6b9c69cb9be69e598c53f65) Improve support for plugin-defined features (#3264)
- [a4d66ad](https://github.com/ACINQ/eclair/commit/a4d66adce2ec180c94532d1d0014f86f6e74f607) Add bitcoin rpc call to check if an address belongs to our wallet (#3267)
- [8c5f39f](https://github.com/ACINQ/eclair/commit/8c5f39f434bf6de32227d7ac92918ec7dd979608) Fix race condition in `Postman` causing flaky `OfferPayment` tests (#3270)
- [c24c3a7](https://github.com/ACINQ/eclair/commit/c24c3a74354d839228762c0c295c07b6f8a90072) Add per-peer profit scoring (#3247)
- [bda5518](https://github.com/ACINQ/eclair/commit/bda55186f01bc59299fc9ef7db48cd7f6e732969) Update Bitcoin Core to v30.2 (#3274)
- [fde8de6](https://github.com/ACINQ/eclair/commit/fde8de65ffd984f071ea1cc9657dbf710f52994f) Close connection when receiving malformed messages (#3273)
- [890ccb3](https://github.com/ACINQ/eclair/commit/890ccb37da47398e5757feea8af32411e88292bf) Improve data stored in `AuditDb` (#3245)
- [5e741b1](https://github.com/ACINQ/eclair/commit/5e741b1ac4f6a342f7e81bc9b18d672a957d7e95) Use bitcoin-lib 0.47 (#3268)
- [96f9b0d](https://github.com/ACINQ/eclair/commit/96f9b0d88401b99a4c4d7181951a58aad088d69c) Reclaim liquidity from idle channels (#3269)
- [a05f583](https://github.com/ACINQ/eclair/commit/a05f58300bcc24c2763d3dee6bd691a02f3a1c9f) Initialize peer stats with past events (#3272)
- [e6c3d6e](https://github.com/ACINQ/eclair/commit/e6c3d6ef7a31a10917eb5d41a7f79a458ea5521c) Add fuzzing infrastructure (#3276)
- [6336d80](https://github.com/ACINQ/eclair/commit/6336d80cbcb61847fc01f06239ff7031647275f8) Follow BOLT1 handling for "no reply" pings: ignore, don't warn. (#3278)
- [129df36](https://github.com/ACINQ/eclair/commit/129df369530ece6d1a48632e7f9d17ed09e93a4d) Add backwards-compatible parts of the official splicing protocol (#3261)
- [3ae7381](https://github.com/ACINQ/eclair/commit/3ae73813452efd2fcf64952e3b3f7516d01a1977) Allow reply-less pings (#3284)
- [9314fe9](https://github.com/ACINQ/eclair/commit/9314fe9918add60535c99ea83719521e89cfc492) Add fee field to `findroute` full format response (#3283)
- [710e849](https://github.com/ACINQ/eclair/commit/710e849723b48af6fd6975c46045febf52f48534) Fix flaky `PeerStatsTracker` test (#3280)
- [0778eaf](https://github.com/ACINQ/eclair/commit/0778eafae34bfafb5e4f1412a8566edc2f54b611) Add fuzz tests for onion, route blinding and lightning message codecs (#3282)
- [8abe590](https://github.com/ACINQ/eclair/commit/8abe5900eb9cf07c4ef66f371621b6e2e4a10d18) Use noble docker images instead of alpine (#3286)
- [1a88ebe](https://github.com/ACINQ/eclair/commit/1a88ebe199faf6382811578d7daad067270c7036) Add delays when reading past payment events for stats (#3288)
- [689dbda](https://github.com/ACINQ/eclair/commit/689dbda1bfba893d059fe02028f38a55d1c2d845) Don't try reconnecting automatically to mobile wallets (#3287)
- [0211d67](https://github.com/ACINQ/eclair/commit/0211d67f1375c90e314331c87a9c54f7936ba93b) Don't load network graph twice on start-up (#3290)
- [5b43e95](https://github.com/ACINQ/eclair/commit/5b43e954e385018331a048ca90e62b08c5371f4c) Fix flaky zero-conf integration test (#3291)
- [20dc410](https://github.com/ACINQ/eclair/commit/20dc4105437099f46605b34636121a6e7027896b) Add peer scorer documentation (#3277)
- [119f8c2](https://github.com/ACINQ/eclair/commit/119f8c2156e13a098b520fbaf2e44a5d967bba48) Add threshold for disabling `from_future_htlc` (#3293)
- [5c44f00](https://github.com/ACINQ/eclair/commit/5c44f005abf454b0fe314499cdb3e0822ebc8769) Increase start-up ZMQ timeout (#3294)
- [da7cd96](https://github.com/ACINQ/eclair/commit/da7cd962a8db61411aea2a07493ccf7a71d6cf18) Store our closing_complete in the simple close session (#3289)
- [6080ffb](https://github.com/ACINQ/eclair/commit/6080ffb0e55b52a6dedefa630bc488ccb0774eb9) More aggressive peer scorer idle channels management (#3295)
- [68b0096](https://github.com/ACINQ/eclair/commit/68b0096a2f9450e9963c4b61fc16f57449fe943c) Add support for the official splicing protocol (#2887)
- [872cb66](https://github.com/ACINQ/eclair/commit/872cb663f56e3319878388027e183bf7101daee3) Use official feature bit for `option_simple_taproot` (#3144)
- [0f5d4b8](https://github.com/ACINQ/eclair/commit/0f5d4b89330f2ae383b13fc64b480d755ba6c9d4) Fix flaky DER signature weight test (#3299)
- [113d8fb](https://github.com/ACINQ/eclair/commit/113d8fba770b64e116b23a7d1ef4cd06053a19ee) Add metrics on interactive-tx inputs and outputs (#3300)
- [6bb7f25](https://github.com/ACINQ/eclair/commit/6bb7f258e285181183872d3336e7d4dcc8d1b319) Add liquidity ads metrics (#3301)
- [66977cc](https://github.com/ACINQ/eclair/commit/66977cc6318dea0544aefb85c9aa1e426cb157d6) Add previous commitments to ChannelFundingConfirmed event (#3303)
- [cb8f6c8](https://github.com/ACINQ/eclair/commit/cb8f6c8997c810b094eea9cc5c2ecf3ecc9c1667) Docker: auto-approve apt-get install command (#3302)
- [76da19f](https://github.com/ACINQ/eclair/commit/76da19f1b97fec30097ab1016f8cb51dca2917e0) Bump org.postgresql:postgresql version (#3305)
- [e133015](https://github.com/ACINQ/eclair/commit/e1330154a5ba23bed2b94198c90b25054c48c7b3) Compute channel keys once on on startup (#3306)
- [befdcd5](https://github.com/ACINQ/eclair/commit/befdcd5444f49aca72b30c8281d807321d13aba8) Fix edge case in `availableBalanceForSend/Receive` (#3308)
- [8876ef1](https://github.com/ACINQ/eclair/commit/8876ef184a2eea242ea6a07f117b9bc0ef6c791b) Update features early on reconnection (#3310)
- [06538ad](https://github.com/ACINQ/eclair/commit/06538ada07403e06b8a9dfaa59fb1c554841a3a4) Change RBF feerate bump rule to match BIP125 (#3298)
- [3540340](https://github.com/ACINQ/eclair/commit/35403401008228c71e9ae301bde5092101e9b38f) Add support for zero-fee commitment format (#3192)
- [0896d03](https://github.com/ACINQ/eclair/commit/0896d03b361a3fff54b6378b0e5fe0424e7c4d95) Fix Bolt12 path fee hiding (#3311)
