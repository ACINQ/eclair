# Eclair v0.11.0

This release adds official support for Bolt 12 offers and makes progress on liquidity management features (splicing, liquidity ads, on-the-fly funding).
We also stop accepting channels that don't support anchor outputs and update our dependency on Bitcoin Core.

We're still actively working with other implementations to finalize the specification for some of these experimental features.
You should only activate them if you know what you're doing, and are ready to handle backwards-incompatible changes!

This release also contains various performance improvements, more configuration options and minor bug fixes.

## Major changes

### Bolt 12

After years of development, [Bolt 12](https://github.com/lightning/bolts/pull/798) was finally accepted to the BOLTs!
Eclair had always stayed up-to-date with specification changes: this release thus contains full support for Bolt 12 payments.

If you'd like to pay a Bolt 12 offer, simply use the `payoffer` API:

```sh
./eclair-cli payoffer --offer=<lno...> --amountMsat=<amount> --blocking=true
```

If you want to generate a Bolt 12 offer, this is slightly more complicated.
Bolt 12 uses [blinded paths](https://github.com/lightning/bolts/pull/765) to guarantee that your identity doesn't leak when receiving payments.
However, we haven't yet figured out how to create default blinded paths that work for any topology, so instead of providing unsafe defaults, we let node operators dynamically configure their blinded paths using plugins.
The [bolt 12 tipjar plugin](https://github.com/ACINQ/eclair-plugins/tree/master/bolt12-tip-jar) is a good example of how to configure your node.

We will add more API support in the next versions of eclair to simplify this process for standard use-cases.

### Splicing

We've improved our implementation of [splicing](https://github.com/lightning/bolts/pull/1160), by relying on the now official [quiescence feature](https://github.com/lightning/bolts/pull/869) and adding RBF support (#2887).

We're still using experimental feature bits and messages in this release, as we're missing support for splicing on public channels and are waiting for other implementations to be ready for cross-compatibility tests.
We hope that splicing will officially be supported in the next release.
Meanwhile, you can experiment with splicing between eclair nodes on unannounced channels.

### Liquidity Ads

This release includes an early prototype for [liquidity ads](https://github.com/lightning/bolts/pull/1153).
Liquidity ads allow nodes to sell their liquidity in a trustless and decentralized manner.
Every node advertises the rates at which they sell their liquidity, and buyers connect to sellers that offer interesting rates.

The liquidity ads specification is still under review and will likely change.
This feature isn't meant to be used on mainnet yet and is thus disabled by default.

### Update minimal version of Bitcoin Core

With this release, eclair requires using Bitcoin Core 27.2.
Newer versions of Bitcoin Core may be used, but have not been extensively tested.

This version introduces a new coin selection algorithm called [CoinGrinder](https://github.com/bitcoin/bitcoin/blob/master/doc/release-notes/release-notes-27.0.md#wallet) that will reduce on-chain transaction costs when feerates are high.

To enable CoinGrinder at all fee rates and prevent the automatic consolidation of UTXOs, add the following line to your `bitcoin.conf` file:

```conf
consolidatefeerate=0
```

### Incoming obsolete channels will be rejected

Eclair will not allow remote peers to open new `static_remote_key` channels. These channels are obsolete, node operators should use `option_anchors` channels now.
Existing `static_remote_key` channels will continue to work. You can override this behaviour by setting `eclair.channel.accept-incoming-static-remote-key-channels` to true.

Eclair will not allow remote peers to open new obsolete channels that do not support `option_static_remotekey`.

### HTLC endorsement for channel jamming

Channel jamming mitigations have been under active research for the past year, leading to a proposal based on [local per-node reputation](https://github.com/lightning/bolts/pull/1071).
A first step towards evaluating the reputation algorithms is to relay an "endorsement" field on outgoing payments, as detailed in [bLIP 4](https://github.com/lightning/blips/pull/27).
Eclair implements bLIP 4 and will relay endorsement signals, which will allow network-wide experimentation of reputation algorithms in the future.

See #2884 for more details.

### On-the-fly funding

Payments sent to mobile wallets often fail because the recipient doesn't have enough inbound liquidity to receive it.
This release contains an implementation of our [proposed protocol](https://github.com/lightning/blips/pull/36) to negotiate an on-the-fly liquidity purchase when receiving such payments.
This protocol builds on top of experimental BOLT features such as splicing and liquidity ads and is used in production in the latest version of [Phoenix](https://phoenix.acinq.co/).

This isn't meant to be activated by default on eclair nodes, but if you want to run an LSP for mobile wallets, you may be interested in this feature.
This is still experimental at this point and is waiting for other implementations, so use at your own risk!

### API changes

- `channelstats` now takes optional parameters `--count` and `--skip` to control pagination. By default, it will return the first 10 entries (#2890)
- `createinvoice` now takes an optional `--privateChannelIds` parameter that can be used to add routing hints through private channels (#2909)
- `nodes` allows filtering nodes that offer liquidity ads (#2848)
- `rbfsplice` lets any channel participant RBF the current unconfirmed splice transaction (#2887)

### Miscellaneous improvements and bug fixes

This release contains multiple performance improvements related to RPC calls made to `bitcoind`, mostly when many channels are being force-closed.

We also update our secp256k1 library to the [0.6.0](https://github.com/bitcoin-core/secp256k1/releases/tag/v0.6.0) release.

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

- [145d0f4](https://github.com/ACINQ/eclair/commit/145d0f486028e58cc66ea4e28b26de5f5978edac) Back to dev (#2832)
- [82dbbdb](https://github.com/ACINQ/eclair/commit/82dbbdbf3fe546b414dd02aba34416ba01b5ee46) Use bitcoin-lib 0.33 (#2822)
- [c866be3](https://github.com/ACINQ/eclair/commit/c866be321c677e5be9d35b8a843a7c8cd7986c2e) Fix flaky test (#2833)
- [1b3e4b0](https://github.com/ACINQ/eclair/commit/1b3e4b0daee337ef3ab918d24bec43d4365625ee) Allow relaying messages to self (#2834)
- [40ef365](https://github.com/ACINQ/eclair/commit/40ef3655cf849e73ee025b2e4cd0f07fd5243a27) Fixup quiescence timeout when initiating splice (#2836)
- [c8184b3](https://github.com/ACINQ/eclair/commit/c8184b3e43a603454d672aed61b2ae461a80e1a8) Update the CLI tools (#2837)
- [7ab42bf](https://github.com/ACINQ/eclair/commit/7ab42bfad51b8fb9e7cc1349270b0c60d31f0ecc) Remove redundant check on  `remoteScriptPubkey` (#2844)
- [c6e586a](https://github.com/ACINQ/eclair/commit/c6e586ab892227d4889dc809c6b1aad5f54e5856) Relax assumptions about `gossip_queries` (#2842)
- [35295af](https://github.com/ACINQ/eclair/commit/35295af73cad4ccb870d496a89b3c724568202dc) Update Bitcoin Core to v26.1 (#2851)
- [c493493](https://github.com/ACINQ/eclair/commit/c4934930aa2447d66d1e364b252d6aafc5968cd5) Implicit node id in offers with blinded paths (#2852)
- [bbd52fa](https://github.com/ACINQ/eclair/commit/bbd52fab029e4281aa463e6b2a1c0013deb6d977) Fix TransactionsSpec tests (#2857)
- [b73a009](https://github.com/ACINQ/eclair/commit/b73a009a1d7d7ea3a158776cd233512b9a538550) Cleanup of RouteBlinding feature (#2856)
- [414f728](https://github.com/ACINQ/eclair/commit/414f72898bea428be3ec4bf33f0e7a2edac993db) Accept onion failure without a `channel_update` (#2854)
- [3bb5f3e](https://github.com/ACINQ/eclair/commit/3bb5f3e9f21f9a4c88f1fd1aa33185116278d9bd) Unwrap blinded routes that start at our node (#2858)
- [40f13f4](https://github.com/ACINQ/eclair/commit/40f13f4de5bb3c4507374ea7fe574fd1878806be) (Minor) Log local inputs in interactive-tx (#2864)
- [f0e3985](https://github.com/ACINQ/eclair/commit/f0e3985d109785ae4173dbce12780a8157f561ce) Add `paysCommitTxFees` flag to `LocalParams` (#2845)
- [741ac49](https://github.com/ACINQ/eclair/commit/741ac492e250a7074dcb381e2468d327b46f9e76) Register can forward messages to nodes (#2863)
- [3277e6d](https://github.com/ACINQ/eclair/commit/3277e6d01c80dcdae141d6859db417fd4e7fdd1b) Add `EncodedNodeId` for mobile wallets (#2867)
- [d960266](https://github.com/ACINQ/eclair/commit/d9602664454f926f6a64dda0a25ca8ae05786eb4) Fix flaky test for punishing a published revoked commit (#2871)
- [71bad3a](https://github.com/ACINQ/eclair/commit/71bad3a210b8bf6fec3eb8e8c792bc7d41cfc2ca) Update Bitcoin Core to v27.1 (#2862)
- [c53b32c](https://github.com/ACINQ/eclair/commit/c53b32c7813b0706d6b4537e9515682420cf2423) Reject unspendable inputs in `interactive-tx` (#2870)
- [791edf7](https://github.com/ACINQ/eclair/commit/791edf78b6147e96874228a1d9ad9d8d84a117dc) Improve `Origin` and `Upstream` (#2872)
- [9762af8](https://github.com/ACINQ/eclair/commit/9762af8bef094355bf6f3bd8cbfb085a3998143c) Update test vector for onion messages (#2876)
- [47c7a45](https://github.com/ACINQ/eclair/commit/47c7a457675db4b09df1b94ea7ac915cb6bd727d) Monitor onion messages (#2877)
- [eaa9e40](https://github.com/ACINQ/eclair/commit/eaa9e400c4098aa797fd8102822d8102fb5355fd) Activate route blinding and quiescence features (#2878)
- [14a4ea4](https://github.com/ACINQ/eclair/commit/14a4ea45b10fa6083acf7bfbed7dd52843b55f96) Upgrade kamon to 2.7.3 (#2879)
- [f8d6acb](https://github.com/ACINQ/eclair/commit/f8d6acb3267316e15a31047846b7ae97baa8f9c3) Add logs to onion message relay (#2880)
- [86373b4](https://github.com/ACINQ/eclair/commit/86373b44113a54cbd5dfe543df5db679c472b599) Reject new `static_remote_key` channels (#2881)
- [83d790e](https://github.com/ACINQ/eclair/commit/83d790e1f169ba63ecf60f7150ef14cfd07c2d9d) Add incoming peer to Hot.Channel (#2883)
- [e298ba9](https://github.com/ACINQ/eclair/commit/e298ba96ea934fdab4297f16ac1405eb0463861e) Offer test vectors (#2723)
- [7aacd4b](https://github.com/ACINQ/eclair/commit/7aacd4b460a707f04e713be0b91ebfa241c424e1) Add HTLC endorsement/confidence (#2884)
- [c45d278](https://github.com/ACINQ/eclair/commit/c45d2784b5757e7b220d3091fddf8c649c7d2029) Pagination for the `channelstats` RPC (#2890)
- [c440007](https://github.com/ACINQ/eclair/commit/c440007b527442921c82d170b73d8a3de08f897a) Fix failure to launch from directory with space in it (#2886)
- [fcd88b0](https://github.com/ACINQ/eclair/commit/fcd88b0a0a2c3ca56dd355dd8a7a47142dd693f1) Wake up wallet nodes before relaying messages or payments (#2865)
- [8370fa2](https://github.com/ACINQ/eclair/commit/8370fa29c01b38b025e3ae9a272430fdb9efbdaa) Reduce the number of RPC calls to bitcoind during force-close (#2902)
- [d726ca1](https://github.com/ACINQ/eclair/commit/d726ca19fc332ccf2515af1161a9255d80085405) Update CI test with latest bitcoin core (switch from autotools to cmake) (#2906)
- [1ff5697](https://github.com/ACINQ/eclair/commit/1ff56972672090ab9239d366cb3db10d14e1a938) Use bitcoin-lib 0.34 (#2905)
- [a710922](https://github.com/ACINQ/eclair/commit/a710922729844bea24d6b2b4635a931475d7b5a2) Ignore LND mutual close errors instead of force-closing (#2907)
- [7b25c5a](https://github.com/ACINQ/eclair/commit/7b25c5adcad963c56c5e2fc3b5c18af997925423) Include faulty TLV tag in `InvalidOnionPayload` error (#2908)
- [885b45b](https://github.com/ACINQ/eclair/commit/885b45bd7543aabbb0e5f7ac2f24a0c51c8dc530) Allow including routing hints when creating Bolt 11 invoice (#2909)
- [cfdb088](https://github.com/ACINQ/eclair/commit/cfdb0885f87eb5552d99acc76473b6128acee30b) Extensible Liquidity Ads (#2848)
- [db8290f](https://github.com/ACINQ/eclair/commit/db8290f80e3559b8d3a5e6325afb52014c65e21d) Add `recommended_feerates` optional message (#2860)
- [de42c8a](https://github.com/ACINQ/eclair/commit/de42c8aa1b5b592f528240fea168260161c15035) Implement on-the-fly funding based on splicing and liquidity ads (#2861)
- [f11f922](https://github.com/ACINQ/eclair/commit/f11f922c6bd92565db8f51c0eb7a9f2a5b969964) Add support for `funding_fee_credit` (#2875)
- [5e9d8c3](https://github.com/ACINQ/eclair/commit/5e9d8c3a9e7399b515e84c29e27e22612868ddf3) Don't drop `wallet_node_id` when wake-up is disabled (#2916)
- [11b6a52](https://github.com/ACINQ/eclair/commit/11b6a52ea0b87781541d2d796266875602e12848) Take min feerate into account for recommended fees (#2918)
- [2a3d7d7](https://github.com/ACINQ/eclair/commit/2a3d7d73fb2ec116ecc7c7290986682cd3531bb2) Update Bolt 12 test vectors (#2914)
- [1b749e1](https://github.com/ACINQ/eclair/commit/1b749e18a387c1ff0503f4065fc3714127adfc4f) Remove support for splicing without quiescence (#2922)
- [cf6b4e3](https://github.com/ACINQ/eclair/commit/cf6b4e39296fab499595740f64ec8aaeec60fa29) Add basic liquidity purchase information to funding txs (#2923)
- [b8e6800](https://github.com/ACINQ/eclair/commit/b8e6800e9d5e0ac4c4113f6a1207de840b4d86a7) Enforce recommended feerate for on-the-fly funding (#2927)
- [e09c830](https://github.com/ACINQ/eclair/commit/e09c830f103c75869e89470927aa3389a86eb1fc) Automatically disable `from_future_htlc` when abused (#2928)
- [f1e0735](https://github.com/ACINQ/eclair/commit/f1e07353b934b69c65a16b5d8e251d31dbf7f352) Fix comment (#2930)
- [13d4c9f](https://github.com/ACINQ/eclair/commit/13d4c9f06c655867d95597359c8d78fbd3c87274) Add support for RBF-ing splice transactions (#2925)
- [96d0c9a](https://github.com/ACINQ/eclair/commit/96d0c9a35b2291ecd9ad6cfb515a5d86c76e5e18) Add detailed error message when splice feerate is incorrect (#2920)
- [4ca8ea0](https://github.com/ACINQ/eclair/commit/4ca8ea025e68f5db389326ccfc883a356e8d5ea4) Use shared input's `txOut` in `shouldSignFirst` (#2934)
- [f4efd64](https://github.com/ACINQ/eclair/commit/f4efd64ae5a9775b5cabc552fe1665a48efc60b9) Don't relay buggy extra payments (#2937)
- [f02c98b](https://github.com/ACINQ/eclair/commit/f02c98b3b3d5432032eedc2480d708ea3e267d95) Make cluster serialization support unknown messages (#2938)
- [5410146](https://github.com/ACINQ/eclair/commit/541014680c3a57d3201ac33e269d839a585aba5c) Add force-close notification (#2935)
- [51defce](https://github.com/ACINQ/eclair/commit/51defce453c39444062615b06a59fab9da6fbbfb) Add logs for balance estimate (#2939)
- [a0b5834](https://github.com/ACINQ/eclair/commit/a0b58344be9acd7d410edcec7f30f2ac42bbdd45) Update Bitcoin Core to 27.2 (#2940)
- [47fdfae](https://github.com/ACINQ/eclair/commit/47fdfaec9f6760962bb6f24d1e15c45b7ece7474) Simplify trampoline test helpers (#2942)
- [02abc3a](https://github.com/ACINQ/eclair/commit/02abc3a7e561529fab4c30606b7660e40650f80a) Allow plain `outgoing_node_id` in blinded `payment_relay` (#2943)
- [a624b82](https://github.com/ACINQ/eclair/commit/a624b82e00d5df577007e0b7e382e3dddd99880c) Use bitcoin-lib 0.35 (#2950)
- [ab94128](https://github.com/ACINQ/eclair/commit/ab94128acce8063f08859049e902a8985519f36e) Refactor trampoline-to-legacy payments (#2948)
- [0d2d380](https://github.com/ACINQ/eclair/commit/0d2d38026a4c6d279b5eb2d06761e12617c9c121) Rename `blinding` to `pathKey` (#2951)
- [304290d](https://github.com/ACINQ/eclair/commit/304290d84117ddda912bdca5ad98d7418a064802) Various refactoring for trampoline blinded paths (#2952)
- [f47acd0](https://github.com/ACINQ/eclair/commit/f47acd0fb4f430a5dd52f307e6008e6b1740715b) Check HTLC output status before funding HTLC tx (#2944)
