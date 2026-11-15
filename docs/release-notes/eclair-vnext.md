# Eclair vnext

<insert here a high-level description of the release>

## Major changes

<insert changes>

### Configuration changes

<insert changes>

### API changes

<insert changes>

### Miscellaneous improvements and bug fixes

<insert changes>

## Building

Eclair builds are deterministic. To reproduce our builds, please use the following environment (*):

- Ubuntu 26.04.1
- Adoptium OpenJDK 21.0.12.1

Then use the following command to generate the eclair-node packages:

```sh
./mvnw clean install -DskipTests
```

That should generate `eclair-node/target/eclair-node-<version>-XXXXXXX-bin.zip` with sha256 checksums that match the one we provide and sign in `SHA256SUMS.asc`

(*) You may be able to build the exact same artefacts with other operating systems or versions of JDK 21, we have not tried everything.

## Upgrading

This release is fully compatible with previous eclair versions. You don't need to close your channels, just stop eclair, upgrade and restart.

## Changelog

<fill this section when publishing the release with `git log --reverse --abbrev=7 --pretty=format:'- [%h](https://github.com/ACINQ/eclair/commit/%H) %s' v0.14.3... --format=oneline --reverse`>
