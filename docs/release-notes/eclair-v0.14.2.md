# Eclair v0.14.2

This is a patch release that contains several bug fixes.
We highly recommend upgrading, as some of these issues can be exploited by malicious nodes.

We now explicitly document that `bitcoind` should run on the same machine as `eclair`,
or that a secure tunnel (providing encryption and authentication) must be setup between `eclair` and `bitcoind`.
If you are running `bitcoind` on a remote machine without a secure tunnel, you should rework your setup.

## Major changes

### Add support for fulfillment payload

We include support for relaying a fulfillment payload and authenticating it with the attribution data.
This was added to the BOLTs in https://github.com/lightning/bolts/pull/1344. This will be useful in the
future to allow payment recipient to atomically send back some data to the sender on payment success.

### Advertize when onion messages require channels

We add support for the `option_onion_messages_only_channels` feature that was recently added to the BOLTs
(see https://github.com/lightning/bolts/pull/1343 for more details), which lets us tell the network that
we will only relay onion messages from peers with whom we already have channels.

This was supported previously by setting your relay policy in `eclair.conf` to:

```conf
eclair.onion-messages.relay-policy = "channels-only"
```

This `relay-policy` field has been removed from the configuration. If you wish to only relay onion messages
from peers with whom you have channels, you should set the corresponding features in your `eclair.conf`:

```conf
eclair.features.option_onion_messages = disabled
eclair.features.option_onion_messages_only_channels = optional
```

### Restrict the number of pending unauthenticated incoming connections

Peers that open a connection but never complete the BOLT 8 handshake consume resources on our node (memory, file
descriptors and CPU). We now bound how many of those we're willing to keep around:

```conf
eclair.peer-connection.max-pending-incoming-connections = 500
eclair.peer-connection.pending-connection-min-age = 1 second
eclair.peer-connection.pending-connection-accept-delay = 100 milliseconds
```

When we reach `max-pending-incoming-connections`, we drop the oldest connection that hasn't authenticated yet to make
room for the new one, and then wait for `pending-connection-accept-delay` before accepting the next one, which lets the
kernel backlog absorb (and discard) the excess connection attempts. We never drop a pending connection that is more
recent than `pending-connection-min-age`: this guarantees that honest peers always have enough time to complete the
handshake, even while we're being flooded with connection attempts. When every pending connection is that recent, we
reject the incoming connection instead.

The default values shouldn't be reached by honest nodes. Note that this is only a last-resort safety net: DDoS
protection is much more efficiently handled at the network layer (for example by a cloud provider). Setting
`max-pending-incoming-connections = 0` disables this limit entirely.

### Configuration changes

#### Gossip queries

A `query_channel_range` message is a few dozen bytes to send, but answering one requires scanning our whole routing
table and sending back megabytes of data. We now limit how many of them we accept from a given peer, which can be
configured with:

```conf
eclair.router.sync.max-queries-per-second = 10
```

Honest peers only need to send a handful of `query_channel_range` per connection, so this shouldn't have any impact on
normal synchronization.

Our peers decide when a routing table synchronization ends, so we now bound the number of `query_short_channel_ids` that
we're willing to buffer for a given peer, which can be configured with:

```conf
eclair.router.sync.max-queries-per-sync = 2000
```

At the default `channel-query-chunk-size` this covers 200 000 channels, which is several times the current size of the
network.

#### Tor configuration

`eclair.tor.auth` now defaults to `safecookie` instead of `password`.

Password authentication sends our tor control password in cleartext to whatever process is listening on the control
port, without any way of verifying that it is really tor. It is now rejected when `eclair.tor.host` isn't a local
address: if you run tor on another host or in a separate container, switch to `eclair.tor.auth = safecookie`, which
authenticates the tor server, or move the control port to the host running eclair. Note that the onion private key is
sent to the control port on every startup, so a remote control port exposes it to the network regardless of the
authentication method used.

### API changes

Nothing noteworthy.

### Miscellaneous improvements and bug fixes

- We now ignore gossip queries that are for another chain instead of answering them
- Answering channel range queries is much cheaper: we cache the timestamps and checksums of our channel updates instead of recomputing them for the whole routing table on every incoming query
- We ignore duplicate `short_channel_id`s in a `query_short_channel_ids`, and reject queries whose query flags don't cover every `short_channel_id`, or that are sent before we've replied to the previous one
- We ignore `reply_short_channel_ids_end` messages that don't answer one of our queries: a peer could previously send us one to make us drop our synchronization state and ignore the rest of its replies
- We now disconnect peers that authenticate but whose connection is never initialized: such connections previously stayed around forever

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

- [ee7d9f6](https://github.com/ACINQ/eclair/commit/ee7d9f6dc53a4ee395f2486ab7ec8a13bd8b6a68) Back to dev (#3339)
- [54912ef](https://github.com/ACINQ/eclair/commit/54912ef29a3e023324bf283debdefe40bfaf998c) Correctly handle unknown flags in `channel_update` (#3341)
- [b6b89e0](https://github.com/ACINQ/eclair/commit/b6b89e0267aec2dc4426caafeb5ec8b70c613128) Improve liquidity ads codec performance (#3344)
- [0c4ddc4](https://github.com/ACINQ/eclair/commit/0c4ddc4d3b98417f98033c31faa942732e48abb2) Multiple bug fixes found by AI scanning (#3346)
- [95878dd](https://github.com/ACINQ/eclair/commit/95878ddb790335f188a04d18227f97bf3f250284) Gossip queries fixes and improvements (#3345)
- [b5d2082](https://github.com/ACINQ/eclair/commit/b5d2082e94037967e59453e4034d1ff4bbcabe02) Improve defenses against malicious bitcoin RPC endpoint (#3343)
- [fa10b0e](https://github.com/ACINQ/eclair/commit/fa10b0e0d664379f4b7deaba7a50271e97931466) Several improvements suggested by Loupe (#3348)
- [17edd76](https://github.com/ACINQ/eclair/commit/17edd765b5b717246479a636dc2a3f20ed2a2ac5) Harden permissions of seeds and datadir (#3340)
- [8436c50](https://github.com/ACINQ/eclair/commit/8436c50e9bc62a83192aa91de20d1a49172d062c) Use `min_final_expiry_delta` in trampoline test handler (#3353)
- [48ff28a](https://github.com/ACINQ/eclair/commit/48ff28abdf9739ec2dc0d1cef12d9eb011d11dc2) Fix several on-the-fly-funding bugs (#3351)
- [32b93b1](https://github.com/ACINQ/eclair/commit/32b93b1488be653c7c66a438b171759e77b058ad) Add more checks around funding amount and channel reserve (#3352)
- [2422958](https://github.com/ACINQ/eclair/commit/24229589eb532df863a7445c876ededd76b0209e) Better documentation for remote `bitcoind` (#3359)
- [aa321d6](https://github.com/ACINQ/eclair/commit/aa321d69d082d474be1f3b9d3362560cb8e9838e) Explicitly match on-the-fly HTLCs after a restart (#3357)
- [06e0ff7](https://github.com/ACINQ/eclair/commit/06e0ff717b84fbcc11efc211c0fed4420fa7d289) Fix a batch of low-severity issues (#3355)
- [1819a5e](https://github.com/ACINQ/eclair/commit/1819a5e80d03b3fecfada8adc58bb62dd7c1daba) Add support for fulfillment payload  (#3321)
- [b358690](https://github.com/ACINQ/eclair/commit/b3586905c6b897dc9b2a06cd12bd0bd06154d390) Add support for `option_onion_messages_only_channels` (#3342)
- [5b765e0](https://github.com/ACINQ/eclair/commit/5b765e026b64631043fa9bbdde2151b6e9cc854b) Force-close channels that our peer claims are late without proving it (#3360)
- [141269f](https://github.com/ACINQ/eclair/commit/141269f516ab6ab07e1ffa2e5fdf1a7a41d71739) (Minor) Update claude gitignore files (#3363)
- [23316e4](https://github.com/ACINQ/eclair/commit/23316e4ecdc606b66bd99980b4c4373dbb047cef) (Minor) Fix flaky test in `WaitForAcceptChannelStateSpec` (#3364)
- [14d4093](https://github.com/ACINQ/eclair/commit/14d40937ba43e4c712392af8d932d0bf36166ddb) Emit `ChannelPersisted` event at channel creation (#3361)
- [b7ebefd](https://github.com/ACINQ/eclair/commit/b7ebefdbb7131774df27b6cdd69471a4f9afec3f) Force-close on invalid HTLC `cltv_expiry` (#3367)
- [1fc3dd7](https://github.com/ACINQ/eclair/commit/1fc3dd7ca140cff45debaa9ad9d3e40eeba4b512) Reject messages that include the wrong type of signatures (#3368)
- [3d092da](https://github.com/ACINQ/eclair/commit/3d092da8c96a9999368a3ed7412486a6ef7dfbf4) Fix a batch of Tor-related issues (#3354)
- [a2fe6c7](https://github.com/ACINQ/eclair/commit/a2fe6c7472c1f55e465f18a6cd05ae202131bbd8) Add optional rate-limit on incoming pre-auth connections (#3356)
