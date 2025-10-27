# Eclair v0.13.1

This release contains database changes to prepare for the removal of pre-anchor channels.
Closed channels will be moved to a new table on restart, which may take some time, but will only happen once.

:warning: Note that you will need to run the v0.13.0 release first to migrate your channel data to the latest internal encoding.

This is the last release of eclair where channels that don't use anchor outputs will be supported.
If you have channels that don't use anchor outputs, you should close them now.
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

:warning: This release also updates the dependency on Bitcoin Core to v29.x (we recommend using v29.2).

## Major changes

### Move closed channels to dedicated database table

We previously kept closed channels in the same database table as active channels, with a flag indicating that it was closed.
This creates performance issues for nodes with a large history of channels, and creates backwards-compatibility issues when changing the channel data format.

We now store closed channels in a dedicated table, where we only keep relevant information regarding the channel.
When restarting your node, the channels table will automatically be cleaned up and closed channels will move to the new table.
This may take some time depending on your channels history, but will only happen once.

### Remove support for legacy channel codecs

We remove the code used to deserialize channel data from versions of eclair prior to v0.13.
Node operators running a version of `eclair` older than v0.13 must first upgrade to v0.13.0 to migrate their channel data, and then upgrade to the latest version.

### Update minimal version of Bitcoin Core

With this release, eclair requires using Bitcoin Core 29.x.
Newer versions of Bitcoin Core may be used, but have not been extensively tested.

### New MPP splitting strategy

Eclair can send large payments using multiple low-capacity routes by sending as much as it can through each route (if `randomize-route-selection = false`) or some random fraction (if `randomize-route-selection = true`).
These splitting strategies are now specified using `mpp.splitting-strategy = "full-capacity"` or `mpp.splitting-strategy = "randomize"`.
In addition, a new strategy is available: `mpp.splitting-strategy = "max-expected-amount"` will send through each route the amount that maximizes the expected delivered amount (amount sent multiplied by the success probability).

Eclair's path-finding algorithm can be customized by modifying the `eclair.router.path-finding.experiments.*` sections of your `eclair.conf`.
The new `mpp.splitting-strategy` goes in these sections, or in `eclair.router.path-finding.default` from which they inherit.

### Configuration changes

No notable changes.

### API changes

- the `closedchannels` API now returns human-readable channel data

### Miscellaneous improvements and bug fixes

No notable changes.

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

