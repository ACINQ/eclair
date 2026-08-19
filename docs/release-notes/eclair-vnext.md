# Eclair vnext

<insert here a high-level description of the release>

We explicitly document that bitcoind should run on the same machine as eclair, or that a secure tunnel (providing encryption and authentication) must be setup between eclair and bitcoind.

## Major changes

<insert changes>

### Configuration changes

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

### API changes

<insert changes>

### Miscellaneous improvements and bug fixes

- We now ignore gossip queries that are for another chain instead of answering them
- Answering channel range queries is much cheaper: we cache the timestamps and checksums of our channel updates instead of recomputing them for the whole routing table on every incoming query
- We ignore duplicate `short_channel_id`s in a `query_short_channel_ids`, and reject queries whose query flags don't cover every `short_channel_id`, or that are sent before we've replied to the previous one
- We ignore `reply_short_channel_ids_end` messages that don't answer one of our queries: a peer could previously send us one to make us drop our synchronization state and ignore the rest of its replies

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

<fill this section when publishing the release with `git log v0.14.1... --format=oneline --reverse`>
