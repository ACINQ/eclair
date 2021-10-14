# Eclair v0.6.2

This releases fixes a known vulnerability, makes several performance improvements, includes a few bug fixes and many new features.
It is fully compatible with 0.6.1 (and all previous versions of eclair).

This release requires a few actions from node operators when upgrading: make sure you read the release notes carefully!

## Major changes

### ZMQ changes

Eclair previously used ZMQ to receive full blocks from Bitcoin Core.
In this release, we instead switch to receive only block hashes over ZMQ.
This will save bandwidth and improve support for deployments with a remote Bitcoin Core node.

:warning: When updating eclair, you need to update your `bitcoin.conf` to have your Bitcoin Core node send block hashes via ZMQ.

The previous configuration was:

```conf
zmqpubrawblock=tcp://127.0.0.1:29000
```

You must remove that line from your `bitcoin.conf` and replace it with:

```conf
zmqpubhashblock=tcp://127.0.0.1:29000
```

### Per node relay fees

Relay fees are now set per node instead of per channel:

- If you set the relay fees for a node with the `updaterelayfee` API, all new channels with this node will use these fees.
- Otherwise the default relay fees set in `eclair.conf` will be used: this means that changing `eclair.conf` will update the fees for all channels where the fee was not manually set.

Note that you can use the `updaterelayfee` API *before* opening a channel to ensure that the channel doesn't use the default relay fees from `eclair.conf`.

The config for default fees has also be changed to allow different default fees for announced/unannounced channels: `fee-base-msat`/`fee-proportional-millionths` are now nested inside `relay.fees.public-channels`/`relay.fees.private-channels`.

:warning: When updating eclair, the relay fees for your existing channels will be reset to the value from your `eclair.conf`. You should use the `updaterelayfee` API to reconfigure relay fees if you don't want to use the default fees for every node you're connected to.

### Beta support for anchor outputs

Anchor outputs is still disabled by default, but users willing to try it can activate it by adding the following line to `eclair.conf`:

```conf
eclair.features.option_anchors_zero_fee_htlc_tx = optional
```

Once activated, eclair will keep the commitment feerate below 10 sat/byte regardless of the current on-chain feerate and will not close channels when there is a feerate mismatch between you and your peer.

