# Eclair v0.8.0

This release adds official support for two important lightning features: zero-conf channels and channel aliases.
It also adds experimental support for dual-funding and a lot of preparatory work for Bolt 12 offers.

:warning: we also require at least Bitcoin Core 23.0: make sure to upgrade and reconfigure your node (see instructions below)!

## Major changes

### Bitcoin Core 23 or higher required

This release adds support for Bitcoin Core 23.x and removes support for previous Bitcoin Core versions.
Please make sure you have updated your Bitcoin Core node before updating eclair, as eclair won't start when connected to older versions of `bitcoind`.

Bitcoin Core 23.0 updated what type of change outputs are created: when sending funds to an address, `bitcoind` will create a change output that matches that address type.
This is undesirable for lightning, as non-segwit inputs cannot be used for lightning channels (funds can be lost when using them).
Bitcoin Core needs to be configured to generate segwit addresses only, so you must add the following lines to your `bitcoin.conf`:

```conf
addresstype=bech32
changetype=bech32
```

Note that you may also use `bech32m` if your peers support `option_shutdown_anysegwit`.

### Add support for channel aliases and zeroconf channels

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

### Experimental support for dual-funding

This release adds experimental support for dual-funded channels, as specified [here](https://github.com/lightning/bolts/pull/851).
Dual-funded channels have many benefits:

- both peers can contribute to channel funding
- the funding transaction can be RBF-ed

This feature is turned off by default, because there may still be breaking changes in the specification.
To turn it on, simply enable the feature in your `eclair.conf` (use at your own risk until the specification is final!):

```conf
eclair.features.option_dual_fund = optional
```

If your peer also supports the feature, eclair will automatically use dual-funding when opening a channel.
If the channel doesn't confirm, you can use the `rbfopen` RPC to initiate an RBF attempt and speed up confirmation.

In this first version, the non-initiator cannot yet contribute funds to the channel.
This will be added in future updates, most likely in combination with [liquidity ads](https://github.com/lightning/bolts/pull/878).

### Fix channel pruning behavior

The specification says that a channel should be pruned whenever _one_ side has not updated their `channel_update` in two weeks.
Eclair previously only pruned channels when _both_ sides didn't update, because we assumed that nodes would not keep generating useless `channel_update`s if their peer is offline.
But that assumption wasn't correct: `lnd` does generate such updates.

This release fixes the pruning behavior to strictly follow the specification, which results in a slightly smaller network DB.

### Probabilistic estimation of remote channels balance

Whenever an outgoing payment is attempted, eclair will use the result to keep track of the balance of channels in the path.
The path-finding algorithm doesn't yet use these estimates: we want to gather real-world data and improve the model before we can actually use it.
But a future release of eclair will leverage this data to improve payments reliability.
See #2272 for more details.

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

### Remove support for MacOS arm64

This version of eclair uses `libsecp256k1` natively, we include bindings for Linux arm64 but not for MacOS arm64 yet.
This can be worked around by downloading an x86-64 version of the JVM to run eclair.
See [the following issue](https://github.com/ACINQ/eclair/issues/2427) for more details.

### API changes

- `channelbalances` retrieves information about the balances of all local channels (#2196)
- `channelbalances` and `usablebalances` return a `shortIds` object instead of a single `shortChannelId` (#2323)
- `stop` stops eclair: please note that the recommended way of stopping eclair is simply to kill its process (#2233)
- `rbfopen` lets the initiator of a dual-funded channel RBF the funding transaction (#2275)
- `listinvoices` and `listpendinginvoices` now accept `--count` and `--skip` parameters to limit the number of retrieved items (#2474)

### Miscellaneous improvements and bug fixes

#### Beta support for Prometheus and Grafana

Eclair can export metrics to Prometheus and consume them from Grafana.
Head over to the [documentation](../Monitoring.md) to set it up for your node.
The dashboards we provide are currently very basic: if they are useful to you, please consider opening pull requests to improve them.

#### Randomize outgoing payment expiry

When sending a payment, if the cltv expiry used for the final node is very close to the current block height, it lets intermediate nodes figure out their position in the route.
To protect against this, a random delta is now added to the current block height, which makes it look like there are more hops after the final node.

You can configure the delta used in your `eclair.conf`:

```conf
// minimum value that will be added to the current block height
eclair.send.recipient-final-expiry.min-delta = 100
// maximum value that will be added to the current block height
eclair.send.recipient-final-expiry.max-delta = 400
```

You can also disable this feature by setting both values to 1:

```conf
// Note that if you set it to 0, payments will fail if the recipient has just received a new block.
// It is thus recommended to set if to a low value that is greater than 0.
eclair.send.recipient-final-expiry.min-delta = 1
eclair.send.recipient-final-expiry.max-delta = 1
```

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

- `eclair.purge-expired-invoices.enabled = true`
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

#### Public IP addresses can be DNS host names

You can now specify a DNS host name as one of your `server.public-ips` addresses (see PR [#911](https://github.com/lightning/bolts/pull/911)).
Note: you can not specify more than one DNS host name.

#### Support for testing on the signet network

To test Eclair with bitcoind configured for signet, set the following values in `eclair.conf`:

```conf
eclair.chain = "signet"
eclair.bitcoind.rpcport=38332
```

#### Support lengthy onion errors

The encrypted errors returned with failed payments were previously limited to 256 bytes.
Eclair now allows errors of arbitrary length, which paves the way for future updates to the onion errors used in lightning.
See #2441 for more details.

#### Deprecate zlib gossip compression

Gossip compression using zlib has been [deprecated](https://github.com/lightning/bolts/pull/981) in the specification and removed from eclair (#2244).
Eclair still supports decompressing zlib content for backwards-compatibility, but will not compress outgoing gossip anymore.

#### Remove legacy Sphinx onion format

The legacy onion format has been replaced by the more flexible variable-length onion format.
Support for the legacy format has been [removed from the specification](https://github.com/lightning/bolts/pull/962).

#### Make `payment_secret` mandatory in Bolt 11 invoices

The `payment_secret` was introduced years ago to ensure that intermediate nodes cannot steal from payments to amountless invoices.
It is now fully supported across the network, so we make it mandatory.

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

- [4451069](https://github.com/ACINQ/eclair/commit/44510698f7c07ffd4c9f42223a4bcec6230062a1) Back to Dev (#2159)
- [cc61f12](https://github.com/ACINQ/eclair/commit/cc61f121ecbf70061cc191ae68c98469ae8419bd) Fix publish loop when funding tx double-spent (#2162)
- [648f93f](https://github.com/ACINQ/eclair/commit/648f93f682f7b980507158fd3dc65d0a1c30962a) Ignore revoked htlcs after restart (#2161)
- [8a65e35](https://github.com/ACINQ/eclair/commit/8a65e35c8f1778e931b71821a9b32b13e68a1b93) Don't broadcast commit before funding locked (#2163)
- [4307bad](https://github.com/ACINQ/eclair/commit/4307bada516aae604a9ba0894ae679146ea971bc) Refactoring of PaymentRequest (#2144)
- [fa31d81](https://github.com/ACINQ/eclair/commit/fa31d81d0eba76b8c2e931642f726cdb80aade99) Improve postman (#2147)
- [553727c](https://github.com/ACINQ/eclair/commit/553727cb22d3d4a0f596b75a3ab733769bb24479) Convert wiki pages in to files in the docs directory and general docs file cleanups (#2165)
- [9401592](https://github.com/ACINQ/eclair/commit/94015923827c2e49525382c8022155904f270c27) Typed features (#2164)
- [daddee1](https://github.com/ACINQ/eclair/commit/daddee1912e9fc627f0cabe841ca7504e00fef21) fixup! Convert wiki pages in to files in the docs directory and general docs file cleanups (#2165) (#2167)
- [ca71a3c](https://github.com/ACINQ/eclair/commit/ca71a3c1526bec18fb81cb9f873de257d0559fc9) Update a few dependencies (#2166)
- [66aafd6](https://github.com/ACINQ/eclair/commit/66aafd68723bf6a6ad2dd5612586c7cf0335b279) Better logging for NegativeProbability exception (#2171)
- [92221e8](https://github.com/ACINQ/eclair/commit/92221e8409d04bc604ad1ba062b9c34b513c51ea) Fix path-finding (#2172)
- [53cb50a](https://github.com/ACINQ/eclair/commit/53cb50a36e623c1dffea8a219167f634a3072a01) Restrict building and publishing docker images to new pushes on master (#2182)
- [99aabda](https://github.com/ACINQ/eclair/commit/99aabdabd046baf82e2ed83625ef1712b785f1a5) Add txId in logback.xml (#2183)
- [609c206](https://github.com/ACINQ/eclair/commit/609c206a675ff3f98fedff7399216bd022b2a637) Ignore IBD in regtest (#2185)
- [b5b2022](https://github.com/ACINQ/eclair/commit/b5b2022efc71b0d15fd98d29995dfc11f75d5b70) Add more PR details to contributing guidelines (#2186)
- [068b139](https://github.com/ACINQ/eclair/commit/068b1393e16e33adc971e7ef4b189cbfd82f245c) purge expired invoice at given time interval (#2174)
- [8f7c6ed](https://github.com/ACINQ/eclair/commit/8f7c6ed68e5a73043205b9f6582bf929eeaa3c43) Refactor `Closing.claimRemoteCommitMainOutput()` (#2191)
- [67fb392](https://github.com/ACINQ/eclair/commit/67fb392a28617214c8298da2f1f92071f25b0233) Use direct channel when available (#2192)
- [ab30af8](https://github.com/ACINQ/eclair/commit/ab30af8fbc09feb2b9b0af4337f4d9486727a0b6) minFinalExpiryDelta is not optional (#2195)
- [dba73c8](https://github.com/ACINQ/eclair/commit/dba73c8aa4311e96394fc7fa86811d854cb28360) (Minor) Flatten `ChannelDataSpec` tests
- [be78e0c](https://github.com/ACINQ/eclair/commit/be78e0ca578726015bd32e45bd0310415061843c) Separate htlc calculation from local/remote commit (no quicklens) (#2194)
- [c4f67e7](https://github.com/ACINQ/eclair/commit/c4f67e7495a3c1273a22cca5bf69dac104add564) Bump postgres-jdbc version (#2198)
- [f3c6c78](https://github.com/ACINQ/eclair/commit/f3c6c7885de5b4c8c48302b06b25b1e7422c763c) Do not fail to relay payments with insufficient fee rate that satisfy the previous fee rate (#2201)
- [8f2c93c](https://github.com/ACINQ/eclair/commit/8f2c93c1ab34ceeb5ea697be3c499916078b9130) Add delayed fees to release notes (#2204)
- [f14300e](https://github.com/ACINQ/eclair/commit/f14300e33f86e71c5fbaf0f46c75b12abaaab8a9) two new min-funding config parameters for public and private channels  (#2203)
- [7b5cefa](https://github.com/ACINQ/eclair/commit/7b5cefaf99a4ccbe7462ff663f52447b84b124c1) Replace `FeatureScope` by type hierarchy (#2207)
- [18ba900](https://github.com/ACINQ/eclair/commit/18ba90067ec04741cd9c52b3a62a04f98dd46b68) (Minor) Fix timeout in tests (#2208)
- [5af042a](https://github.com/ACINQ/eclair/commit/5af042a56afa549a3e5480ad23f0b228a869f242) Add messages for when startup fails due to wrong bitcoind wallet(s) loaded (#2209)
- [fd84909](https://github.com/ACINQ/eclair/commit/fd849095854547dfcf0af5e80aa906fa36cdbbf4) Fix TLV tag number in exception (#2212)
- [3e02046](https://github.com/ACINQ/eclair/commit/3e02046a52a19f9230c4bb63303154108860923d) Add logs about local channel graph inclusion (#2184)
- [dbd9e38](https://github.com/ACINQ/eclair/commit/dbd9e38b5ddfa0ff88a04baf0eebd329aa4cff5a) Add explicit github permissions to bitcoin core build (#2200)
- [6823309](https://github.com/ACINQ/eclair/commit/682330987400b0d035ca5284e5c2fcb2fd6412df) Use `NodeAddress` everywhere instead of `InetAddress` (#2202)
- [0e88440](https://github.com/ACINQ/eclair/commit/0e88440e6a215ce33da019b60cbb714131fbdbff) Make trampoline pay the right fee (#2206)
- [d135224](https://github.com/ACINQ/eclair/commit/d13522480598be29cffe99b6ff7b453bb9403a3b) Refactor channel into separate traits (#2217)
- [2872d87](https://github.com/ACINQ/eclair/commit/2872d876d03ac6930d5d9465a9d5e7e45fbcf175) Fix latest bitcoin build (#2218)
- [9358e5e](https://github.com/ACINQ/eclair/commit/9358e5e1f5d5fbbf19edcb8891693822c0d4f7bf) Add `channelbalances` API call  (#2196)
- [6f31ed2](https://github.com/ACINQ/eclair/commit/6f31ed2dc99399783d2aef842cdb5fa19a327ede) Add release notes for new min-funding settings and invoice purger  (#2210)
- [1627682](https://github.com/ACINQ/eclair/commit/162768274e5e40e67381482fbe37ce0e797a0f65) Remove ipv6 zone identifier test (#2220)
- [7883bf6](https://github.com/ACINQ/eclair/commit/7883bf621dd6add1ab25b23d43cb8b47154acad8) Use bitcoin-kmp through bitcoin-lib (#2038)
- [36ed788](https://github.com/ACINQ/eclair/commit/36ed788d392e53a95f707c33dd0930871c72936a) Update experimental trampoline feature bit (#2219)
- [446a780](https://github.com/ACINQ/eclair/commit/446a780989f2d326df2511b92e1fb33cac8bc723) Refactor channel transitions (#2228)
- [ddb52d8](https://github.com/ACINQ/eclair/commit/ddb52d82ea211d97eff9af42ac57308e9002b149) Check that channel seed has not been modified (#2226)
- [7cf2e20](https://github.com/ACINQ/eclair/commit/7cf2e209ec4a6d604ea1819bde3b2368d69ece3d) Remove Phoenix workaround for PaymentSecret dependency (#2230)
- [787c51a](https://github.com/ACINQ/eclair/commit/787c51acc27ea23745b4496c5f8ebae81483ba2b) Add a "stop" API method (#2233)
- [c7c515a](https://github.com/ACINQ/eclair/commit/c7c515a0edd84fb9fcd6d8680bd7d2660983e891) Add Bolt12 types and codecs (#2145)
- [86145a7](https://github.com/ACINQ/eclair/commit/86145a7470316f1e09901b729ed575039d1e32e4) Remove unused PerHopPayloadFormat traits (#2235)
- [9a31dfa](https://github.com/ACINQ/eclair/commit/9a31dfabea03bddf70e20628ae643efc78c873e7) Rename channel funder to initiator (#2236)
- [443266d](https://github.com/ACINQ/eclair/commit/443266d2b86a1be40e891dd10ad3436c793b5293) Add dual funding codecs and feature bit (#2231)
- [a4ddbc3](https://github.com/ACINQ/eclair/commit/a4ddbc33eafc64cf769cc9af474e714c61faaecb) Give best capacity score to local channels (#2239)
- [f099409](https://github.com/ACINQ/eclair/commit/f0994099e9bdbb862d1c6eeca93d220357859e27) Disable invoice purger in tests (#2240)
- [56c9e06](https://github.com/ACINQ/eclair/commit/56c9e0670ed18577f956f823bbaa90b7a64b7da0) Only define decode method in legacy codecs (#2241)
- [6958970](https://github.com/ACINQ/eclair/commit/69589706d3c79f162d43d5620795035c5bfdc26a) Fix flaky MempoolTxMonitor test (#2249)
- [74ef082](https://github.com/ACINQ/eclair/commit/74ef08294a992b646c7b67562aff2d8c5a5fd840) Use log probability in path-finding (#2257)
- [ee74f10](https://github.com/ACINQ/eclair/commit/ee74f10a8d956196dcc62ad297e83769aa7a2a82) Stop compressing with zlib (#2244)
- [1605c04](https://github.com/ACINQ/eclair/commit/1605c0435d579f00f8b134d499a8be01efebd7bb) Introduce `ChannelRelayParams` in the graph (#2264)
- [95213bc](https://github.com/ACINQ/eclair/commit/95213bcb1fc065beaec18294d4af1ea5a69bced6) Remove unused `LocalChannel` class in the router (#2265)
- [b691e87](https://github.com/ACINQ/eclair/commit/b691e876c589ab3fecd284e6599eb7673ec425c4) Fix flaky replaceable tx publisher tests (#2262)
- [10eb9e9](https://github.com/ACINQ/eclair/commit/10eb9e932f9c0de06cc8926230d8ad4e2d1d9e2c) Improve wallet double-spend detection (#2258)
- [8e2fc7a](https://github.com/ACINQ/eclair/commit/8e2fc7acd35ef9c9c1e6318913f37ae225828c5a) Run channel state tests sequentially (#2271)
- [81571b9](https://github.com/ACINQ/eclair/commit/81571b95e697fc748e565ad6ee6e27c9a03e753d) Fix flaky test PeerConnectionSpec (#2277)
- [bb7703a](https://github.com/ACINQ/eclair/commit/bb7703aa5d513e4697e0fd610d289365335baa23) Add simple integration test between `Channel` and `Router` (#2270)
- [03097b0](https://github.com/ACINQ/eclair/commit/03097b0d42b29346535c87480c9e990bcdc83203) Add `localChannelReserve` and `remoteChannelReserve` (#2237)
- [1e8791c](https://github.com/ACINQ/eclair/commit/1e8791c56ec4f6b3fc7ed5d4790463f7e61e6687) Update dual funding codecs (#2245)
- [6fa02a0](https://github.com/ACINQ/eclair/commit/6fa02a0ec18264d082dc6148fafb1ac22315aef6) Use long id instead of short id in `ChannelRelay` (#2221)
- [d98da6f](https://github.com/ACINQ/eclair/commit/d98da6f44f219831b630924cd2e09595d4db5f4f) Index unannounced channels on `channelId` instead of `shortChannelId` in the router (#2269)
- [44e7127](https://github.com/ACINQ/eclair/commit/44e71277a6f1f80d28c1c14cee2f974eb2e1a6db) (Minor) Postgres: use `write` instead of `writePretty` (#2279)
- [9c7ddcd](https://github.com/ACINQ/eclair/commit/9c7ddcd8745bbea9c7113457e1a1f378435347c7) Increase closed timeout from 10s to 1min (#2281)
- [45f19f6](https://github.com/ACINQ/eclair/commit/45f19f6287ae5809f5df6b4248ed148138d076f7) Fix test when run in Intellij (#2288) (#2289)
- [749d92b](https://github.com/ACINQ/eclair/commit/749d92ba39cc21f90a72aa9915ac62335397ade1) Add option to disable sending remote addr in init (#2285)
- [088de6f](https://github.com/ACINQ/eclair/commit/088de6f666e05438924838f9cc5d40744b316963) Don't RBF anchor if commit is confirmed (#2283)
- [ee46c56](https://github.com/ACINQ/eclair/commit/ee46c562812660f36524175aef6b993ff7ecfdf9) Remove dependency between dual funding and anchors (#2297)
- [9610fe3](https://github.com/ACINQ/eclair/commit/9610fe30e308af64fd30d0953ccf2eb0da69b67a) Define a proper base class for fixture tests (#2286)
- [47c5b95](https://github.com/ACINQ/eclair/commit/47c5b95eaa7ee9001c4853cf4e4b0269e685f77d) Drop support for Tor v2 hidden services (#2296)
- [ecbec93](https://github.com/ACINQ/eclair/commit/ecbec93dfe15a76eef94aa54783d908d2829fb44) Add traits for permanent channel features (#2282)
- [e08353b](https://github.com/ACINQ/eclair/commit/e08353b2432e6143ad18622689e99472dc9b4e2c) Remove close() in db interfaces (#2303)
- [c5620b2](https://github.com/ACINQ/eclair/commit/c5620b2d5613e40961eca701236a22ec256a9ca2) Log routing hint when finding a route (#2242)
- [340b568](https://github.com/ACINQ/eclair/commit/340b568d532249fd2d67dd567ad008d3300c83a6) (Minor) Test nits (#2306)
- [682e9bf](https://github.com/ACINQ/eclair/commit/682e9bf6a76473ae288f17ba8856e02e962c8b6f) Fix minimal node fixture flakyness (#2302)
- [4722755](https://github.com/ACINQ/eclair/commit/472275573cd4b9576d54a6e06373223877653554) Estimate balances of remote channels (#2272)
- [c4786f3](https://github.com/ACINQ/eclair/commit/c4786f3a7b9458ec62251f4ca1455fcc72a13b0c) Store disabled channel update when offline (#2307)
- [3e3f786](https://github.com/ACINQ/eclair/commit/3e3f786000acf4c339784a01ee11273997ad0c27) Allow disabling bitcoind requests batching (#2301)
- [e8c9df4](https://github.com/ACINQ/eclair/commit/e8c9df4ec48ec1746ffa727d17bb4563c517b25e) Add more GraphSpec tests (#2292) (#2293)
- [e5f5cd1](https://github.com/ACINQ/eclair/commit/e5f5cd152e9cb881395afb5a04bbf045c548611a) Add support for zero-conf and scid-alias (#2224)
- [f47c7c3](https://github.com/ACINQ/eclair/commit/f47c7c39faf23d4da3674aba2868829895fc29ca) Don't block when generating shutdown script (#2284)
- [bfba9e4](https://github.com/ACINQ/eclair/commit/bfba9e41193da5fbe76a7685485e131293f32789) (Minor) Include the discriminator in all channel codecs (#2317)
- [7630c51](https://github.com/ACINQ/eclair/commit/7630c5169c4f0c4c83eb48956ebf90b0769b3ee8) (Minor) Fix flaky `ZeroConfAliasIntegrationSpec` (#2319)
- [6882bc9](https://github.com/ACINQ/eclair/commit/6882bc9b2f9e0c351e59238e958e2f8b573430ea) Clean up scid parsing from coordinates string (#2320)
- [fc30eab](https://github.com/ACINQ/eclair/commit/fc30eab0a0bffa796da86458700535a4c635e9e8) Remove `channelId` from `PublicChannel` (#2324)
- [26741fa](https://github.com/ACINQ/eclair/commit/26741fabca6550b6becc8bbba563a7bd31f054fb) Reuse postgres instance in tests (#2313)
- [6b2e415](https://github.com/ACINQ/eclair/commit/6b2e415ecbbd1a602969efb327d9452458129090) Expose scraping endpoint for prometheus metrics (#2321)
- [70de271](https://github.com/ACINQ/eclair/commit/70de271312d629fd663417bbbe59d05e303b6975) Make actors signal when they are ready (#2322)
- [0c84063](https://github.com/ACINQ/eclair/commit/0c84063d324403bfdaaafed157325dcdf1e502c9) add arm64v8 Dockerfile (#2304)
- [af79f44](https://github.com/ACINQ/eclair/commit/af79f44051a08ecc31eab5a129d41e6c3100776a) Move arm64 docker file to contrib and udpate README.md (#2327)
- [08d2ad4](https://github.com/ACINQ/eclair/commit/08d2ad4a917aa31719ceb371dcf476a8adf650d7) Random values for `generateLocalAlias()` (#2337)
- [c9810d5](https://github.com/ACINQ/eclair/commit/c9810d54246648c6177dfb92559e7ff6c9676ad6) Resume reading after processing unknown messages (#2332)
- [e880b62](https://github.com/ACINQ/eclair/commit/e880b62c13dbc752e1758386c0ed39aa005728c6) Refactor routing hints to prepare for payments to blinded routes (#2315)
- [8af14ac](https://github.com/ACINQ/eclair/commit/8af14ac515f9c01a4b6709720aec1b778aabac90) Backport from feature branches (#2326)
- [6011c86](https://github.com/ACINQ/eclair/commit/6011c867cf54ba4d29297d4a51efd577004d085d) Additive per-node feature override (#2328)
- [a1f7c1e](https://github.com/ACINQ/eclair/commit/a1f7c1e74f993c1c3f1cbb7df479e873e80576e0) Return local channel alias in payment failures (#2323)
- [2790b2f](https://github.com/ACINQ/eclair/commit/2790b2ff6c792bdc8d06cd427068b0f4de1beb35) Activate 0-conf based on per-peer feature override (#2329)
- [2461ef0](https://github.com/ACINQ/eclair/commit/2461ef08cb7a688e1e48f9e37682b704f99f8ecb) Call `resolve()` on configuration (#2339)
- [3b97e44](https://github.com/ACINQ/eclair/commit/3b97e446aaeb50d56409a1fd5a09df7c8ed51117) Allow disabling no-htlc commitment fee-bump (#2246)
- [276340d](https://github.com/ACINQ/eclair/commit/276340d1b3722c01b5aab196ac8e45ff09e90e07) Add host metrics grafana dashboard (#2334)
- [3196462](https://github.com/ACINQ/eclair/commit/31964620bdcb519f2f4c9f8ebd2aeb381815f38f) Proper json serializer for `BlockHeight` (#2340)
- [e1dc358](https://github.com/ACINQ/eclair/commit/e1dc358c7957da1723860c2a450eb194b56d0d4e) Fix RoutingHeuristics.normalize (#2331)
- [c20b3c9](https://github.com/ACINQ/eclair/commit/c20b3c9e8bae0775a64a3eed2ab4628ad5a5903d) Remove redundant routing hint (#2349)
- [214873e](https://github.com/ACINQ/eclair/commit/214873e4a74837ed7244ad91eac59d83a8340758) Use the correct extra edges to put in metrics (#2350)
- [23de6c4](https://github.com/ACINQ/eclair/commit/23de6c4efd8cb61dbd1c5c801dd5205b657b089d) Fix flaky zeroconf test (#2352)
- [99d9bc1](https://github.com/ACINQ/eclair/commit/99d9bc181c47329cfd7813b29a7325866ced0b6c) Relay blinded payments (#2253)
- [4fd3d90](https://github.com/ACINQ/eclair/commit/4fd3d90961be4543d7aa234e52c7370e374e2c57) Emit event when receiving `Pong` (#2355)
- [b171a16](https://github.com/ACINQ/eclair/commit/b171a1690f1f6b2408f630cf781b1dc242197cf1) Remove payments overview DB (#2357)
- [8a42246](https://github.com/ACINQ/eclair/commit/8a42246b18fa96c5fee3cc7f947884d06f9e9fea) Update offers types (#2351)
- [85fea72](https://github.com/ACINQ/eclair/commit/85fea72720322d0682d163dc369b5afb0ea5f967) Broadcast commit tx when nothing at stake (#2360)
- [c71c3b4](https://github.com/ACINQ/eclair/commit/c71c3b40465a6fadc8a5cca982a5b466fd0aedfc) Make `htlc_maximum_msat` mandatory in channel updates (#2361)
- [9f9cf9c](https://github.com/ACINQ/eclair/commit/9f9cf9ce0ee9699bcc3443b7b1a72cbc5f0483d2) (Minor) Test improvements (#2354)
- [f49eb0e](https://github.com/ACINQ/eclair/commit/f49eb0effea9808e92f16eeea13b97b1554952f7) Update sqlite to 3.39.2.0 (#2369)
- [0310bf5](https://github.com/ACINQ/eclair/commit/0310bf5dc442d810636059fc371a970e6688f2f1) Implement first steps of the dual funding flow (#2247)
- [de1ac34](https://github.com/ACINQ/eclair/commit/de1ac34d4605e3f691cf15b2fe53df2487a1e8ff) Add explicit duration to ExcludeChannel (#2368)
- [2f590a8](https://github.com/ACINQ/eclair/commit/2f590a80e22a6ad0c5dc0b6bd5540dc144042654) Refactor routing hint failure updates (#2370)
- [e8dda28](https://github.com/ACINQ/eclair/commit/e8dda28eebef39ddbf888a1393480a3f1ab8fb7b) Remove invalid channel updates from DB at startup (#2379)
- [a13c3d5](https://github.com/ACINQ/eclair/commit/a13c3d5d6d5a67448b22d0e766b9959fcf9ee636) Prune channels if any update is stale (#2380)
- [5f4f720](https://github.com/ACINQ/eclair/commit/5f4f72031f1b6c8fca7415b1fd09398dd656662d) Remove legacy force-close htlc matching (#2376)
- [33e6fac](https://github.com/ACINQ/eclair/commit/33e6fac97bde29188acbaeacf98da9a66b218fe7) Implement the interactive-tx protocol (#2273)
- [bb6148e](https://github.com/ACINQ/eclair/commit/bb6148e31cdce5162d44922d9dfb136e02c4c0d8) Support DNS hostnames in node announcements (#2234)
- [285fe97](https://github.com/ACINQ/eclair/commit/285fe97a775b24ba5fe591ca854f8ebb8232260a) Fix blinded path amount to forward calculation (#2367)
- [4e3b377](https://github.com/ACINQ/eclair/commit/4e3b3774c341b30ccabd762ff962c7938fe1594a) Update route blinding test vectors (#2075)
- [8f2028f](https://github.com/ACINQ/eclair/commit/8f2028f600526ee370ed27260b0d0c4feeea091c) Limit default `from` and `to` API parameters (#2384)
- [323aeec](https://github.com/ACINQ/eclair/commit/323aeec09c57194a669da0fec649f9509de850e0) Add support for the `signet` test network (#2387)
- [a97e88f](https://github.com/ACINQ/eclair/commit/a97e88fae19b806d5b81f4956acc388f4490f3d5) Dual funding channel confirmation (#2274)
- [ad19a66](https://github.com/ACINQ/eclair/commit/ad19a665a14ce7d1b81a20a05e1ee255ec9c9916) Unlock utxos during dual funding failures (#2390)
- [a735ba8](https://github.com/ACINQ/eclair/commit/a735ba86b672e73005604b02dfe98c433dd74725) Dual funding RBF support (#2275)
- [278f391](https://github.com/ACINQ/eclair/commit/278f391a54f8fe33aedce6b9cca885612f853457) Add akka metrics grafana dashboard (#2347)
- [a4faa90](https://github.com/ACINQ/eclair/commit/a4faa908a168cf23d4a5a1f1a7c020b1c6b5c1e2) Add eclair metrics grafana dashboard (#2343)
- [fb6eb48](https://github.com/ACINQ/eclair/commit/fb6eb485c411662357ad942109b91c8b0d965c49) Fix dual funding flaky test (#2392)
- [c1daaf3](https://github.com/ACINQ/eclair/commit/c1daaf3acdac1ff2ba1ecff6139019bdc8e68106) Check input out of bounds early in `tx_add_input` (#2393)
- [81af619](https://github.com/ACINQ/eclair/commit/81af6192a848273f7e043d24116452a5622c14d3) (Minor) Ignore line ending when comparing strings in tests (#2403)
- [40b2d44](https://github.com/ACINQ/eclair/commit/40b2d440296b86303b0439c6f3fc019fe69a526a) Remove support for legacy Sphinx payloads (#2190)
- [3568dd6](https://github.com/ACINQ/eclair/commit/3568dd6c17cd6d3321e023c974b67e93c77669fc) Rework log capture in tests (#2409)
- [ee1136c](https://github.com/ACINQ/eclair/commit/ee1136c040c02f3115b4240e59c6fced9526a7a1) Assume sent htlcs will succeed in the balance computation (#2410)
- [611b796](https://github.com/ACINQ/eclair/commit/611b79635ea757885124ca0e6d796c0e1ab6dccf) More lenient `interactive-tx` RBF validation (#2402)
- [065cb28](https://github.com/ACINQ/eclair/commit/065cb28aaedf47dfcc31b657cd98d226570b97b4) Add option to require confirmed inputs in interactive-tx (#2406)
- [37863a4](https://github.com/ACINQ/eclair/commit/37863a41d76d2e6bdbea41bc23d7d50dd5c7f59f) Use deterministic serial IDs in interactive-tx (#2407)
- [7c8a777](https://github.com/ACINQ/eclair/commit/7c8a777572e149556a0b224d19ec83315912837a) Add a `ChannelOrigin` placeholder (#2411)
- [40b83a7](https://github.com/ACINQ/eclair/commit/40b83a7eed79305f98c476fa410b0ff38a39f8e4) Nits (#2413)
- [517c0fd](https://github.com/ACINQ/eclair/commit/517c0fde6ea73f0da022be1e4ca0a434ede672e5) Fix collision bug in local alias generation (#2415)
- [59f6cda](https://github.com/ACINQ/eclair/commit/59f6cdad4cb9dcb0aaa93376f9aacf7a31d7f238) Separate tlv decoding from content validation (#2414)
- [09f1940](https://github.com/ACINQ/eclair/commit/09f1940333fa0b62084fdb342ad47f4c3b893cbc) Implement latest route blinding spec updates (#2408)
- [9d17b1d](https://github.com/ACINQ/eclair/commit/9d17b1dfc13ef03fcba876f3eb134123a233b62b) Receive payments for Bolt 12 invoices (#2416)
- [3191878](https://github.com/ACINQ/eclair/commit/319187868587b99ea63ec5d7daf6ea08d0fe98c5) Allow receiving to blinded routes (#2418)
- [ba2b928](https://github.com/ACINQ/eclair/commit/ba2b928ead450bb4232b8a7765ae3e3b2b40dff4) Add tests for invoice feature validation (#2421)
- [b00a0b2](https://github.com/ACINQ/eclair/commit/b00a0b2004d957d5296d360f20b3307194919862) Don't set channel reserve for dual funded channels (#2430)
- [3fdad68](https://github.com/ACINQ/eclair/commit/3fdad68a5fc88e81be74fabb716a0c692b9a13fb) Remove `channelReserve.getOrElse(0 sat)` (#2432)
- [1b0ce80](https://github.com/ACINQ/eclair/commit/1b0ce802eca4fd0247c3fc5fdb76f7438e9093a0) Add support for push amount with dual funding (#2433)
- [1b36697](https://github.com/ACINQ/eclair/commit/1b366978022c154cc07bc904c303212eab2ccb50) Fix duplicate SLF4J loggers in tests (#2436)
- [afdaf46](https://github.com/ACINQ/eclair/commit/afdaf4619d9510a5f064b9cd9c308ffbf43a4878) Add initial support for async payment trampoline relay (#2435)
- [6e381d4](https://github.com/ACINQ/eclair/commit/6e381d4004f4b95d0ad6dc2bc8185a655471d1a0) Fix availableForSend fuzz tests (#2440)
- [0de91f4](https://github.com/ACINQ/eclair/commit/0de91f45231ea3f81a64c69bd0ed820570ea1732) Bump scala-library from 2.13.8 to 2.13.9 (#2444)
- [06ead3c](https://github.com/ACINQ/eclair/commit/06ead3cf28c299825e87bc579f0b0d3146891462) Add tlvField codec (#2439)
- [ca68695](https://github.com/ACINQ/eclair/commit/ca6869530e2a118305c48984e1d610658b15a660) Validate payment using minFinalExpiryDelta from node params (#2448)
- [dad0a51](https://github.com/ACINQ/eclair/commit/dad0a51b7cb24978bff23b6deff7f7e1aa023d84) Complete codec for TLV fields (#2452)
- [3b12475](https://github.com/ACINQ/eclair/commit/3b12475794e6d9d3d71cdb751de6cc1c12c9755c) Make payment secret not optional (#2457)
- [a0433aa](https://github.com/ACINQ/eclair/commit/a0433aa0c027c9be618c5afe18e7f91642a7f372) Fix flaky CommitmentsSpec test (#2458)
- [c1a925d](https://github.com/ACINQ/eclair/commit/c1a925db11ee1aa141ad41cac7efe5760dfa0871) Use 0-conf based on local features only (#2460)
- [dd3d694](https://github.com/ACINQ/eclair/commit/dd3d694b60218e2af690368977e9f41d464f8939) Remove onion message size limit (#2459)
- [1f32652](https://github.com/ACINQ/eclair/commit/1f32652465f8fccbc51f283aa8f23bb59b1b9685) Add tlv to require confirmed inputs for dual funding (#2461)
- [5e85c7c](https://github.com/ACINQ/eclair/commit/5e85c7ce012515f66a8053bf9217a09e2f38b4de) Fix pruned channels coming back online (#2456)
- [a11984b](https://github.com/ACINQ/eclair/commit/a11984bf3f54bfe1f8f94203ec617f5f8707e8db) Use `tx_hash` in dual funding and verify `nSequence` (#2463)
- [7c8bdb9](https://github.com/ACINQ/eclair/commit/7c8bdb959bde5c496e91c747f537db1c985a32fd) Explain how and why we use bitcoin core (#2473)
- [21c6278](https://github.com/ACINQ/eclair/commit/21c6278305bdb773ec8e0360054ae83053b6d58a) Only use txid in error messages (#2423)
- [c385ca2](https://github.com/ACINQ/eclair/commit/c385ca225fc82fab40aacb41200b273cddd1360f) Add support for arbitrary length onion errors (#2441)
- [a8389a0](https://github.com/ACINQ/eclair/commit/a8389a042a149b3b126c617e577a917ffb48448e) Add private flag to channel updates (#2362)
- [31f61c1](https://github.com/ACINQ/eclair/commit/31f61c1ca5e06dfc4476f6cc3b74886ad9de2a56) Randomize final cltv expiry (#2469)
- [a566d53](https://github.com/ACINQ/eclair/commit/a566d53a6e6692f0acef91327012881f06c2b598) Allow nodes to overshoot final htlc amount and expiry (#2468)
- [0906804](https://github.com/ACINQ/eclair/commit/090680403a9856c6f754e8d42a5bc1831fc95eec) Support `scid_alias` and `zero_conf` for all commitment types (#2404)
- [9ae4dea](https://github.com/ACINQ/eclair/commit/9ae4dea03e91adf265387aec07073f0d6ad93ae5) Reduce diff with feature branch (#2476)
- [8f1af28](https://github.com/ACINQ/eclair/commit/8f1af2851dd2a388b91c0d900b27fe65d51ed0ab) Add pagination on listInvoices (#2474)
- [7dbaa41](https://github.com/ACINQ/eclair/commit/7dbaa41f39e6ce34bce013ca33eae83abfcf610a) Update to bitcoind 23.x (#2466)
- [6eba3e7](https://github.com/ACINQ/eclair/commit/6eba3e76da1c1d3c7a16b1236d932217a8e70cc2) Fix static default `from` and `to` pagination (#2484)
- [003aae0](https://github.com/ACINQ/eclair/commit/003aae053ae52ff06c0c13913fea007ab246fec7) Explicitly request bech32 static remotekey (#2486)
- [37a3911](https://github.com/ACINQ/eclair/commit/37a3911015e7dae5516eb7d3d8830cd45b6cb1fd) Fix circular dependency (#2485)
- [1e252e5](https://github.com/ACINQ/eclair/commit/1e252e56c7718675fec210fe0fe9c847e9ed697a) Make `eclair-cli -s` display short channel ids correctly (#2488)
- [6d3991b](https://github.com/ACINQ/eclair/commit/6d3991bc74d7f73c1ae2cc4cce06f7dc3a0210f0) Accept payments to zero-hop blinded route (#2500)
- [e32697e](https://github.com/ACINQ/eclair/commit/e32697e79806dcdd0dcc1e4b92da949a11c81414) Build Bolt12 invoices with provided intermediary nodes (#2499)
- [6569eaf](https://github.com/ACINQ/eclair/commit/6569eaf07dcf0768ea09e5eb0ce2f69d8ecbbdd5) Cancel previous `LiftChannelExclusion` (#2510)
- [8afcb46](https://github.com/ACINQ/eclair/commit/8afcb467912f6289156bc7bd0090cd64bef96396) Fix closing channel state when nothing at stake (#2512)
- [731a7ee](https://github.com/ACINQ/eclair/commit/731a7eedf6259456800e7c5264cdd88aff09d071) Implement correct ordering of `tx_signatures` (#2501)
- [5b0aa35](https://github.com/ACINQ/eclair/commit/5b0aa35c8dd5164885c99a2e429f896076bd36be) Create routes to self using remote alias (#2507)
- [a230395](https://github.com/ACINQ/eclair/commit/a230395f5f398043068c49a15b2c679770455e89) Test change address type generation (#2513)
- [77b9aa0](https://github.com/ACINQ/eclair/commit/77b9aa080be0c1dc7fbaa300ae1c2583a6a2e9c4) Remove warning for pruned local channel update (#2514)
