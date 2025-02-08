# Eclair vnext

<insert here a high-level description of the release>

## Major changes

### Channel Splicing

With this release, we add support for the final version of [splicing](https://github.com/lightning/bolts/pull/1160) that was recently added to the BOLTs.
Splicing allows node operators to change the size of their existing channels, which makes it easier and more efficient to allocate liquidity where it is most needed.
Most node operators can now have a single channel with each of their peer, which costs less on-chain fees and resources, and makes path-finding easier.

The size of an existing channel can be increased with the `splicein` API:

```sh
eclair-cli splicein --channelId=<channel_id> --amountIn=<amount_satoshis>
```

Once that transaction confirms, the additional liquidity can be used to send outgoing payments.
If the transaction doesn't confirm, the node operator can speed up confirmation with the `rbfsplice` API:

```sh
eclair-cli rbfsplice --channelId=<channel_id> --targetFeerateSatByte=<feerate_satoshis_per_byte> --fundingFeeBudgetSatoshis=<maximum_on_chain_fee_satoshis>
```

If the node operator wants to reduce the size of a channel, or send some of the channel funds to an on-chain address, they can use the `spliceout` API:

```sh
eclair-cli spliceout --channelId=<channel_id> --amountOut=<amount_satoshis> --scriptPubKey=<on_chain_address>
```

That operation can also be RBF-ed with the `rbfsplice` API to speed up confirmation if necessary.

Note that when 0-conf is used for the channel, it is not possible to RBF splice transactions.
Node operators should instead create a new splice transaction (with `splicein` or `spliceout`) to CPFP the previous transaction.

Note that eclair had already introduced support for a splicing prototype in v0.9.0, which helped improve the BOLT proposal.
We're removing support for the previous splicing prototype feature: users that depended on this protocol must upgrade to create official splice transactions.

### Peer storage

With this release, eclair supports the `option_provide_storage` feature introduced in <https://github.com/lightning/bolts/pull/1110>.
When `option_provide_storage` is enabled, eclair will store a small encrypted backup for peers that request it.
This backup is limited to 65kB and node operators should customize the `eclair.peer-storage` configuration section to match their desired SLAs.
This is mostly intended for LSPs that serve mobile wallets to allow users to restore their channels when they switch phones.

### Eclair requires a  Java 21 runtime

Eclair now targets Java 21 and requires a compatible Java Runtime Environment. It will no longer work on JRE 11 or JRE 17.
There are many organisations that package Java runtimes and development kits, for example [OpenJDK 21](https://adoptium.net/temurin/releases/?package=jdk&version=21).

### API changes

<insert changes>

### Miscellaneous improvements and bug fixes

#### Gossip sync limits

On reconnection, eclair now only synchronizes its routing table with a small number of top peers instead of synchronizing with every peer.
If you already use `sync-whitelist`, the default behavior has been modified and you must set `router.sync.peer-limit = 0` to keep preventing any synchronization with other nodes.
You must also use `router.sync.whitelist` instead of `sync-whitelist`.

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

- Ubuntu 24.04.1
- Adoptium OpenJDK 21.0.4

Use the following command to generate the eclair-node package:

```sh
./mvnw clean install -DskipTests
```

That should generate `eclair-node/target/eclair-node-<version>-XXXXXXX-bin.zip` with sha256 checksums that match the one we provide and sign in `SHA256SUMS.asc`

(*) You may be able to build the exact same artefacts with other operating systems or versions of JDK 11, we have not tried everything.

## Upgrading

This release is fully compatible with previous eclair versions. You don't need to close your channels, just stop eclair, upgrade and restart.

## Changelog

<fill this section when publishing the release with `git log v0.11.0... --format=oneline --reverse`>