You can modify that threshold by setting `eclair.on-chain-fees.feerate-tolerance.anchor-output-max-commit-feerate` in your `eclair.conf`.
Head over to [reference.conf](https://github.com/ACINQ/eclair/blob/master/eclair-core/src/main/resources/reference.conf) for more details.

In case the channel is unilaterally closed, eclair will use CPFP and RBF to ensure that transactions confirm in a timely manner.
You **MUST** ensure you have some utxos available in your Bitcoin Core wallet for fee bumping, otherwise there is a risk that an attacker steals some of your funds.

Do note that anchor outputs may still be unsafe in high-fee environments until the Bitcoin network provides support for [package relay](https://bitcoinops.org/en/topics/package-relay/).

### Configurable dust tolerance

Dust HTLCs are converted to miner fees when a channel is force-closed and these HTLCs are still pending.
This can be used as a griefing attack by malicious peers, as described in [CVE-2021-41591](https://cve.mitre.org/cgi-bin/cvename.cgi?name=CVE-2021-41591).

Node operators can now configure the maximum amount of dust HTLCs that can be pending in a channel by setting `eclair.on-chain-fees.feerate-tolerance.dust-tolerance.max-exposure-satoshis` in their `eclair.conf`.

Choosing the right value for your node involves trade-offs.
The lower you set it, the more protection it will offer against malicious peers.
But if it's too low, your node may reject some dust HTLCs that it would have otherwise relayed, which lowers the amount of relay fees you will be able to collect.

Another related parameter has been added: `eclair.on-chain-fees.feerate-tolerance.dust-tolerance.close-on-update-fee-overflow`.
When this parameter is set to `true`, your node will automatically close channels when the amount of dust HTLCs overflows your configured limits.
This gives you a better protection against malicious peers, but may end up closing channels with honest peers as well.
This parameter is deactivated by default and unnecessary when using `option_anchors_zero_fee_htlc_tx`.

Note that you can override these values for specific peers, thanks to the `eclair.on-chain-fees.override-feerate-tolerance` mechanism.
You can for example set a high `eclair.on-chain-fees.feerate-tolerance.dust-tolerance.max-exposure-satoshis` with peers that you trust.

Note that if you were previously running eclair with the default configuration, your exposure to this issue was quite low because the default `max-accepted-htlc` is set to 30.
With an on-chain feerate of `10 sat/byte`, your maximum exposure would be ~70 000 satoshis per channel.
With an on-chain feerate of `5 sat/byte`, your maximum exposure would be ~40 000 satoshis per channel.

### Path-finding improvements

This release contains many improvements to path-finding and paves the way for future experimentation.

A noteworthy addition is a new heuristic that can be used to penalize long paths by setting a virtual cost per additional hop in the route (#1815). This can be freely configured by node operators by setting fields in the `eclair.router.path-finding.default.hop-cost` section.

We also added support for A/B testing to experiment with various configurations of the available heuristics.
A/B testing can be activated directly from `eclair.conf`, by configuring some `experiments`, for example:

```conf
eclair.router.path-finding.experiments {
  control = ${eclair.router.path-finding.default} {
    percentage = 75 // 75% of the traffic will use the default configuration
  }

  use-shorter-paths = ${eclair.router.path-finding.default} {
    percentage = 25 // 25% of the traffic will use this custom configuration
    ratios {
      base = 1
      cltv = 0
      channel-age = 0
      channel-capacity = 0
    }
    hop-cost {
      // High hop cost penalizes strongly longer paths
      fee-base-msat = 10000
      fee-proportional-millionths = 10000
    }
  }
}
```

Have a look at [reference.conf](https://github.com/ACINQ/eclair/blob/master/eclair-core/src/main/resources/reference.conf) for more examples.

You can also force a specific payment to use an experimental path-finding configuration by specifying the experiment name in the various path-finding APIs:

```sh
eclair-cli payinvoice --invoice=<xxx> --pathFindingExperimentName=use-shorter-paths
```

The results are stored in the `audit` database, inside the `path_finding_metrics` table.
You can then analyze the results after sending a large enough number of payments to decide what configuration yields the best results for your usage of lightning.

### Tor support for blockchain watchdogs

Eclair introduced blockchain watchdogs in v0.5.0, where secondary blockchain sources are regularly queried to detect whether your node is being eclipsed.

Most of these watchdogs were previously queried over HTTPS, which exposes your IP address.
This is fixed in this release: when using Tor, the watchdogs will now also be queried through Tor, keeping your IP address private.

You can also now choose to disable some watchdogs by removing them from the `eclair.blockchain-watchdog.sources` list in `eclair.conf`.
Head over to [reference.conf](https://github.com/ACINQ/eclair/blob/master/eclair-core/src/main/resources/reference.conf) for more details.

### Dust limit thresholds

Eclair can now use dust limits as low as 354 satoshis.
This value covers all current and future segwit versions, while ensuring that transactions can relay according to default bitcoin network policies.

With this change, we also disallow non-segwit scripts when closing a channel.
We still support receiving non-segwit remote scripts, but will force-close if the resulting mutual close transaction would be invalid according to default network policies.

See the [spec discussions](https://github.com/lightningnetwork/lightning-rfc/pull/894) for more details.

### Audit trail for published transactions

Eclair now records every transaction it publishes in the `audit` database, in a new `transactions_published` table.
It also stores confirmed transactions that have an impact on existing channels (including transactions made by your peer) in a new `transactions_confirmed` table.

This lets you audit the complete on-chain footprint of your channels and the on-chain fees paid.
This information is exposed through the `networkfees` API (which was already available in previous versions).

We removed the previous `network_fees` table which achieved the same result but contained less details.

### Sample GUI removed

We previously included code for a sample GUI: `eclair-node-gui`.
This GUI was only meant to be used for demo purposes, not for mainnet node administration.

However some users were using it on mainnet, which lead to several issues (e.g. channel closure and potentially loss of funds).
We completely removed it from this release to prevent it from happening again.

### API changes

This release contains many API updates:

- `open` lets you specify the channel type through the `--channelType` parameter, which can be one of `standard`, `static_remotekey`, `anchor_outputs` or `anchor_outputs_zero_fee_htlc_tx` (#1867)
- `open` doesn't support the `--feeBaseMsat` and `--feeProportionalMillionths` parameters anymore: you should instead set these with the `updaterelayfee` API, which can now be called before opening a channel (#1890)
- `updaterelayfee` must now be called with nodeIds instead of channelIds and will update the fees for all channels with the given node(s) at once (#1890)
- `close` lets you specify a fee range when using quick close through the `--preferredFeerateSatByte`, `--minFeerateSatByte` and `--maxFeerateSatByte` (#1768)
- `close` now rejects non-segwit `scriptPubKey`
- `createinvoice` now lets you provide a `--descriptionHash` instead of a `--description` (#1919)
- `sendtonode` doesn't support providing a `paymentHash` anymore since it uses `keysend` to send the payment (#1840)
- `payinvoice`, `sendtonode`, `findroute`, `findroutetonode` and `findroutebetweennodes` let you specify `--pathFindingExperimentName` when using path-finding A/B testing (#1930)
- the `--maxFeePct` parameter used in `payinvoice` and `sendtonode` must now be an integer between 0 and 100: it was previously a value between 0 and 1, which was misleading for a percentage (#1930)
- `findroute`, `findroutetonode` and `findroutebetweennodes` let you choose the format of the route returned with the `--routeFormat` parameter (supported values are `nodeId` and `shortChannelId`) (#1943)
- `findroute`, `findroutetonode` and `findroutebetweennodes` now accept `--includeLocalChannelCost` to specify if you want to count the fees from your node like trampoline payments do (#1942)

Have a look at our [API documentation](https://acinq.github.io/eclair) for more details.

### Miscellaneous improvements and bug fixes

- Eclair nodes may now use different relay fees for unannounced channels (#1893)
- Relay fees are now set per node and automatically apply to all channels with that node (#1890)
- Eclair now supports [explicit channel type negotiation](https://github.com/lightningnetwork/lightning-rfc/pull/880)
- Eclair now supports [quick close](https://github.com/lightningnetwork/lightning-rfc/pull/847), which provides more control over what feerate will be used when closing channels

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

That should generate `eclair-node/target/eclair-node-0.6.2-XXXXXXX-bin.zip` with sha256 checksums that match the one we provide and sign in `SHA256SUMS.asc`

(*) You may be able to build the exact same artefacts with other operating systems or versions of JDK 11, we have not tried everything.

## Upgrading

This release is fully compatible with previous eclair versions. You don't need to close your channels, just stop eclair, upgrade and restart.

## Changelog

- [cafaeed](https://github.com/ACINQ/eclair/commit/cafaeedef46a3a51ade12c33eb53c76f1da627c3) Set version to 0.6.2-SNAPSHOT (#1888)
- [6d4da40](https://github.com/ACINQ/eclair/commit/6d4da4072c6611f9d941afdd48abbeda5dc3527f) Different default relay fees for announced and unannounced channels (#1893)
- [2d68bab](https://github.com/ACINQ/eclair/commit/2d68bab3032637124aa925eaa85e5a1e3c19196e) Fix API substream materialization issues (#1884)
- [131ae8b](https://github.com/ACINQ/eclair/commit/131ae8b11d1c1d1e353b405aeb96ba37799d6f72) Balance: do not deduplicate identical htlcs amounts (#1894)
- [8f5f6ac](https://github.com/ACINQ/eclair/commit/8f5f6ace540df0fdee2d67ce0ca7c023a8d3ada7) Set relay fees per node and save them to database (#1890)
- [a342717](https://github.com/ACINQ/eclair/commit/a3427172b7045d2bc79efb155cbff53d34da38c9) Retry local channel failures in trampoline payments (#1899)
- [19f4d1f](https://github.com/ACINQ/eclair/commit/19f4d1f9e106f278d13ddcd231529fd31dfb6f95) Refactor db migration (#1901)
- [9f2b036](https://github.com/ACINQ/eclair/commit/9f2b0368c70e62351ff880044a6a0af946fdaa72) Rename channel type traits (#1909)
- [4cde8c5](https://github.com/ACINQ/eclair/commit/4cde8c555fe2a16f98fb1ba412a031229c604cc9) Handle shutdown retransmit when negotiating (#1902)
- [aab83fd](https://github.com/ACINQ/eclair/commit/aab83fdfbcb334014aa442fc256711b91a0a6700) Don't add channel update to auditDb if it hasn't changed (#1906)
- [ebed5ad](https://github.com/ACINQ/eclair/commit/ebed5ad9ea27771f48d871e0ff5bf5780bd1925c) Add cost per hop and base weight ratio (#1815)
- [49e1996](https://github.com/ACINQ/eclair/commit/49e19963910a62a6c0e76d7b19f576aeb498e4af) MPP scale min part amount based on total amount (#1911)
- [9a0fc14](https://github.com/ACINQ/eclair/commit/9a0fc1471031c75cae1b5964c49deb9c56a25931) Nicer feerate string representation (#1908)
- [c504658](https://github.com/ACINQ/eclair/commit/c5046582649832248bc858522e4ebc18502e5759) Better handling of remote commit confirmation in TxPublisher (#1905)
- [759c87f](https://github.com/ACINQ/eclair/commit/759c87fc093c3e9f59138cb8502b438d03dde14d) Add advanced configuration details in README.md (#1915)
- [fc36321](https://github.com/ACINQ/eclair/commit/fc36321403f4d463070ce12aa49be03ebf94fe7c) Add TlvStream to all lightning messages (#1891)
- [07b022e](https://github.com/ACINQ/eclair/commit/07b022e7fbeef22048dca2f94100822b107c4efc) Split `SendPayment` in `SendPaymentToRoute` and `SendPaymentToNode` (#1921)
- [92091a1](https://github.com/ACINQ/eclair/commit/92091a1504ff2eaa00d6a8726ea50ffe47049fff) Fix ZmqWatcher flaky test (#1925)
- [d53f57f](https://github.com/ACINQ/eclair/commit/d53f57fed98bf52477f9ae257d1d5777295a531d) Switch ZMQ to block hash and improve resiliency (#1910)
- [275581d](https://github.com/ACINQ/eclair/commit/275581df9629eded8a1a78afe92ebf5641637080) Make route params explicit (#1923)
- [59ccf34](https://github.com/ACINQ/eclair/commit/59ccf3427ac8cc5b108b5f1e6cf56139415e8ed0) Explicit channel type in channel open (#1867)
- [54fa208](https://github.com/ACINQ/eclair/commit/54fa208c7da9c9a789c3a6d1cd5f4126753645b0) Add validation on the recid in `verifymessage` (#1928)
- [bca2a83](https://github.com/ACINQ/eclair/commit/bca2a8321816d23f05fef3dcd71b7ce4708f187c) Tor support for blockchain watchdogs (#1907)
- [d11765c](https://github.com/ACINQ/eclair/commit/d11765cfdad823951bfdffec507ee46e167f06b2) Add description_hash in createinvoice (#1919)
- [118285f](https://github.com/ACINQ/eclair/commit/118285f4a09b035d7dd8a1b80406dd72651235f9) Gracefully release Postgres lock on shutdown (#1912)
- [4f93734](https://github.com/ACINQ/eclair/commit/4f93734fe3783a9d011f3f17fd7d8478e5f1bf27) Add warning about GUI deprecation (#1929)
- [9f9f10e](https://github.com/ACINQ/eclair/commit/9f9f10e911eb258ad26476d22fc59e0a91ddcd0a) Conversion nits (#1937)
- [daace53](https://github.com/ACINQ/eclair/commit/daace535c403a1f7c467861bf237f13fd57d67b9) Dedicated event for `channel_update` modifications (#1935)
- [663094e](https://github.com/ACINQ/eclair/commit/663094e0bf5d10724d45815c10743fac181e0e38) More flexible mutual close fees (#1768)
- [632d40c](https://github.com/ACINQ/eclair/commit/632d40c27030a848419819e824f9f9a6249693a1) Add `AbstractChannelRestored` event trait (#1927)
- [88f0dfd](https://github.com/ACINQ/eclair/commit/88f0dfd2251d901d28c90de0c2ce54f703783c60) Make publising of onion addresses configurable (#1936)
- [6c546f0](https://github.com/ACINQ/eclair/commit/6c546f06c04014292f038ac234b4339ebfb91250) Remove `messageFlags` from `ChannelUpdate` (#1941)
- [768a745](https://github.com/ACINQ/eclair/commit/768a74558f184477bb0ca30670407178f179b0a1) AB testing (#1930)
- [64f33ba](https://github.com/ACINQ/eclair/commit/64f33bada40c877632e19a7f82a25ac0d2537ef5) Fix isNode1 in tests (#1944)
- [24dd613](https://github.com/ACINQ/eclair/commit/24dd6136f7fd1bac8c5b848ef18e89211001481d) Fix the build (#1945)
- [a228bac](https://github.com/ACINQ/eclair/commit/a228baca71cb84c20b2b1d5e3f5f876f4546c0ca) Implement anchor outputs zero fee htlc txs (#1932)
- [03ac320](https://github.com/ACINQ/eclair/commit/03ac320f21cfa84fa0b1b6f1c8f450d5fbe6c9eb) Add 'shortChannelId' output format for findroute* API calls (#1943)
- [fb0199c](https://github.com/ACINQ/eclair/commit/fb0199c0690fda00fc7e40e0dba65ed11f793c50) Update Bolt 11 official test vectors (#1870)
- [5b7a474](https://github.com/ACINQ/eclair/commit/5b7a474b6a758aab00bbb651c141eb5c7045d04f) Clean up inconsistency between bitcoin client and wallet (#1939)
- [e93110b](https://github.com/ACINQ/eclair/commit/e93110b25427b2105836508922754d05a94621f7) Use Github discussions instead of Gitter (#1954)
- [8b29edb](https://github.com/ACINQ/eclair/commit/8b29edb58db29e559a644707601df475130da025) Add release notes in the repository (#1951)
- [273fae9](https://github.com/ACINQ/eclair/commit/273fae9135f3d54bc49584d9683fec2d1c26d01c) Add success probabilities in path finding (#1942)
- [c846781](https://github.com/ACINQ/eclair/commit/c846781192148b46728a8d88f42b77445e567987) Make Tor optional for blockchain watchdogs (#1958)
- [5686ad0](https://github.com/ACINQ/eclair/commit/5686ad013c2cd7b810a81a9590b7f1761b1e676f) Minor changes and refactoring (#1965)
- [467a0bc](https://github.com/ACINQ/eclair/commit/467a0bc82975f354143a52504c6fcabe273cf031) Count local fees in path finding metrics (#1963)
- [d5c0c73](https://github.com/ACINQ/eclair/commit/d5c0c73921ddc4d6e2f124f0d2a393d99bd59f2f) Clarify Bitcoin Core supported versions (#1960)
- [5fc980c](https://github.com/ACINQ/eclair/commit/5fc980c8d9b4a92ea89df8500c281dfc6810a0b3) Lower minimum remote dust limit (#1900)
- [6dc836d](https://github.com/ACINQ/eclair/commit/6dc836daa36fec3ccc9496d682083b2a65ab998d) Ignore channels without capacity (#1975)
- [97393b1](https://github.com/ACINQ/eclair/commit/97393b13b43d60887eef3b747ceb86f2e9938d2b) Fix race condition in 'stream updates to front' test (#1978)
- [fd56504](https://github.com/ACINQ/eclair/commit/fd565040d367445a07b82ef7e8287fad7dd61464) Remove the GUI (#1981)
- [3295881](https://github.com/ACINQ/eclair/commit/3295881e485018439917942134c068b3e6b4b4c3) Json serializers refactoring (#1979)
- [73744ee](https://github.com/ACINQ/eclair/commit/73744ee44011d84732c40325c2b3e469326d0f58) Move path-finding examples to documentation (#1983)
- [d0be2cf](https://github.com/ACINQ/eclair/commit/d0be2cf6e1fc6fed19c342f2469245704d460258) Log payment failure summary (#1966)
- [c803da6](https://github.com/ACINQ/eclair/commit/c803da670cf7a161c126e4ca0792697902b68cc7) Store published txs in AuditDb (#1976)
- [d6b46ae](https://github.com/ACINQ/eclair/commit/d6b46aed4ddf7d9c012978b6fd2d0d1ae0b8ccb1) Update anchor outputs feerate tolerance (#1980)
- [0621ccf](https://github.com/ACINQ/eclair/commit/0621ccfe0c3957f7c861b06d69355a20db16caa3) Fix ZmqWatcher block timeout (#1989)
- [bb5e6df](https://github.com/ACINQ/eclair/commit/bb5e6df186faec474e3cf2589521097df5532f21) Fix remote upfront script codec (#1991)
- [75eafd0](https://github.com/ACINQ/eclair/commit/75eafd0e4d4d93ac51e40a56c85e9df87a47d391) Configure dust in flight threshold (#1985)
