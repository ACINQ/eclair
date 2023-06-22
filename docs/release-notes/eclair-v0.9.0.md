# Eclair v0.9.0

This release contains a lot of preparatory work for important (and complex) lightning features: dual-funding, splicing and Bolt 12 offers.
These features are now fully implemented in `eclair`, but we're waiting for the specification work to be finalized and other implementations to be ready for cross-compatibility tests.
You should only activate them if you know what you're doing, and are ready to handle potential backwards-incompatible changes!
We also make plugins more powerful, introduce mitigations against various types of DoS, and improve performance in many areas of the codebase.

## Major changes

### Dual funding

Eclair is now up-to-date with the latest state of the [dual funding specification](https://github.com/lightning/bolts/pull/851).
We contributed many improvements to the specification as we implemented this feature.
This feature is disabled by default, because the specification may not be final yet.

### Splicing prototype

Eclair now supports a custom prototype for [splicing](https://github.com/lightning/bolts/pull/863).
This prototype differs from the current proposal: we've found multiple improvements that will be added to the specification.
We are actively working with other implementations to be able to release this important feature in a future version of `eclair`.

### Data model

The database model has been completely reworked to handle splices. Mainly, a channel can have several commitments in parallel.
Node operators that use Postgres as database backend and make SQL queries on channels' JSON content should reset the JSON column:

1. Set `eclair.db.postgres.reset-json-columns = true` before restarting eclair
2. Once restarted, set `eclair.db.postgres.reset-json-columns = false` (no need to restart again)

### Offers

We continued working on Bolt 12 support, and made a lot of updates to this experimental feature.

#### Paying offers

```shell
$ ./eclair-cli payoffer --offer=<offer-to-pay> --amountMsat=<amountToPay>
```

If the offer supports it, you can also specify `--quantity` to buy more than one at a time.
All the parameters from `payinvoice` are also supported.

Eclair will request an invoice and pay it (assuming it matches our request) without further interaction.

Offers are still experimental and some details could still change before they are widely supported.

#### Receiving payments for offers

To be able to receive payments for offers, you will need to use a plugin.
The plugin needs to create the offer and register a handler that will accept or reject the invoice requests and the payments.
Eclair will check that these satisfy all the protocol requirements and the handler only needs to consider whether the item on offer can be delivered or not.

Invoices generated for offers are not stored in the database to prevent a DoS vector.
Instead, all the relevant data (offer id, preimage, amount, quantity, creation date and payer id) is included in the blinded route that will be used for payment.
The handler can also add its own data.
All this data is signed and encrypted so that it can not be read or forged by the payer.

### API changes

- `audit` now accepts `--count` and `--skip` parameters to limit the number of retrieved items (#2474, #2487)
- `sendtoroute` removes the `--trampolineNodes` argument and implicitly uses a single trampoline hop (#2480)
- `sendtoroute` now accept `--maxFeeMsat` to specify an upper bound of fees (#2626)
- `payinvoice` always returns the payment result when used with `--blocking`, even when using MPP (#2525)
- `node` returns high-level information about a remote node (#2568)
- `channel-created` is a new websocket event that is published when a channel's funding transaction has been broadcast (#2567)
- `channel-opened` websocket event was updated to contain the final `channel_id` and be published when a channel is ready to process payments (#2567)
- `getsentinfo` can now be used with `--offer` to list payments sent to a specific offer.
- `listreceivedpayments` lists payments received by your node (#2607)
- `closedchannels` lists closed channels. It accepts `--count` and `--skip` parameters to limit the number of retrieved items as well (#2642)
- `cpfpbumpfees` can be used to unblock chains of unconfirmed transactions by creating a child transaction that pays a high fee (#1783)

### Miscellaneous improvements and bug fixes

#### Strategies to handle locked utxos at start-up (#2278)

If some utxos are locked when eclair starts, it is likely because eclair was previously stopped in the middle of funding a transaction.
While this doesn't create any risk of loss of funds, these utxos will stay locked for no good reason and won't be used to fund future transactions.
Eclair offers three strategies to handle that scenario, that node operators can configure by setting `eclair.bitcoind.startup-locked-utxos-behavior` in their `eclair.conf`:

- `stop`: eclair won't start until the corresponding utxos are unlocked by the node operator
- `unlock`: eclair will automatically unlock the corresponding utxos
- `ignore`: eclair will leave these utxos locked and start

#### Add plugin support for channel open interception (#2552)

Eclair now supports plugins that intercept channel open requests and decide whether to accept or reject them. This is useful for example to enforce custom policies on who can open channels with you.

An example plugin that demonstrates this functionality can be found in the [eclair-plugins](https://github.com/ACINQ/eclair-plugins) repository.

#### Configurable channel open rate limits (#2552)

We have added parameters to `eclair.conf` to allow nodes to manage the number of channel open requests from peers that are pending on-chain confirmation. A limit exists for each public peer node individually and for all private peer nodes in aggregate.

The new configuration options and defaults are as follows:

```conf
// a list of public keys; we will ignore limits on pending channels from these peers
eclair.channel.channel-open-limits.channel-opener-whitelist = [] 

// maximum number of pending channels we will accept from a given peer
eclair.channel.channel-open-limits.max-pending-channels-per-peer = 3 

// maximum number of pending channels we will accept from all private nodes
eclair.channel.channel-open-limits.max-total-pending-channels-private-nodes = 99 
```

#### Configurable limit on incoming connections (#2601)

We have added a parameter to `eclair.conf` to allow nodes to track the number of incoming connections they maintain from peers they do not have existing channels with. Once the limit is reached, Eclair will disconnect from the oldest tracked peers first.

Outgoing connections and peers on the `sync-whitelist` are exempt from and do not count towards the limit.

The new configuration option and default is as follows:

```conf
// maximum number of incoming connections from peers that do not have any channels with us
eclair.peer-connection.max-no-channels = 250 
```

#### Removed funding limits when Wumbo is enabled (#2624)

We removed the `eclair.channel.max-funding-satoshis` configuration field.
If node operators wish to limit the size of channels opened to them, there are two options.

The first option is to disable large channels support by adding the following line to `eclair.conf`:

```conf
eclair.features.option_support_large_channel = disabled
```

But that option won't limit the number of inbound channels, so it isn't a guarantee that the node will "stay small".

The second option is to leverage the new plugin support for channel open interception: node operators can reject channel open requests based on any metric that they see fit.

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

- [cd9ca57](https://github.com/ACINQ/eclair/commit/cd9ca57562063a450dee6b2f2e8a06f755eb27ec) Back to Dev (#2515)
- [3b2eb96](https://github.com/ACINQ/eclair/commit/3b2eb964fd51557cf59e39c897f7890ce55dadaa) Fix balance histogram (#2516)
- [a0b7a49](https://github.com/ACINQ/eclair/commit/a0b7a4958b85ca3c7e6d02216126f5cb50754848) Remove compatibility code for waitingSince (#2338)
- [908d87f](https://github.com/ACINQ/eclair/commit/908d87fc1d2e463f7244317d1db1679715fd409d) Switch embedded postgres library used in tests (#2518)
- [2a7649d](https://github.com/ACINQ/eclair/commit/2a7649de40029cd3eee530748d3f172a088c99e9) Add pagination on Audit API (#2487)
- [cdedbdf](https://github.com/ACINQ/eclair/commit/cdedbdf6b07605585ae27d52118c9c0638f42698) Streamline api extractor names (#2521)
- [683328d](https://github.com/ACINQ/eclair/commit/683328dfe485db488ba4d23d6333bed7c001000a) Close SQL statements during migrations (#2524)
- [8320964](https://github.com/ACINQ/eclair/commit/8320964c547022c02c4d6fd0c7af82284534cc22) Improve `htlc_maximum_msat` in channel updates (#2299)
- [7eb5067](https://github.com/ACINQ/eclair/commit/7eb50679f04d3ea06d2f0b0954359bd3c75960b4) Add mempool eviction tests (#2529)
- [ca831df](https://github.com/ACINQ/eclair/commit/ca831df241b2a723f7d9037db380509286eed3c6) Add a payment recipient abstraction (#2480)
- [04517f8](https://github.com/ACINQ/eclair/commit/04517f8215f507893942a1eec1a03c1a66ade15a) Update title of v0.8.0 release notes (#2531)
- [aa04402](https://github.com/ACINQ/eclair/commit/aa04402bc2d7d07bebb290165e04a77f2741a914) Add anchor outputs zero fee spec test vector (#2400)
- [a9146dd](https://github.com/ACINQ/eclair/commit/a9146dda09b404dc9b37f82cf26f2c86c51c4965) Fix flaky tests (#2520)
- [74719b8](https://github.com/ACINQ/eclair/commit/74719b8b14536d48035cfc80b48e6585662430f4) Send payments to blinded routes (#2482)
- [83edd8c](https://github.com/ACINQ/eclair/commit/83edd8c41dd81ee85a8dfdf46b9dee9aee06ee25) Allow non segwit outputs in dual funding (#2528)
- [b5a2d3a](https://github.com/ACINQ/eclair/commit/b5a2d3a6654c64bf4def3db4d409792145c73e07) Fix blinded route failure messages (#2490)
- [bf08769](https://github.com/ACINQ/eclair/commit/bf087698cc1639d36e689ee0b6d31e124412eed5) Update dependencies (#2537)
- [656812e](https://github.com/ACINQ/eclair/commit/656812e8cab3935bca1b8ab7d253b7edc13dcff2) `Commitments` clean-up (#2533)
- [5b19e58](https://github.com/ACINQ/eclair/commit/5b19e586014920d5ed9fa1071b6af3eacd81c0ee) Improve `payinvoice` API response (#2525)
- [c2eb357](https://github.com/ACINQ/eclair/commit/c2eb357392b08beadd52bf0ed0a062e609626327) Add `PeerReadyNotifier` actor (#2464)
- [7c50528](https://github.com/ACINQ/eclair/commit/7c50528945193385aa1375837e8eff1cb89bf808) Improve findRoute ignored channels behavior (#2523)
- [2fdc9fa](https://github.com/ACINQ/eclair/commit/2fdc9fa4d3f1da7acea881a1c2f1d6e26f45a343) Remove redundant log messages (#2527)
- [d76d0f6](https://github.com/ACINQ/eclair/commit/d76d0f6591d3fdb6635ec3eb2b1b89a42bcc4ea5) Fix build (#2543)
- [431df1f](https://github.com/ACINQ/eclair/commit/431df1fddb72db0dfd2fee23a0f5f003c047a0a4) Update various doc files (#2547)
- [f0d12eb](https://github.com/ACINQ/eclair/commit/f0d12eb2167eac60a55aad8de26db4b44e0b99d3) Minor refactoring (#2538)
- [33ca262](https://github.com/ACINQ/eclair/commit/33ca2623a7881c263747526e1eeacb896ce6bd31) Add singleton `AsyncPaymentTriggerer` to monitor when the receiver of an async payment reconnects  (#2491)
- [555de6e](https://github.com/ACINQ/eclair/commit/555de6e27afdb45ca63bcaa7763b3cf38f5b0dde) Move `Commitments` methods from companion to class (#2539)
- [9aee10a](https://github.com/ACINQ/eclair/commit/9aee10ac7e0f1fea815b278b2121970d080ad54b) Improve blinded payments e2e tests (#2535)
- [530b3c2](https://github.com/ACINQ/eclair/commit/530b3c206f2cecffcd8d6078da857959cf8916fc) Unlock utxos at startup (#2278)
- [f95f087](https://github.com/ACINQ/eclair/commit/f95f087e0bb1d22f6ca16b1b820e9cdde4369b99) Updated offers spec (#2386)
- [5967f72](https://github.com/ACINQ/eclair/commit/5967f72e64634435fa75bb2c4494541443025894) Update to bitcoind 23.1 (#2551)
- [92c27fe](https://github.com/ACINQ/eclair/commit/92c27fe4a5bbb9297e5bfa9d174602d3de8860f8) Increase blockchain watchdogs connection timeout (#2555)
- [c9c5638](https://github.com/ACINQ/eclair/commit/c9c563892ff3b900de58892ace95a2efa54bdd3a) Add tlv stream to onion failures (#2455)
- [93ed6e8](https://github.com/ACINQ/eclair/commit/93ed6e8fc63907906941ce52f10805e1f86109a7) Factor funding spent handlers (#2556)
- [06587f9](https://github.com/ACINQ/eclair/commit/06587f927945244e66b87dd22b37ec52467d994c) Wait for tx to be published for zero-conf (#2558)
- [351666d](https://github.com/ACINQ/eclair/commit/351666dbc9211b3b7ac6c1bd7996d3acb34366af) Factor funding tx acceptance (#2557)
- [6b6725e](https://github.com/ACINQ/eclair/commit/6b6725e40d8de2f4def37172c25569866d9588f4) Always lock utxos when funding transactions (#2559)
- [20c9a58](https://github.com/ACINQ/eclair/commit/20c9a58d4091236f40043de758ad55ccc56c5493) Skip waiting for empty `tx_signatures` (#2560)
- [f02d33d](https://github.com/ACINQ/eclair/commit/f02d33daddae96f9f2e9d667c205e6263072ed73) Do not hardcode input index at signature (#2563)
- [4dd4829](https://github.com/ACINQ/eclair/commit/4dd48297d56ca68b22075fd2ae5e47df14f7eb48) Move interactive-tx funding to a dedicated actor (#2561)
- [1d68ccc](https://github.com/ACINQ/eclair/commit/1d68ccc46f99388392b8ec6bf77292ab62ba35f1) Make creation of first commit tx more generic (#2564)
- [b2bde63](https://github.com/ACINQ/eclair/commit/b2bde6367be6bd6e74f9db401b8262b4d8572e11) Use case classes for fee providers (#2454)
- [6486449](https://github.com/ACINQ/eclair/commit/648644940483c19fa602b9a851a2aad3d4c8407e) Add GetNode router API (#2568)
- [b21085d](https://github.com/ACINQ/eclair/commit/b21085ddd67c88b4e72435b4b1b43ade2a0d06f5) Add `ChannelOpened` event (#2567)
- [303799e](https://github.com/ACINQ/eclair/commit/303799e6e91d54ee08f2c052e30afdbb8c86f20f) Allow keysend payments without a payment secret (#2573)
- [204bc3e](https://github.com/ACINQ/eclair/commit/204bc3e9b170ad2efeb569882efde32589fbe066) Build message inside Postman (#2570)
- [c52bb69](https://github.com/ACINQ/eclair/commit/c52bb694eafbb515308bde0233a410567b26f92f) Rework data model for splices (#2540)
- [06fcb1f](https://github.com/ACINQ/eclair/commit/06fcb1f6327f580cad0d95d0878f3912ea97c3b5) Dual funding latest changes (#2536)
- [9d4f2ba](https://github.com/ACINQ/eclair/commit/9d4f2baedf630d7b7796b4985778bfa04de25233) Add `replyTo` field to Router.GetNode (#2575)
- [8084f3c](https://github.com/ACINQ/eclair/commit/8084f3c7fc2bce5f97927ddc57b5949946222173) Properly quote urls in bash scripts (#2580)
- [d52e869](https://github.com/ACINQ/eclair/commit/d52e869a4d146069e696666dbe4e80b6d68deb11) Don't send payment secret when using keysend (#2574)
- [1d5af4d](https://github.com/ACINQ/eclair/commit/1d5af4df5ecf7e11b37ae47c62b42c80b044c31c) Read channel db only once at restart (#2569)
- [9611f9e](https://github.com/ACINQ/eclair/commit/9611f9e2462030e7115662f27205a7c4f577b79a) Improve `InteractiveTxBuilder` model (#2576)
- [e3e1ee5](https://github.com/ACINQ/eclair/commit/e3e1ee5231c72a36bdb405efc728e2674c9b07e3) Refactor commitments and meta-commitments (#2579)
- [28072e8](https://github.com/ACINQ/eclair/commit/28072e8d690d754e94d47b3b175a1a9ce4fa53f6) Select final onchain address when closing (#2565)
- [46999fd](https://github.com/ACINQ/eclair/commit/46999fd3b63ddf31f682d6b08143aab1461df836) Use `MetaCommitments` as much as possible (#2587)
- [2857994](https://github.com/ACINQ/eclair/commit/2857994cf358fcb64f8b2cb44cfc6069d66efda7) Equality for TlvStream (#2586)
- [927e1c8](https://github.com/ACINQ/eclair/commit/927e1c8ad4137e3539567330e4d6af0a33a218a5) Absolute priority to local channels (#2588)
- [f901aea](https://github.com/ACINQ/eclair/commit/f901aeac04b47cc25479d6d381b4649044c90afc) Fix ability for plugins to exchange custom Lightning Messages with other LN nodes (#2495)
- [198fd93](https://github.com/ACINQ/eclair/commit/198fd934f340babbcd055a2352333ce348fce105) Allow sending message to route that starts with us (#2585)
- [f23e2c5](https://github.com/ACINQ/eclair/commit/f23e2c5fc5389368b9b049e896d527a1cb0cade3) Clarify contribution guidelines around rebasing PRs (#2591)
- [01ec73b](https://github.com/ACINQ/eclair/commit/01ec73b1e384dd358761f3a912597c217de492bd) Store `fundingParams` with the `sharedTx` (#2592)
- [a54ae22](https://github.com/ACINQ/eclair/commit/a54ae2200b73577856b4f1e68ea125e70a385d05) Remove unnecessary dust limit hack in interactive-tx (#2594)
- [6483896](https://github.com/ACINQ/eclair/commit/6483896b3b9b0f63936aeab8b9efe100dc86f0c8) Add `ChannelAborted` event (#2593)
- [202598d](https://github.com/ACINQ/eclair/commit/202598d14e198cc9d4c6c9152a98bd224d1fbc05) Introduce a specific funding status for zeroconf (#2598)
- [6904283](https://github.com/ACINQ/eclair/commit/69042835a2c6fcd3db14fbeeebabe2ef2016b552) Handle next remote commit in ReplaceableTxPublisher (#2600)
- [d4c32f9](https://github.com/ACINQ/eclair/commit/d4c32f99dda1121d068a583968ab77ffd307c004) Add support for paying offers (#2479)
- [ddcb978](https://github.com/ACINQ/eclair/commit/ddcb978ead285c963c47931ce5900a7724eeeb99) Add support for plugins that intercept open channel messages  (#2552)
- [fcc52a8](https://github.com/ACINQ/eclair/commit/fcc52a84cabe9b95e92c060dcc6b50c9f0fbbc1f) Prepare `InteractiveTxBuilder` to support splicing (#2595)
- [e3bba3d](https://github.com/ACINQ/eclair/commit/e3bba3d022db5e03d61ff214c292e11f4eb61cbf) Remove reserve requirement on first commitment (#2597)
- [1a79e75](https://github.com/ACINQ/eclair/commit/1a79e75a635bd8abad5dc4f9c36dd6f61865befd) Replace `Commitments` with `MetaCommitments` (#2599)
- [a3c6029](https://github.com/ACINQ/eclair/commit/a3c6029e1d8d60da08800baf77c9bc38f83dd8ba) Limit number of RBF attempts during dual funding (#2596)
- [df590d8](https://github.com/ACINQ/eclair/commit/df590d8d3001714dae9d6d37e9b7f76a6f8c99dc) Make `updateLocalFundingStatus` method return `Either` (#2602)
- [1c9c694](https://github.com/ACINQ/eclair/commit/1c9c694fb5d69b47ad2954360eff772dc8bde4ce) Add new command to fix flaky tests in PendingChannelsRateLimiterSpec (#2606)
- [a52a10a](https://github.com/ACINQ/eclair/commit/a52a10a0404cf908d352315a7a49348ca80847cc) Update Bolt 3 tests (#2605)
- [f4326f4](https://github.com/ACINQ/eclair/commit/f4326f4b262e2685b0dc231dd213b72319d2740f) Use bitcoin-lib 0.27 (#2612)
- [e1cee96](https://github.com/ACINQ/eclair/commit/e1cee96c120c83839e0776a3421edf73120c43ba) Fix opportunistic zero-conf (#2616)
- [732eb31](https://github.com/ACINQ/eclair/commit/732eb3168172808c72bf8dcfa672ff893435c19d) Add limit for incoming connections from peers without channels (#2601)
- [df0e712](https://github.com/ACINQ/eclair/commit/df0e7121ef61f07ca99bc952efa17b3e39960139) Add offer manager (#2566)
- [dcedecc](https://github.com/ACINQ/eclair/commit/dcedeccb05a1cca7357de60e319ff61f87d8c14f) Rework responses to channel open and rbf (#2608)
- [e383d81](https://github.com/ACINQ/eclair/commit/e383d81de8512d91379c18d464fcc07713128610) Add `listreceivedpayments` RPC call (#2607)
- [db15beb](https://github.com/ACINQ/eclair/commit/db15beb015153e3d7812ff0d1330adfb928dbd79) Add t-bast's GPG key to SECURITY.md (#2621)
- [3a95a7d](https://github.com/ACINQ/eclair/commit/3a95a7deb5a4afb4735d8dd63f6bfd2f5af51705) Store channel state after sending `commit_sig` (#2614)
- [6d7b0fa](https://github.com/ACINQ/eclair/commit/6d7b0fae5787cbc1c24a8c71cbf02ccbdbd7a330) Remove `max-funding-satoshis` config (#2624)
- [daf947f](https://github.com/ACINQ/eclair/commit/daf947fb600db4ed2eee6096948cf73626b475f1) Allow negative contributions in `InteractiveTxBuilder` (#2619)
- [de6d3c1](https://github.com/ACINQ/eclair/commit/de6d3c17099c4c73c5de3657b4657c2cbb05792b) Add support for splices (#2584)
- [a8471df](https://github.com/ACINQ/eclair/commit/a8471df1563779ac8d446ddeff0ce7d98db2a976) Update `tx_signature` witness codec (#2633)
- [71568ca](https://github.com/ACINQ/eclair/commit/71568cae58c0aebd176e83babdb4239aaed82058) Dynamic funding pubkeys (#2634)
- [3973ffa](https://github.com/ACINQ/eclair/commit/3973ffaee1a444cf2124bf8e29cc1ee46094967c) Onion message test vector (#2628)
- [46149c7](https://github.com/ACINQ/eclair/commit/46149c7ed2053ad75a558ab0cd6239480becdcb6) Add padding to blinded payment routes (#2638)
- [36745a6](https://github.com/ACINQ/eclair/commit/36745a63bbebdb5ea0007f134e6c6ab803cac59a) Better validation of onion message payloads (#2631)
- [0d3de8f](https://github.com/ACINQ/eclair/commit/0d3de8f3778240afe8b2729f51495a150f06cbbe) Fix test to prevent error from timeout waiting for no messages (#2640)
- [25b92da](https://github.com/ACINQ/eclair/commit/25b92da0b049d0d1af45eafa8898888711198c36) Pass remote address to InterceptOpenChannelPlugin (#2641)
- [a58b7e8](https://github.com/ACINQ/eclair/commit/a58b7e8fa818fc84e2d6af09d5a601ae0670c0b5) Use tlv type 0 for next_funding_txid (#2637)
- [25f4cd2](https://github.com/ACINQ/eclair/commit/25f4cd2df425abbe7e49207375d707fde817abf6) Remove `minDepth` from `InteractiveTxParams` (#2635)
- [a010750](https://github.com/ACINQ/eclair/commit/a010750de8c1d81fa9432227b12e5860c7366405) Minor fixes for splices (#2647)
- [15e4986](https://github.com/ACINQ/eclair/commit/15e4986f3f63c7f302efd3d89088b32234ca24dd) Cleanup `ChannelKeyManager` (#2639)
- [396c84d](https://github.com/ACINQ/eclair/commit/396c84d6a22695d1ddac22d8bdd0a1467a24511d) Included doc folder in the assembly zip (#2604)
- [ee63c65](https://github.com/ACINQ/eclair/commit/ee63c65a1c778c14ce5849867cefd37fd2558b62) Add `cpfp-bump-fees` API (#1783)
- [77b3337](https://github.com/ACINQ/eclair/commit/77b333731f618395f33d69d1fe0aba2c86cdba58) Use a `tx_hash` instead of `txid` in all lightning messages (#2648)
- [7bf2e8c](https://github.com/ACINQ/eclair/commit/7bf2e8c67f8a2259300c65642d5375bb9bf92748) Remove restriction to regtest/testnet (#2652)
- [9beccce](https://github.com/ACINQ/eclair/commit/9beccce30095da5bddfcf39cb300a69f779e1172) Define `channelReserve()` methods in `ChannelParams` (#2653)
- [14cbed9](https://github.com/ACINQ/eclair/commit/14cbed9b1213d6c56e418571acbec3606455870b) Fix JSON Postgres index on channel's `remote_node_id` (#2649)
- [55a985a](https://github.com/ACINQ/eclair/commit/55a985adc81097ef54e94ff19180e6c1def5b709) Fix channels DB migration (#2655)
- [fa985da](https://github.com/ACINQ/eclair/commit/fa985da59f5bed4278156704984c0934f059d172) Reject unreasonably low splice feerate (#2657)
- [c73db84](https://github.com/ACINQ/eclair/commit/c73db8479c7a7ce05d61d2e31935d1088236dd01) Ignore lnd's internal errors (#2659)
- [50178be](https://github.com/ACINQ/eclair/commit/50178be6fa8d4faf3eeae53aaf0fa9323ad177a4) Update to bitcoind 23.2 (#2664)
- [f2aa0cc](https://github.com/ACINQ/eclair/commit/f2aa0cc003d4badb3f30a13b5f8e769f7919120a) Use bitcoin-lib 0.28 (#2658)
- [adaad5e](https://github.com/ACINQ/eclair/commit/adaad5eee6af45856995fd37f13308d0e4b9746d) Fix interpretation of option_onion_messages (#2670)
- [3fed408](https://github.com/ACINQ/eclair/commit/3fed40812ac8c6858d66a7108f7e78c664c67371) Update Kamon (#2665)
- [2c01915](https://github.com/ACINQ/eclair/commit/2c01915af01e5caa1cc358bd0f80e6a4357a8f2f) Add message size metric (#2671)
- [4713a54](https://github.com/ACINQ/eclair/commit/4713a541b6c9e7e9bd1155641e3375f2b5a15abf) Relax reserve requirements on HTLC receiver (#2666)
- [aaad2e1](https://github.com/ACINQ/eclair/commit/aaad2e1d61bb49488a447724ff9fe5a1ae463094) Accept closing fee above commit fee (#2662)
- [835b33b](https://github.com/ACINQ/eclair/commit/835b33b2b8ab6e39ff87fff652b880b19a5a2c8f) Add upper bound on fees paid during force-close (#2668)
- [0fa4453](https://github.com/ACINQ/eclair/commit/0fa44534d3c4e738268ec436bd9f1d8a6fcc6caf) Ignore non-relayed incoming HTLCs when closing (#2672)
- [41b8d5c](https://github.com/ACINQ/eclair/commit/41b8d5cacdd5c469f58f2b34cd3e22261b632805) Fix Autoprobe dummy invoice (#2661)
- [84f1d03](https://github.com/ACINQ/eclair/commit/84f1d03970655571cee3f82943785aad51e3cd4b) Fix splice reconnection while signing (#2673)
- [71968d0](https://github.com/ACINQ/eclair/commit/71968d06167bb88f7960b6a7891a62d744dc4b35) More robust channels timestamps (#2674)
- [e7b4631](https://github.com/ACINQ/eclair/commit/e7b46314cc47f96316770326698e478a154d9510) (Minor) refactor tlvs at connection reestablish (#2675)
- [46d1c73](https://github.com/ACINQ/eclair/commit/46d1c73889d561e7008933abb79a3d3ced073ec6) Always store remote `commit_sig` in interactive tx  (#2681)
- [37eb142](https://github.com/ACINQ/eclair/commit/37eb1420dc64d68ff394d81b83b9098bf4efa2d2) Add `closedchannels` RPC (#2642)
- [53872ea](https://github.com/ACINQ/eclair/commit/53872eaaa0fc3d4c44d731681863ab2c9b2992cc) (Minor) Add json type hint for `WAIT_FOR_DUAL_FUNDING_SIGNED` (#2682)
- [5505967](https://github.com/ACINQ/eclair/commit/55059678c008616a52591dd00f7f5ffaf6643495) Make shared transaction codecs more future-proof (#2679)
- [ef277f0](https://github.com/ACINQ/eclair/commit/ef277f075e943c8cf87cf07611a363138143b343) Increase default max-cltv value (#2677)
- [5ab8471](https://github.com/ACINQ/eclair/commit/5ab84712bf83d3c1afb4fd3f2a7bdedb58aa9361) Check bitcoind version before initializing DB (#2660)
- [42dfa9f](https://github.com/ACINQ/eclair/commit/42dfa9f535b3762aaa427d0e250998662fc20617) Handle invoice with amounts larger than 1btc (#2684)
- [ef77198](https://github.com/ACINQ/eclair/commit/ef77198650a903316786f715e5457734671b55ea) Ignore outgoing connection requests if front not ready (#2683)
- [b084d73](https://github.com/ACINQ/eclair/commit/b084d73e96a9602d611e76f7756167be9c3396df) Add `maxFeeMsat` parameter to `sendtoroute` RPC call (#2626)
- [05ef2f9](https://github.com/ACINQ/eclair/commit/05ef2f95524eb5ce609f6fb0a4e5b4a28ce38b9a) Fix blinded path `min_final_expiry_delta` check (#2678)
- [faebbfa](https://github.com/ACINQ/eclair/commit/faebbfae153254f401bc0eae52b6482e84744b79) Move channel collector inside Peer actor (#2688)
- [f184317](https://github.com/ACINQ/eclair/commit/f1843178b14cb50eb43dbc35bcdbd6c62de8ef8d) Fix problems and add tests for pending channels rate limiter  (#2687)
- [303c1d4](https://github.com/ACINQ/eclair/commit/303c1d45e153ec086eab53dc6b3779305cabb41e) Fix failing test `PendingChannelsRateLimiter` and clarify other tests (#2691)
- [878eb27](https://github.com/ACINQ/eclair/commit/878eb276b031705e48cddf3f01f81707fd5f73cc) Update maven version (#2690)
- [1105a0a](https://github.com/ACINQ/eclair/commit/1105a0a7bed69d814190072bf8b419240d55fd85) Update docker gradle checksum (#2692)
- [fe9f32b](https://github.com/ACINQ/eclair/commit/fe9f32bdf13bb103cf944f31e9b0e89e287b8b00) Minor updates on `PeerReadyNotifier` (#2695)
- [3a351f4](https://github.com/ACINQ/eclair/commit/3a351f4d5dce3fca3d4656d6369031a5d3a65afc) Never serialize ActorRef (#2697)
