# Eclair vnext

<insert here a high-level description of the release>

## Major changes

### Liquidity Ads

This release includes support for the official version of [liquidity ads](https://github.com/lightning/bolts/pull/1153).
Liquidity ads allow nodes to sell their liquidity in a trustless and decentralized manner.
Every node advertizes the rates at which they sell their liquidity, and buyers connect to sellers that offer interesting rates.

Node operators who want to sell their liquidity must configure their funding rates in `eclair.conf`:

```conf
eclair.liquidity-ads.funding-rates = [
    {
        min-funding-amount-satoshis = 100000 // minimum funding amount at this rate
        max-funding-amount-satoshis = 500000 // maximum funding amount at this rate
        // The seller can ask the buyer to pay for some of the weight of the funding transaction (for the inputs and
        // outputs added by the seller). This field contains the transaction weight (in vbytes) that the seller asks the
        // buyer to pay for. The default value matches the weight of one p2wpkh input with one p2wpkh change output.
        funding-weight = 400
        fee-base-satoshis = 500 // flat fee that we will receive every time we accept a liquidity request
        fee-basis-points = 250 // proportional fee based on the amount requested by our peer (2.5%)
        channel-creation-fee-satoshis = 2500 // flat fee that is added when creating a new channel
    },
    {
      min-funding-amount-satoshis = 500000
      max-funding-amount-satoshis = 1000000
      funding-weight = 750
      fee-base-satoshis = 1000
      fee-basis-points = 200 // 2%
      channel-creation-fee-satoshis = 2000
    }
]
```

Node operators who want to purchase liquidity from other nodes must first choose a node that sells liquidity.
The `nodes` API can be used to filter nodes that support liquidity ads:

```sh
./eclair-cli nodes --liquidityProvider=true
```

This will return the corresponding `node_announcement`s that contain the nodes' funding rates.
After choosing a seller node, liquidity can be purchased on a new channel:

```sh
./eclair-cli open --nodeId=<seller_node_id> --fundingSatoshis=<local_contribution> --requestFundingSatoshis=<remote_contribution>
```

If the buyer already has a channel with the seller, and if the seller supports splicing, liquidity can be purchased with a splice:

```sh
./eclair-cli splicein --channelId=<channel_id> --amountIn=<amount_in> --requestFundingSatoshis=<remote_contribution>
./eclair-cli spliceout --channelId=<channel_id> --amountOut=<amount_out> --address=<output_address> --requestFundingSatoshis=<remote_contribution>
```

Note that `amountIn` and `amountOut` can be set to `0` when purchasing liquidity without splicing in or out.
It is however more efficient to batch operations and purchase liquidity at the same time as splicing in or out.

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
- `open`, `rbfopen`, `splicein` and `spliceout` now take an optional `--requestFundingSatoshis` parameter to purchase liquidity from the remote node. (#2926)

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
