# Eclair v0.13.0

This release contains a lot of refactoring and an initial implementation of taproot channels.
The specification for taproot channels is still ongoing (https://github.com/lightning/bolts/pull/995) so this cannot be used yet, but it is ready for cross-compatibility tests with other implementations.

This release also contains improvements to splicing based on recent specification updates, and better Bolt 12 support.
Note that the splicing specification is still pending, so it cannot be used with other implementations yet.

This is the last release of eclair where channels that don't use anchor outputs will be supported.
If you have channels that don't use anchor outputs, you should close them (see below for more details).

As usual, this release contains various performance improvements, more configuration options and bug fixes.
In particular, the amount of data stored for each channel has been optimized (especially during force-close), which reduces the size of the channels DB.
Also, the performance of the on-chain watcher during mass force-close has been drastically improved.

## Major changes

### Package relay

With Bitcoin Core 28.1, eclair starts relying on the `submitpackage` RPC during channel force-close.
When using anchor outputs, allows propagating our local commitment transaction to peers who are also running Bitcoin Core 28.x or newer, even if the commitment feerate is low (package relay).

This removes the need for increasing the commitment feerate based on mempool conditions, which ensures that channels won't be force-closed anymore when nodes disagree on the current feerate.

### Deprecation warning for non-anchor channels

This is the last release where `eclair` will support non-anchor channels.
Starting with the next release, those channels will be deprecated and `eclair` will refuse to start.
Please make sure you close your existing non-anchor channels whenever convenient.

You can list those channels using the following command:

```sh
$ eclair-cli channels | jq '.[] | { channelId: .data.commitments.channelParams.channelId, commitmentFormat: .data.commitments.active[].commitmentFormat }' | jq 'select(.["commitmentFormat"] == "legacy")'
```

If your peer is online, you can then cooperatively close those channels using the following command:

```sh
$ eclair-cli close --channelId=<channel_id_from_previous_step>  --preferredFeerateSatByte=<feerate_satoshis_per_byte>
```

If your peer isn't online, you may want to force-close those channels to recover your funds:

```sh
$ eclair-cli forceclose --channelId=<channel_id_from_previous_step>
```

### Database migration of channel data

When updating your node, eclair will automatically migrate all of your channel data to the latest (internal) encoding.
Depending on the number of open channels, this may be a bit slow: don't worry if this initial start-up is taking more time than usual.
This will only happen the first time you restart your node.

This is an important step towards removing legacy code from our codebase, which we will do before the next release.

### Attribution data

Eclair now supports attributable failures which allow nodes to prove they are not the source of the failure.
Previously a failing node could choose not to report the failure and we would penalize all nodes of the route.
If all nodes of the route support attributable failures, we only need to penalize two nodes (there is still some uncertainty as to which of the two nodes is the failing one).
See https://github.com/lightning/bolts/pull/1044 for more details.

Attribution data also provides hold times from payment relayers, both for fulfilled and failed HTLCs.

Support is enabled by default.
It can be disabled by setting `eclair.features.option_attribution_data = disabled`.

### Local reputation and HTLC endorsement

To protect against jamming attacks, eclair gives a reputation to its neighbors and uses it to decide if a HTLC should be relayed given how congested the outgoing channel is.
The reputation is basically how much this node paid us in fees divided by how much they should have paid us for the liquidity and slots that they blocked.
The reputation is per incoming node and endorsement level.
The confidence that the HTLC will be fulfilled is transmitted to the next node using the endorsement TLV of the `update_add_htlc` message.
Note that HTLCs that are considered dangerous are still relayed: this is the first phase of a network-wide experimentation aimed at collecting data.

To configure, edit `eclair.conf`:

```eclair.conf
// We assign reputations to our peers to prioritize payments during congestion.
// The reputation is computed as fees paid divided by what should have been paid if all payments were successful.
eclair.relay.peer-reputation {
    // Set this parameter to false to disable the reputation algorithm and simply relay the incoming endorsement
    // value, as described by https://github.com/lightning/blips/blob/master/blip-0004.md,
    enabled = true
    // Reputation decays with the following half life to emphasize recent behavior.
    half-life = 30 days
    // Payments that stay pending for longer than this get penalized
    max-relay-duration = 5 minutes
}
```

### Use past payment attempts to estimate payment success

When setting `eclair.router.path-finding.use-ratios = false` in `eclair.conf`, we estimate the probability that a given route can relay a given payment as part of route selection.
Until now this estimate was naively assuming the channel balances to be uniformly distributed.
By setting `eclair.router.path-finding.use-past-relay-data = true`, we will now use data from past payment attempts (both successes and failures) to provide a better estimate, hopefully improving route selection.

### API changes

- `listoffers` now returns more details about each offer (see #3037 for more details).
- `parseoffer` is added to display offer fields in a human-readable format (see #3037 for more details).
- `forceclose` has a new optional parameter `maxClosingFeerateSatByte`: see the `max-closing-feerate` configuration section below for more details.

### Configuration changes

The default configuration value for `eclair.features.option_channel_type` is now  `mandatory` instead of `optional`. This change has been added to the BOLTs in [#1232](https://github.com/lightning/bolts/pull/1232).

We added a configuration parameter to facilitate custom signet use.
The parameter `eclair.bitcoind.signet-check-tx` should be set to the txid of a transaction that exists in your signet or set to `""` to skip this check.
See issue [#3078](https://github.com/ACINQ/eclair/issues/3078) for details.

### Miscellaneous improvements and bug fixes

#### Add `max-closing-feerate` configuration parameter

We added a new configuration value to `eclair.conf` to limit the feerate used for force-close transactions where funds aren't at risk: `eclair.on-chain-fees.max-closing-feerate`.
This ensures that you won't end up paying a lot of fees during mempool congestion: your node will wait for the feerate to decrease to get your non-urgent transactions confirmed.

The default value from `eclair.conf` can be overridden by using the `forceclose` API with the `maxClosingFeerateSatByte` set, which allows a per-channel override. This is particularly useful for channels where you have a large balance, which you may wish to recover more quickly.

If you need those transactions to confirm because you are low on liquidity, you can either:

- update `eclair.on-chain-fees.max-closing-feerate` and restart your node: `eclair` will automatically RBF all available transactions for all closing channels.
- use the `forceclose` API with the `maxClosingFeerateSatByte` set, to update a selection of channels without restarting your node.

#### Remove confirmation scaling based on funding amount

We previously scaled the number of confirmations based on the channel funding amount.
However, this doesn't work with splicing, where the channel capacity may change drastically.
It's much simpler to always use the same number of confirmations, while choosing a value that is large enough to protect against malicious reorgs.
We now by default use 8 confirmations, which can be modified in `eclair.conf`:

```conf
// Minimum number of confirmations for channel transactions to be safe from reorgs.
eclair.channel.min-depth-blocks = 8
```

Note however that we require `min-depth` to be at least 6 blocks, since the BOLTs require this before announcing channels.
See #3044 for more details.

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

- [af05a55](https://github.com/ACINQ/eclair/commit/af05a55351fce881c9f9e8d0ef2ab369adfa5cd0) Back to dev (#3035)
- [c2f6852](https://github.com/ACINQ/eclair/commit/c2f68521f84aadd30cb70b2994e5b2e0aa6a1db1) Stop using `QuickLens` in non-test code (#3036)
- [bdaa625](https://github.com/ACINQ/eclair/commit/bdaa625aa8feca39f5d9c135cc3de2f0c05603ee) Improve Bolt12 offer APIs (#3037)
- [b98930b](https://github.com/ACINQ/eclair/commit/b98930b071634abcc933339880e15e8ae335d4f8) Fix flaky GossipIntegrationSpec test (#3039)
- [5d6e556](https://github.com/ACINQ/eclair/commit/5d6e556e618ac9c7136df6be3e74a5edbfe9f699) Support p2tr bitcoin wallet (#3026)
- [9a63ed9](https://github.com/ACINQ/eclair/commit/9a63ed9e210306add3138a1e3fef7804cdd151c3) Fix plugin loading in Windows (#3043)
- [9210dff](https://github.com/ACINQ/eclair/commit/9210dffe2c3fb7f9e403748d194dfe9b5003ecab) Keep track on which side initiated a mutual close (#3042)
- [9c85128](https://github.com/ACINQ/eclair/commit/9c851286acad7d04912edb4d84cedb76c914d84a) Remove amount-based confirmation scaling (#3044)
- [b4e938d](https://github.com/ACINQ/eclair/commit/b4e938d7caa10115cfb66f524a0a2bc36a979e4d) Optional `payment_secret` in trampoline outer payload (#3045)
- [2dfdc26](https://github.com/ACINQ/eclair/commit/2dfdc26b4278250f2cbc8238048b1d8f219a8aed) Make option_channel_type mandatory (#3046)
- [fbc7004](https://github.com/ACINQ/eclair/commit/fbc7004ecf24e971352271e2ec7f656779764bf0) Add more force-close tests for HTLC settlement (#3040)
- [24e397a](https://github.com/ACINQ/eclair/commit/24e397a9ce2f8761150f7895ed83faa54591c8b8) Add an index on `audit.relayed(channel_id)` (#3048)
- [9ea9e97](https://github.com/ACINQ/eclair/commit/9ea9e97a4609428f06abac7b89274f4a827da72b) Fix tests that sometimes get p2tr inputs (#3049)
- [3d415bc](https://github.com/ACINQ/eclair/commit/3d415bc9127cdd25779bda77d90307a42e82e21f) Use package relay for anchor force-close (#2963)
- [8df52bb](https://github.com/ACINQ/eclair/commit/8df52bb7f750b1ce30ff2361bc6e9a78f69955fc) Fix comments for `feerate-tolerance` in `reference.conf` (#3053)
- [e0a9c0a](https://github.com/ACINQ/eclair/commit/e0a9c0a03b00f15cb924ab4a0ae9d3fc0aa48bff) Relay non-blinded failure from wallet nodes (#3050)
- [650681f](https://github.com/ACINQ/eclair/commit/650681f78e28b0839943b9dec25335ed5ef9ef19) ChannelKeyManager: add optional list of spent outputs to sign() methods (#3047)
- [633738b](https://github.com/ACINQ/eclair/commit/633738b12dd3f86353ffceb7dad94b62f892228e) [test] latest-bitcoind: build output location has moved to bin/ (#3057)
- [cb7f95d](https://github.com/ACINQ/eclair/commit/cb7f95d479e8f262193b0664852f60d7ac098bc5) Add test for Bolt11 features minimal encoding (#3058)
- [4088359](https://github.com/ACINQ/eclair/commit/40883591e5a818bdef75ed95a003babebf2aab35) fixup! Fix flaky GossipIntegrationSpec test (#3039) (#3061)
- [383d141](https://github.com/ACINQ/eclair/commit/383d1413c336460882ad23d469f8a4fba00a817f) Remove support for claiming remote anchor output (#3062)
- [63f4ca8](https://github.com/ACINQ/eclair/commit/63f4ca8e10b24687992292dc07d8825ac05da2a5) Use bitcoin-lib 0.37 (#3063)
- [ecd4634](https://github.com/ACINQ/eclair/commit/ecd46342c38862167448f29a45d2f222bc8a20ce) Simplify channel keys management (#3064)
- [a626281](https://github.com/ACINQ/eclair/commit/a62628181f0b0876872c52bb626b185a93b33fd4) Remove duplication around skipping dust transactions (#3068)
- [76eb6cb](https://github.com/ACINQ/eclair/commit/76eb6cb9226160ddc068b0fd0b98cef951779e75) Move `addSigs` into each dedicated class (#3069)
- [3b714df](https://github.com/ACINQ/eclair/commit/3b714df04950054c1a66010050de4b9b75fe6a61) Refactor HTLC-penalty txs creation (#3071)
- [826284c](https://github.com/ACINQ/eclair/commit/826284cb277c28c7eef14aa275f3d6e3255c8e66) Remove `LegacyClaimHtlcSuccess` transaction class (#3072)
- [2ada7e7](https://github.com/ACINQ/eclair/commit/2ada7e7f82a991942b3efd95de30f08307c4fd22) Rework the `TransactionWithInputInfo` architecture (#3074)
- [f14b92d](https://github.com/ACINQ/eclair/commit/f14b92d7df8727d6362d86251d717a93f37df251) Increase default revocation timeout (#3082)
- [fdc2077](https://github.com/ACINQ/eclair/commit/fdc207797a9ef29e9910ac9ac03441b151a7229c) Refactor replaceable transactions (#3075)
- [9b0c00a](https://github.com/ACINQ/eclair/commit/9b0c00a2a28d3ba6c7f3d01fbd2d8704ebbdc75d) Add an option to specify a custom signet tx to check (#3088)
- [055695f](https://github.com/ACINQ/eclair/commit/055695fe0a2fee6dcfe2bfebe81a7e9c55ceaad4) Attributable failures (#3065)
- [1e23081](https://github.com/ACINQ/eclair/commit/1e23081751aa82ca1f9d23c90b919fa1f79026b6) Refactor some closing helper functions (#3089)
- [dd622ad](https://github.com/ACINQ/eclair/commit/dd622ad882a8860923989b5ed03348f26655dc77) Add low-level taproot helpers (#3086)
- [a1c6988](https://github.com/ACINQ/eclair/commit/a1c6988e0b11103a4faf58bb1a5802b853dac562) Stricter batching of `commit_sig` messages on the wire (#3083)
- [62182f9](https://github.com/ACINQ/eclair/commit/62182f91b1681778f6c4a7f327be4fc62ab21248) Increase limits for flaky test (#3091)
- [fb84a9d](https://github.com/ACINQ/eclair/commit/fb84a9d1115bf80bb2b3269fafa31fd5cc9dfacb) Cleaner handling of HTLC settlement during force-close (#3090)
- [f8b1272](https://github.com/ACINQ/eclair/commit/f8b1272cb40c8f5212b8d1fb69652221cb6b2c17) Watch spent outputs before watching for confirmation (#3092)
- [2c10538](https://github.com/ACINQ/eclair/commit/2c105381c0391f9ed0ef86b155be5d16c22324fa) Rework closing channel balance computation (#3096)
- [345aef0](https://github.com/ACINQ/eclair/commit/345aef0268096c57ea73842c928ec06a36e02eab) Add more splice channel_reestablish tests (#3094)
- [e7b9b89](https://github.com/ACINQ/eclair/commit/e7b9b896a9244893e13dce2a4eeadc594c364f09) Parse offers and pay offers with currency (#3101)
- [100e174](https://github.com/ACINQ/eclair/commit/100e174a34f572500814f1146285fbc9dabbeb0e) Add attribution data to UpdateFulfillHtlc (#3100)
- [52b7652](https://github.com/ACINQ/eclair/commit/52b7652b83e3645b4bda7737b3611984c7be0a53) Remove non-final transactions from `XxxCommitPublished` (#3097)
- [7b67f33](https://github.com/ACINQ/eclair/commit/7b67f332baf2312ef75bbf1f580643d90f831935) Stop storing commit tx and HTLC txs in channel data (#3099)
- [8abb525](https://github.com/ACINQ/eclair/commit/8abb5255270b02c6c7038e8c550a985762cc8037) Round hold times to decaseconds (#3112)
- [8e3e206](https://github.com/ACINQ/eclair/commit/8e3e206d4a653604cde661ff15d7d3edcec037b8) Increase channel spent delay to 72 blocks (#3110)
- [297f7f0](https://github.com/ACINQ/eclair/commit/297f7f05f4e8eccfd993b803a4d5e5ac2bf71d6e) Prepare attribution data for trampoline payments (#3109)
- [9418ea1](https://github.com/ACINQ/eclair/commit/9418ea1740479fbcd03df5f745979a5404939db9) Remove obsolete interop tests (#3114)
- [961f844](https://github.com/ACINQ/eclair/commit/961f84403125fc2a07400a574e21f2de4651a0ab) Simplify force-close transaction signing and replaceable publishers (#3106)
- [2e6c6fe](https://github.com/ACINQ/eclair/commit/2e6c6feadd4c9039a66f55f45dceee8da382b261) Use `Uint64` for `max_htlc_value_in_flight_msat` consistently (#3113)
- [4a34b8c](https://github.com/ACINQ/eclair/commit/4a34b8c73f2f562e97138e45704ca374595c2c5f) Ensure `htlc_maximum_msat` is at least `htlc_minimum_msat` (#3117)
- [e6585bf](https://github.com/ACINQ/eclair/commit/e6585bf7e68836022685296006c285b0569628cc) Keep original features byte vector in Bolt12 TLVs (#3121)
- [5e829ac](https://github.com/ACINQ/eclair/commit/5e829ac4c9e3508fb23b9ceda021674d2ec6376a) Stricter Bolt11 invoice parsing (#3122)
- [6fb7ac1](https://github.com/ACINQ/eclair/commit/6fb7ac1381d4aa603da756ff9f71ffc840d3404a) Refactor channel params: extract commitment params (#3116)
- [bddacda](https://github.com/ACINQ/eclair/commit/bddacda988f2f5a1a2588dc0fb7998d2dc9a4065) Publish hold times to the event stream (#3123)
- [3a9b791](https://github.com/ACINQ/eclair/commit/3a9b79184b71ac49d837e7473400e9cda7c665b2) Fix flaky 0-conf watch-published event (#3124)
- [d7c020d](https://github.com/ACINQ/eclair/commit/d7c020d14c57b3b463839da1c7a71fd157c77ef2) Refactor attribution helpers and commands (#3125)
- [17ac335](https://github.com/ACINQ/eclair/commit/17ac335c477b1cbf6fd372848eacbf020bd5f889) Upgrade to bitcoin-lib 0.41 (#3128)
- [43c3986](https://github.com/ACINQ/eclair/commit/43c3986870bbf864333b203011c6066216d6bfc4) Endorse htlc and local reputation (#2716)
- [09fc936](https://github.com/ACINQ/eclair/commit/09fc9368128a7af05474e1bb8ef4624bfb0656b1) Rename maxHtlcAmount to maxHtlcValueInFlight in Commitments (#3131)
- [a307b70](https://github.com/ACINQ/eclair/commit/a307b70b838d46ee28d2b5f39626802ca1de0767) Add unconfirmed transaction pruning when computing closing balance (#3119)
- [9af7084](https://github.com/ACINQ/eclair/commit/9af708446c9a09670855ef33779a0bca1a53eaaa) Add outgoing reputation (#3133)
- [65e2639](https://github.com/ACINQ/eclair/commit/65e26391754744c3e50938ca82f1db0106461808) Fix flaky on-chain balance test (#3132)
- [af3cd55](https://github.com/ACINQ/eclair/commit/af3cd55912d752056b437d57c41f480684e14c4a) Add recent invoice spec test vectors (#3137)
- [5703cd4](https://github.com/ACINQ/eclair/commit/5703cd45ebfb3fdcaa8db03b1d72afce2ee479d3) Use actual CLTV delta for reputation (#3134)
- [b651e5b](https://github.com/ACINQ/eclair/commit/b651e5b987528fbb9a5981eed5bf84414e52f94f) Offers with currency must set amount. (#3140)
- [49bee72](https://github.com/ACINQ/eclair/commit/49bee72fd7b4c6efaebc6c9521d604c1d5a40718) Extract `CommitParams` to individual commitments (#3118)
- [d8ce91b](https://github.com/ACINQ/eclair/commit/d8ce91b4efea704ee56a9938928824d1ed2665f9) Simple taproot channels (#3103)
- [0e0da42](https://github.com/ACINQ/eclair/commit/0e0da422986bd66672afc5e0f549e85f2f945cae) Allow omitting `previousTx` for taproot splices (#3143)
- [d7ee663](https://github.com/ACINQ/eclair/commit/d7ee6638c7c2094bbcd9309d5505a3f4f9c8dbbc) Split commit nonces from funding nonce in `tx_complete` (#3145)
- [3d5fd33](https://github.com/ACINQ/eclair/commit/3d5fd3347f291f9f2307bdc7a6a3e84708a58cd0) Fix minor incompatibilities with feature branches (#3148)
- [012b382](https://github.com/ACINQ/eclair/commit/012b3828b8d6e111755d3d00c49611837b7c79c2) Adjust `batch_size` on `commit_sig` retransmission (#3147)
- [50f16cb](https://github.com/ACINQ/eclair/commit/50f16cbd584bb3ecc00d75be7f04c261e41c886b) Add `GossipTimestampFilter` buffer during gossip queries to fix flaky tests (#3152)
- [18ea362](https://github.com/ACINQ/eclair/commit/18ea362861cc141c0c8e1842cc3188bddeae315a) Fix `LocalFundingStatus.ConfirmedFundingTx` migration (#3151)
- [d48dd21](https://github.com/ACINQ/eclair/commit/d48dd2140f8994a5362a7fc95aa10bd8366bcbab) Allow overriding `max-closing-feerate` with `forceclose` API (#3142)
- [f93d02f](https://github.com/ACINQ/eclair/commit/f93d02fb72ee6b1dc4fce953c9ecd120af260401) Allow non-initiator RBF for dual funding (#3021)
- [d4dfb86](https://github.com/ACINQ/eclair/commit/d4dfb8648e65dfc4ea15fd5dc872edf4cefd1d3e) Use balance estimates from past payments in path-finding (#2308)
- [07c0cfd](https://github.com/ACINQ/eclair/commit/07c0cfd6cdd50de7e5b65deda40123369ae16a36) Re-encode channel data using v5 codecs (#3149)
- [70a2e29](https://github.com/ACINQ/eclair/commit/70a2e297c063ec57896bc691bbe525412fd1648b) Use helpers for feerates conversions (#3156)
- [c9ff501](https://github.com/ACINQ/eclair/commit/c9ff5019edaf7ed338adcc6b5da6f0588b3504a8) Catch close commands in `Offline(WaitForDualFundingSigned)` (#3159)
- [2a5b0f1](https://github.com/ACINQ/eclair/commit/2a5b0f14f49de713a6ebec004d1890ff13475a11) Fix comparison of utxos in the balance (#3160)
- [6075196](https://github.com/ACINQ/eclair/commit/60751962703d8ba3b20ca91339df64f3d40ea819) Relax taproot feature dependency (#3161)
- [e8ec148](https://github.com/ACINQ/eclair/commit/e8ec148951b873bf3c96a71d9193db759209720c) Add high-S signature Bolt 11 test vector (#3163)
- [f32e0b6](https://github.com/ACINQ/eclair/commit/f32e0b681c05087baa202234f490f85510b913d5) Fix flaky `OfferPaymentSpec` (#3164)