- [3abc17f](https://github.com/ACINQ/eclair/commit/3abc17f9aa9eb3354e1616ff056f1511015a3ced) Back to dev (#3155)
- [f029584](https://github.com/ACINQ/eclair/commit/f02958467214dbe88cdaeeacbd30bc0a2627d161) Update Bitcoin Core to v29.1 (#3153)
- [ae3e44b](https://github.com/ACINQ/eclair/commit/ae3e44be7066c6ed45b8b8021d5de95e086954da) Remove legacy channel codecs and DB migrations (#3150)
- [a7e57ea](https://github.com/ACINQ/eclair/commit/a7e57ea7c8af1876067af78d98f5abb0e6e6d0fa) Update taproot commit weight to match `lnd` (#3158)
- [d3ac75d](https://github.com/ACINQ/eclair/commit/d3ac75dfb5fb0c07bfb43326ad4c11e77cd209d7) Fix flaky `ReputationRecorder` test (#3166)
- [c13d530](https://github.com/ACINQ/eclair/commit/c13d530f3283d5741c2d6c4efa055896a907a041) Fix flaky test in `OfferPaymentSpec` (#3165)
- [379abc5](https://github.com/ACINQ/eclair/commit/379abc55b76668e83a7144f225e9e9a52c260c88) Resign next remote commit on reconnection (#3157)
- [e351320](https://github.com/ACINQ/eclair/commit/e351320503faf79bc60c48e86a023c6e84436cf7) Increase timeout for flaky onion message tests (#3167)
- [76f8d53](https://github.com/ACINQ/eclair/commit/76f8d53aea564d169e4abc774bc098e21ef53366) Correctly fill PSBT for taproot `interactive-tx` (#3169)
- [9f00ebf](https://github.com/ACINQ/eclair/commit/9f00ebf98923455c53f500c3ba34bd35f0e505cf) Always count local CLTV delta in route finding (#3174)
- [08a1fc6](https://github.com/ACINQ/eclair/commit/08a1fc6603c7614164bb32f2628d5100de5061f2) Kill the connection if a peer sends multiple ping requests in parallel (#3172)
- [fa1b0ee](https://github.com/ACINQ/eclair/commit/fa1b0eef5d5f9a5075860c5868a5cb45b9b3980a) Remove `PaymentWeightRatios` from the routing config (#3171)
- [abe2cc9](https://github.com/ACINQ/eclair/commit/abe2cc9cc38df82394f793e5924f8b802a1095fe) Reject offers with some fields present but empty (#3175)
- [ad8b2e3](https://github.com/ACINQ/eclair/commit/ad8b2e3b36a9e09981fc34710d0ba66a2368fb91) Add "phoenix zero reserve" feature bit (#3176)
- [5b4368a](https://github.com/ACINQ/eclair/commit/5b4368a65bee501c80cb8681da171d704db55888) Fix encoding of channel type TLV in splice_init/splice_ack (#3178)
- [90778ca](https://github.com/ACINQ/eclair/commit/90778ca483746143bcab9cb086bb009cd89a5562) Smaller default value for `peer-connection.max-no-channels` (#3180)
- [d04ed3d](https://github.com/ACINQ/eclair/commit/d04ed3dc34b4a43a912c49e09f47dbf4354b933c) Deduplicate closing balance during mutual close (#3182)
- [2b39bf6](https://github.com/ACINQ/eclair/commit/2b39bf622f14cfe227cfc225c48423145e54e5c6) Update `bitcoin-lib` (#3179)
- [16a309e](https://github.com/ACINQ/eclair/commit/16a309e49e83e094fb02063776a324c6a4f31f9c) Add `DATA_CLOSED` class when active channel is closed (#3170)
- [56a3acd](https://github.com/ACINQ/eclair/commit/56a3acdf625ebb455f1c0cbb06339694f2554b49) Add `zero-conf` test tag for Phoenix taproot tests (#3181)
- [cc75b13](https://github.com/ACINQ/eclair/commit/cc75b135f403ab7b4bb8bf09c75a8c4249fa66d9) Nits (#3183)
- [d1863f9](https://github.com/ACINQ/eclair/commit/d1863f9457988e2b78d0c3460b5bb09ae2d610c1) Create fresh shutdown nonce on reconnection (#3184)
- [3208266](https://github.com/ACINQ/eclair/commit/32082666ecf0bca6d0ab87185ef0f4c32a94bb44) Use bitcoin-lib 0.44 (#3185)
- [51a144c](https://github.com/ACINQ/eclair/commit/51a144c8a231b4fe4df6dfb6f51ce45a1188aed3) Split MPP by maximizing expected delivered amount (#2792)
- [409c7c1](https://github.com/ACINQ/eclair/commit/409c7c17148ef1d18d04a3e74739c2cdd89e87ca) Don't store anchor transaction in channel data (#3187)
- [0baddd9](https://github.com/ACINQ/eclair/commit/0baddd91b1901317dc74e7ebf3cb00fee3499536) Only store txs spending our commit outputs (#3188)
- [711c52a](https://github.com/ACINQ/eclair/commit/711c52ab717a55a87f2ea56d9f8115a61170e1d8) Avoid negative on-the-fly funding fee (#3189)
- [656a2fe](https://github.com/ACINQ/eclair/commit/656a2fe6859d426c6f47afa488ac88cd7cfda440) Update Bitcoin Core to v29.2 (#3190)
- [7372a87](https://github.com/ACINQ/eclair/commit/7372a8779e3b1c2bc14975adcbdd53bb7a216ccb) Update `bitcoin-lib` (#3193)
- [9771b2d](https://github.com/ACINQ/eclair/commit/9771b2d862ec779e6281152a3b93dd0d438a4d91) Configure bitcoind test instances to use bech32m addresses (#3195)
- [701f229](https://github.com/ACINQ/eclair/commit/701f2297d2e385d7125268a51e30a4acd5e0ff73) More flexible mixing of clearnet addresses and tor proxy (#3054)
