# Eclair vnext

<insert here a high-level description of the release>

## Major changes

### Remove support for non-anchor channels

We remove the code used to support legacy channels that don't use anchor outputs or taproot.
If you still have such channels, eclair won't start: you will need to close those channels, and will only be able to update eclair once they have been successfully closed.

### Configuration changes

<insert changes>

### API changes

- `findroute`, `findroutetonode` and `findroutebetweennodes` now include a `maxCltvExpiryDelta` parameter (#3234)

### Miscellaneous improvements and bug fixes

<insert changes>

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

<fill this section when publishing the release with `git log v0.13.1... --format=oneline --reverse`>
