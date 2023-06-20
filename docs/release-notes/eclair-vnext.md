# Eclair vnext

<insert here a high-level description of the release>

## Major changes

### Use priority instead of block target for feerates

Eclair now uses a `slow`/`medium`/`fast` notation for feerates (in the style of mempool.space),
instead of block targets. Only the funding and closing priorities can be configured, the feerate
for commitment transactions is managed by eclair, so is the fee bumping for htlcs in force close
scenarii. Note that even in a force close scenario, when an output is only spendable by eclair, then
the normal closing priority is used.

Default setting is `medium` for both funding and closing. Node operators may configure their values like so:
```eclair.conf
eclair.on-chain-fees.confirmation-priority {
    funding = fast
    closing = slow
}
```

This configuration section replaces the previous `eclair.on-chain-fees.target-blocks` section.

### API changes

<insert changes>

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

<fill this section when publishing the release with `git log v0.9.0... --format=oneline --reverse`>
