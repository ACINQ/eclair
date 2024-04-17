# Eclair vnext

<insert here a high-level description of the release>

## Major changes

### Liquidity Ads

This release includes an early prototype for [liquidity ads](https://github.com/lightning/bolts/pull/878).
Liquidity ads allow nodes to rent their liquidity in a trustless and decentralized manner.
Every node advertizes the rates at which they lease their liquidity, and buyers connect to sellers that offer interesting rates.

The liquidity ads specification is still under review and will likely change.
This feature isn't meant to be used on mainnet yet and is thus disabled by default.

### API changes

- `nodes` allows filtering nodes that offer liquidity ads (#2550)
- `open` allows requesting inbound liquidity from the remote node using liquidity ads (#2550)

### Miscellaneous improvements and bug fixes

<insert changes>

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

<fill this section when publishing the release with `git log v0.10.0... --format=oneline --reverse`>
