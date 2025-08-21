# Eclair vnext

<insert here a high-level description of the release>

## Major changes

### Update minimal version of Bitcoin Core

With this release, eclair requires using Bitcoin Core 30.x.
Newer versions of Bitcoin Core may be used, but have not been extensively tested.

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
    // We stop funding channels if our on-chain balance is below this amount.
    min-on-chain-balance-satoshis = 50000000 // 0.5 btc
    // We stop funding channels if the on-chain feerate is above this value.
    max-feerate-sat-per-byte = 5
    // Rate-limit the number of funding transactions we make per day (on average).
    max-funding-tx-per-day = 6
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

### Configuration changes

<insert changes>

### API changes

- `findroute`, `findroutetonode` and `findroutebetweennodes` now include a `maxCltvExpiryDelta` parameter (#3234)
- `findroute`, `findroutetonode` and `findroutebetweennodes` now include a `fee` field for each route in their full format response (#3283)
- `channel-opened` was removed from the websocket in favor of `channel-funding-created`, `channel-confirmed` and `channel-ready` (#3237 and #3256)
- `networkfees` and `channelstats` are removed in favor in `relaystats` (#3245)

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
