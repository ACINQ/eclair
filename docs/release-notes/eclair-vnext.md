# Eclair vnext

<insert here a high-level description of the release>

## Major changes

<insert changes>

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
