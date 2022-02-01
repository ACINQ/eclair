# Eclair v0.7.0

This release adds official support for two long-awaited lightning features: anchor outputs and onion messages.
Support for the PostreSQL database backend is also now production ready!

This release also includes a few bug fixes and many new (smaller) features.
It is fully compatible with 0.6.2 (and all previous versions of eclair).

Because this release changes the default type of channels that your node will use, make sure you read the release notes carefully!

## Major changes

### Anchor outputs activated by default

Experimental support for anchor outputs channels was first introduced in [eclair v0.4.2](https://github.com/ACINQ/eclair/releases/tag/v0.4.2).
Going from an experimental feature to production-ready required a lot more work than we expected!
What seems to be a simple change in transaction structure had a lot of impact in many unexpected places, and fundamentally changes the mechanisms to ensure funds safety.

When using anchor outputs, you will need to keep utxos available in your `bitcoind` wallet to fee-bump HTLC transactions when channels are force-closed.
Eclair will warn you via the `notifications.log` file when your `bitcoind` wallet balance is too low to protect your funds against malicious channel peers.
Eclair will wait for you to add funds to your wallet and automatically retry, but you may be at risk in the meantime if you had pending payments at the time of the force-close.

We recommend activating debug logs for the transaction publication mechanisms.
This will make it much easier to follow the trail of RBF-ed transactions, and shouldn't be too noisy unless you constantly have a lot of channels force-closing.
To activate these logs, simply add the following line to your `logback.xml`:

```xml
<logger name="fr.acinq.eclair.channel.publish" level="DEBUG"/>
```

You don't even need to restart eclair, it will automatically pick up the logging configuration update after a short while.

If you don't want to use anchor outputs, you can disable the feature in your `eclair.conf`:

```conf
eclair.features.option_anchors_zero_fee_htlc_tx = disabled
```

### Postgres database backend graduates to production-ready

Postgres support was introduced in Eclair 0.4.1 as beta, and has since been improved continuously over several versions. It is now production-ready and is the recommended database backend for larger nodes.

Postgres offers superior administration capabilities, advanced JSON queries over channel data, streaming replication, and more, which makes it a great choice for enterprise setups.

A [step-by-step migration guide](https://github.com/ACINQ/eclair/blob/master/docs/PostgreSQL.md#migrating-from-sqlite-to-postgres) from Sqlite to Postgres is provided for users who wish to do the switch.

Sqlite remains fully supported.

### Alternate strategy to avoid mass force-close of channels in certain cases

The default strategy, when an unhandled exception or internal error happens, is to locally force-close the channel. Not only is there a delay before the channel balance gets refunded, but if the exception was due to some misconfiguration or bug in eclair that affects all channels, we risk force-closing all channels.

This is why an alternative behavior is to simply log an error and stop the node. Note that if you don't closely monitor your node, there is a risk that your peers take advantage of the downtime to try and cheat by publishing a revoked commitment. Additionally, while there is no known way of triggering an internal error in eclair from the outside, there may very well be a bug that allows just that, which could be used as a way to remotely stop the node (with the default behavior, it would "only" cause a local force-close of the channel).

### Separate log for important notifications

Eclair added a new log file (`notifications.log`) for important notifications that require an action from the node operator.
Node operators should watch this file very regularly.

An event is also sent to the event stream for every such notification.
This lets plugins notify the node operator via external systems (push notifications, email, etc).

### Support for onion messages

Eclair now supports the `option_onion_messages` feature (see <https://github.com/lightning/bolts/pull/759)>.
This feature is enabled by default: eclair will automatically relay onion messages it receives.

By default, eclair will only accept and relay onion messages from peers with whom you have channels.
You can change that strategy by updating `eclair.onion-messages.relay-policy` in your `eclair.conf`.

Eclair applies some rate-limiting on the number of messages that can be relayed to and from each peer.
You can choose what limits to apply by updating `eclair.onion-messages.max-per-peer-per-second` in your `eclair.conf`.

Whenever an onion message for your node is received, eclair will emit an event, that can for example be received on the websocket (`onion-message-received`).

You can also send onion messages via the API.
This will be covered in the API changes section below.

To disable the feature, you can simply update your `eclair.conf`:

```conf
eclair.features.option_onion_messages = disabled
```

### Support for `option_payment_metadata`

Eclair now supports the `option_payment_metadata` feature (see <https://github.com/lightning/bolts/pull/912>).
This feature will let recipients generate "light" invoices that don't need to be stored locally until they're paid.
This is particularly useful for payment hubs that generate a lot of invoices (e.g. to be displayed on a website) but expect only a fraction of them to actually be paid.

Eclair includes a small `payment_metadata` field in all invoices it generates.
This lets node operators verify that payers actually support that feature.

### Optional safety checks when using Postgres

When using postgres, at startup we optionally run a few basic safety checks, e.g. the number of local channels, how long since the last local channel update, etc. The goal is to make sure that we are connected to the correct database instance.

Those checks are disabled by default because they wouldn't pass on a fresh new node with zero channels. You should enable them when you already have channels, so that there is something to compare to, and the values should be specific to your setup, particularly for local channels. Configuration is done by overriding `max-age` and `min-count` values in your `eclair.conf`:

```conf
eclair.db.postgres.safety-checks
 {
  enabled = true
  max-age {
    local-channels = 3 minutes
    network-nodes = 30 minutes
    audit-relayed = 10 minutes
  }
  min-count {
    local-channels = 10
    network-nodes = 3000
    network-channels = 20000
  }
}
```

### API changes

#### Timestamps

All timestamps are now returned as an object with two attributes:

- `iso`: ISO-8601 format with GMT time zone. Precision may be second or millisecond depending on the timestamp.
- `unix`: seconds since epoch formats (seconds since epoch). Precision is always second.

Examples:

- second-precision timestamp:
  - before:

  ```json
  {
    "timestamp": 1633357961
  }
  ```

  - after

  ```json
  {
    "timestamp": {
      "iso": "2021-10-04T14:32:41Z",
      "unix": 1633357961
    }
  }
  ```

- milli-second precision timestamp:
  - before:

  ```json
  {
    "timestamp": 1633357961456
  }
  ```

  - after (note how the unix format is in second precision):

  ```json
  {
    "timestamp": {
      "iso": "2021-10-04T14:32:41.456Z",
      "unix": 1633357961
    }
  }
  ```
  
#### Sending onion messages

You can now send onion messages with `sendonionmessage`.

There are two ways to specify the recipient:

- when you're sending to a known `nodeId`, you must set it in the `--recipientNode` field
- when you're sending to an unknown node behind a blinded route, you must provide the blinded route in the `--recipientBlindedRoute` field (hex-encoded)

If you're not connected to the recipient and don't have channels with them, eclair will try connecting to them based on the best address it knows (usually from their `node_announcement`).
If that fails, or if you don't want to expose your `nodeId` by directly connecting to the recipient, you should find a route to them and specify the nodes in that route in the `--intermediateNodes` field.

You can send arbitrary content to the recipient, by providing a hex-encoded tlv in the `--content` field.

If you expect a response, you should provide a route from the recipient back to you in the `--replyPath` field.
Eclair will create a corresponding blinded route, and the API will wait for a response (or timeout if it doesn't receive a response).

#### Balance

The detailed balance json format has been slightly updated for channels in state `normal` and `shutdown`, and `closing`.

Amounts corresponding to incoming htlcs for which we knew the preimage were previously included in `toLocal`, they are
now grouped with outgoing htlcs amounts and the field has been renamed from `htlcOut` to `htlcs`.

#### Miscellaneous

This release contains many other API updates:

- `deleteinvoice` allows you to remove unpaid invoices (#1984)
- `findroute`, `findroutetonode` and `findroutebetweennodes` supports new output format `full` (#1969)
- `findroute`, `findroutetonode` and `findroutebetweennodes` now accept `--ignoreNodeIds` to specify nodes you want to be ignored in path-finding (#1969)
- `findroute`, `findroutetonode` and `findroutebetweennodes` now accept `--ignoreShortChannelIds` to specify channels you want to be ignored in path-finding (#1969)
- `findroute`, `findroutetonode` and `findroutebetweennodes` now accept `--maxFeeMsat` to specify an upper bound of fees (#1969)
- `getsentinfo` output includes `failedNode` field for all failed routes
- for `payinvoice` and `sendtonode`, `--feeThresholdSat` has been renamed to `--maxFeeFlatSat`
- for `open`, `--channelFlags` has been replaced by `--announceChannel`
- the `networkstats` API has been removed

Have a look at our [API documentation](https://acinq.github.io/eclair) for more details.

### Miscellaneous improvements and bug fixes

- Eclair now supports cookie authentication for Bitcoin Core RPC (#1986)
- Eclair echoes back the remote peer's IP address in `init` (#1973)
- Eclair now supports [circular rebalancing](https://github.com/ACINQ/eclair/blob/master/docs/CircularRebalancing.md)

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

That should generate `eclair-node/target/eclair-node-0.7.0-XXXXXXX-bin.zip` with sha256 checksums that match the one we provide and sign in `SHA256SUMS.asc`

(*) You may be able to build the exact same artefacts with other operating systems or versions of JDK 11, we have not tried everything.

## Upgrading

This release is fully compatible with previous eclair versions. You don't need to close your channels, just stop eclair, upgrade and restart.

## Changelog

- [57bf860](https://github.com/ACINQ/eclair/commit/57bf86044ec4e2ff09d9639e986d04f88fde2305) Back to Dev (#1993)
- [df63ea4](https://github.com/ACINQ/eclair/commit/df63ea47835af6c61dbf37933707752ed06812c1) Deprecation warning for relay fees config (#2012)
- [498e9a7](https://github.com/ACINQ/eclair/commit/498e9a7db1deb5274380ee8dd47e78fef95a854f) Remove CoinUtils.scala. (#2013)
- [b22b1cb](https://github.com/ACINQ/eclair/commit/b22b1cbea738102a7c63fdbf1366254ec0dffbfe) Fix API hanging on invalid remote params (#2008)
- [9057c8e](https://github.com/ACINQ/eclair/commit/9057c8e90a9b8301b575fd90ac50f123fc0d203f) Minor improvements (#1998)
- [b4d285f](https://github.com/ACINQ/eclair/commit/b4d285f1c4c2cccfcd8b052e451a3b428e8996b0) Proper types for UNIX timestamps (#1990)
- [6018988](https://github.com/ACINQ/eclair/commit/601898864d18316ba759fa8844a2d846c729819d) Check serialization consistency in all channel tests (#1994)
- [6b202c3](https://github.com/ACINQ/eclair/commit/6b202c392bc2fad31bc33affc40577dc7577ed3d) Add low-level route blinding features (#1962)
- [f3b1604](https://github.com/ACINQ/eclair/commit/f3b16047eb188395454ae1479c4fd62f12ca9cdb) Add API to delete an invoice (#1984)
- [93481d9](https://github.com/ACINQ/eclair/commit/93481d99433b35278fe1746dc089df58f8935a9a) Higher `walletpassphrase` timeout in tests (#2022)
- [bdef833](https://github.com/ACINQ/eclair/commit/bdef8337e89bf5f31e7cb76e700c6b51fadd673f) Additional parameters for findroute* API calls (#1969)
- [9274582](https://github.com/ACINQ/eclair/commit/9274582679f0b7cbce675182019ce82c75a85305) Balance: take signed fulfills into account (#2023)
- [4e9190a](https://github.com/ACINQ/eclair/commit/4e9190aaee9207ce83152fc89e90e2c5c041a7a1) Minor: higher timeout in payment fsm test (#2026)
- [28d04ba](https://github.com/ACINQ/eclair/commit/28d04ba7a79b6fa3a20a9f3afeee43a639fc74ea) Store blinding pubkey for introduction node (#2024)
- [570dc22](https://github.com/ACINQ/eclair/commit/570dc223da6a3a92f7cf90efe6d3299800f990c3) Fix flaky transaction published event test (#2020)
- [99a8896](https://github.com/ACINQ/eclair/commit/99a889636b0dd3dc5c587a82bc032e6089e9a30c) ignoreShortChannelIds should disable edges in both directions (#2032)
- [494e346](https://github.com/ACINQ/eclair/commit/494e346231c9016d4ee71b92b99b76d891b3aeed) Minor: put htlc info logs in debug (#2030)
- [765a0c5](https://github.com/ACINQ/eclair/commit/765a0c5436a8e2ec1f469f1b066910f1205c4c99) Add log file for important notifications (#1982)
- [1573f7b](https://github.com/ACINQ/eclair/commit/1573f7be05931bb722c085eda7577c1e9731f04e) EncryptedRecipientData TLV stream should not be length-prefixed (#2029)
- [e54aaa8](https://github.com/ACINQ/eclair/commit/e54aaa84bef071c189f3fcfd7fb0e687a0d0dcbb) API: fix default time boundaries (#2035)
- [c5fa39f](https://github.com/ACINQ/eclair/commit/c5fa39f75480e859c39bd3b5708123152a067c98) Front: stop the jvm after coordinated shutdown (#2028)
- [2e9f8d9](https://github.com/ACINQ/eclair/commit/2e9f8d9f9eea47df251698852b7eb695f271f078) Cookie-based authentication for Bitcoin Core RPC (#1986)
- [2c0c24e](https://github.com/ACINQ/eclair/commit/2c0c24e1e1565a33fee1e0a3adf69a5d0be582f2) Rework channel reestablish (#2036)
- [4f458d3](https://github.com/ACINQ/eclair/commit/4f458d356ce039b1d9b6ab361b71e07427c96ba2) Alternate strategy for unhandled exceptions (#2037)
- [9f65f3a](https://github.com/ACINQ/eclair/commit/9f65f3a3a9ebedfcc7186ad0422a58d477d1b090) Make compatibility code for `waitingSince` work on testnet (#2041)
- [1f613ec](https://github.com/ACINQ/eclair/commit/1f613ec7a328de6a4f6551aaa3ad31ec3c1cb8a6) Handle mutual close published from the outside (#2046)
- [f7a79d1](https://github.com/ACINQ/eclair/commit/f7a79d10b4ddb6f91a4c66983c7a1620cc572e72) Fix response for `updaterelayfee` (#2047)
- [3dc4ae1](https://github.com/ACINQ/eclair/commit/3dc4ae1099fa1a2a3bb3742955f3c61a5c6704bc) Refactor payment onion utilities (#2051)
- [b45dd00](https://github.com/ACINQ/eclair/commit/b45dd0078ecf56d88098a74e5f2887a20c8aa4c1) Refactor sphinx payment packet (#2052)
- [4ac8236](https://github.com/ACINQ/eclair/commit/4ac823620b5bc734b365aa7edce572c0e79d00a0) Remove dumpprivkey from tests (#2053)
- [083dc3c](https://github.com/ACINQ/eclair/commit/083dc3c8da0c78414e63ae001aba126cdef80662) Onion messages (#1957)
- [333e9ef](https://github.com/ACINQ/eclair/commit/333e9ef04f0c74ca54bcf41360f895e675a3b28e) Clarify route blinding types (#2059)
- [6cc37cb](https://github.com/ACINQ/eclair/commit/6cc37cbd4f8192162e916ddabe3a612fafed06a2) Simplify onion message codec (#2060)
- [fb96e5e](https://github.com/ACINQ/eclair/commit/fb96e5eb3f29bce372af1dd392e169fd1bb8b3f1) Add failed node ID field to `FailureSummary` (#2042)
- [59b4035](https://github.com/ACINQ/eclair/commit/59b403559bdfc0ba884503bb12a14846b7aa5d2d) Relay onion messages (#2061)
- [9792c72](https://github.com/ACINQ/eclair/commit/9792c725c777d18d538e16d55ac89a847ba8beef) Rename feeThresholdSat to maxFeeFlatSat (#2079)
- [4ad502c](https://github.com/ACINQ/eclair/commit/4ad502c4c897af8f37a62bb0dfc9504ff5c9c0fc) Abort HTLC tx publisher if remote commit confirms (#2080)
- [589e84f](https://github.com/ACINQ/eclair/commit/589e84feabcb623898d2cfa62ecac70453a24645) Support private channels in SendToRoute (#2082)
- [86aed63](https://github.com/ACINQ/eclair/commit/86aed633e9741f4f604edf0d9aa507bbd12b0cfe) Remove misleading claim-htlc-success log (#2076)
- [bacb31c](https://github.com/ACINQ/eclair/commit/bacb31cf8d6db9ea323f0366a859686b632411ca) Add channel type feature bit (#2073)
- [3003289](https://github.com/ACINQ/eclair/commit/3003289328c091527f248b118e9280657417481d) No error when updating the relay fees not in normal state (#2086)
- [a470d41](https://github.com/ACINQ/eclair/commit/a470d41a9592e34d8b3b27e209f4991ac0247a34) Write received onion messages to the websocket (#2091)
- [62cc073](https://github.com/ACINQ/eclair/commit/62cc073d670e9e7407e2a8c5819292f7a829346f) Remove network stats computation (#2094)
- [ee852d6](https://github.com/ACINQ/eclair/commit/ee852d6320b567f5c5e463bdb2c2e69f1ae011c8) (Minor) Handle disconnect request when offline (#2095)
- [40cc458](https://github.com/ACINQ/eclair/commit/40cc4580438691434c5bb22a87c79b23cbc8502a) Switchboard exposes peer information (#2097)
- [3451974](https://github.com/ACINQ/eclair/commit/34519749d240e2a00b9cdc1e9e9933feeaa9a51a) Raise default connection timeout values (#2093)
- [ac9e274](https://github.com/ACINQ/eclair/commit/ac9e274f0d1083319f70e08d495f20e27dd4cccd) Add message relay policies (#2099)
- [535daec](https://github.com/ACINQ/eclair/commit/535daec0657acf2e05f5c8fca046ae9ab3f70434) Fix unhandled event in DbEventHandler (#2103)
- [4ebc8b5](https://github.com/ACINQ/eclair/commit/4ebc8b5d6b64746b8828a81765d7860c0dda5c80) Notify node operator on low fee bumping reserve (#2104)
- [576c0f6](https://github.com/ACINQ/eclair/commit/576c0f6e39783755ada0efe079e2ec3164a01842) Increase timeout onion message integration tests (#2106)
- [3d88c43](https://github.com/ACINQ/eclair/commit/3d88c43b12ab1e0c4fd4030f408119df4e4c0136) Kill idle peers (#2096)
- [8ff7dc7](https://github.com/ACINQ/eclair/commit/8ff7dc713a5024397fd687dbd2c9ccdae6aac567) Avoid default arguments in test helpers (#2108)
- [7e7de53](https://github.com/ACINQ/eclair/commit/7e7de53d1de4cba79280f08f3d4d6cf227ba42d2) Filter out non-standard channels from `channelInfo` API (#2107)
- [ec13281](https://github.com/ACINQ/eclair/commit/ec132810a574a2832933b9b2432fb50a7abd41b7) Add nightly build with bitcoind master (#2027)
- [c370abe](https://github.com/ACINQ/eclair/commit/c370abe5c3c0333c1e650383da736c5f0721b7be) Rate limit onion messages (#2090)
- [8afb02a](https://github.com/ACINQ/eclair/commit/8afb02ab97b216e7d9fd67454830b84d19115fc6) Add payment hash to `ClaimHtlcSuccessTx` (#2101)
- [bf0969a](https://github.com/ACINQ/eclair/commit/bf0969a3081800713b488e432c5fd99b5506c8a6) Add defenses against looping paths (#2109)
- [7693696](https://github.com/ACINQ/eclair/commit/76936961f9cd6c794bab90879269372f5723c7ae) Fix potential loop in path-finding (#2111)
- [0b807d2](https://github.com/ACINQ/eclair/commit/0b807d257a1ee1d33830fb91a11aa47ba7d3dcb0) Set max timestamp in API call to 9999/12/31 (#2112)
- [2827be8](https://github.com/ACINQ/eclair/commit/2827be875d2ebb5f941c7e2a876a214469d698be) Make `ClaimHtlcTx` replaceable (#2102)
- [1fd6344](https://github.com/ACINQ/eclair/commit/1fd6344a5d2a89bf7993193678520f2dc27a60bd) Define 9999-12-31 as max value for timestamps (#2118)
- [fda3818](https://github.com/ACINQ/eclair/commit/fda3818c6a38688dbc19a8bd7f0e10632876075c) Rename raw tx publisher to final tx publisher (#2119)
- [7421098](https://github.com/ACINQ/eclair/commit/7421098c4455efb2548d620ce2197bab610a2a69) Process replies to onion messages (#2117)
- [27579a5](https://github.com/ACINQ/eclair/commit/27579a5786db083700bb668a93eb86583bdf9d74) (Minor) Use `sys` package instead of `System` when applicable (#2124)
- [6e88532](https://github.com/ACINQ/eclair/commit/6e88532d18874f04b964029ce00b4008cbee3102) Add support for `option_payment_metadata` (#2063)
- [7f7ee03](https://github.com/ACINQ/eclair/commit/7f7ee0389926279b2d3277f2914f61e19c99a1f2) Handle onion creation failures (#2087)
- [546ca23](https://github.com/ACINQ/eclair/commit/546ca23984d3010e22b7bb1695220a9eed3cb00d) Activate onion messages (#2133)
- [27e29ec](https://github.com/ACINQ/eclair/commit/27e29ecebf42da223e53bfd9f1b4da3dd65953f3) Fix onion message tests by changing the rate limiter (#2132)
- [88dffbc](https://github.com/ACINQ/eclair/commit/88dffbc28c48ebbcf977333a84f9af7ff128b39a) Publish docker images (#2130)
- [40f7ff4](https://github.com/ACINQ/eclair/commit/40f7ff4034f3df32f5c5dc4f6a6af30d408cfcc5) Replaceable txs fee bumping (#2113)
- [58f9ebc](https://github.com/ACINQ/eclair/commit/58f9ebc6243563bee187478ab8d35e4315eb856d) Use BlockHeight everywhere (#2129)
- [52a6ee9](https://github.com/ACINQ/eclair/commit/52a6ee905969b66b0d912129bd29e3b58acea43d) Rename TxPublishLogContext (#2131)
- [e2b1b26](https://github.com/ACINQ/eclair/commit/e2b1b26919ed886b0e178c55a001585a38abfb16) Activate anchor outputs (#2134)
- [2f07b3e](https://github.com/ACINQ/eclair/commit/2f07b3ec0b8934bede851af93dcdb6145be585fb) (Minor) Nits (#2139)
- [f8d507b](https://github.com/ACINQ/eclair/commit/f8d507bbdd935ef5ca4eb8ce280300fdb3c1996a) Send remote address in init (#1973)
- [c180ca2](https://github.com/ACINQ/eclair/commit/c180ca2ef10531307d3441aad5b83f139eeb1c40) Postgres: add safety checks at startup (#2140)
- [0a37213](https://github.com/ACINQ/eclair/commit/0a37213483e75fbf1974ce4f6ec830596a79ae2b) Add randomization on node addresses (#2123)
- [85f9455](https://github.com/ACINQ/eclair/commit/85f94554c0c513011cabd0149d581f3d7505686f) Fix error logs formatting in TxPublisher (#2136)
- [2d64187](https://github.com/ACINQ/eclair/commit/2d641870731075f9c4ee5eef2e84f9368f410464) More aggressive confirmation target when low on utxos (#2141)
- [d59d434](https://github.com/ACINQ/eclair/commit/d59d4343c82cdd6fecddec5e44ec2cd95dbd1571) Improve `getsentinfo` accuracy (#2142)
- [f3604cf](https://github.com/ACINQ/eclair/commit/f3604cffaf9df9c8fffd5142f5dc9387fcfc74b9) Document onchain wallet backup. (#2143)
- [8758d50](https://github.com/ACINQ/eclair/commit/8758d50df26ba2e7007f5ab4243eaf556fd32d35) Update cluster documentation [ci skip] (#2122)
- [75ef66e](https://github.com/ACINQ/eclair/commit/75ef66e54c387ea7833465d8d34e8f6627bcbff2) Front: use IMDSv2 to retrieve the instance ip (#2146)
- [57c2cc5](https://github.com/ACINQ/eclair/commit/57c2cc5df907f2d848daf3d40cf2ceb6ff603ede) Type `ChannelFlags` instead of using a raw `Byte` (#2148)
- [ffecd62](https://github.com/ACINQ/eclair/commit/ffecd62cc133bb64aa916ed8d066b8ab6d7597ec) Move channel parameters to their own conf section (#2149)
- [1f6a7af](https://github.com/ACINQ/eclair/commit/1f6a7afd47e989b7c083ae9da075a9e1dd88e457) Have sqlite also write to jdbc url file (#2153)
- [0333e11](https://github.com/ACINQ/eclair/commit/0333e11c79de838cfd2bd7946d0c29b2bae3a8d6) Database migration Sqlite->Postgres (#2156)
