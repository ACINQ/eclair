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
- `open`, `rbfopen`, `splicein` and `spliceout` now take an optional `--requestFundingSatoshis` parameter to purchase liquidity from the remote node. (#2926)

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
