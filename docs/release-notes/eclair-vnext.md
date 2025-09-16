# Eclair vnext

<insert here a high-level description of the release>

## Major changes

<insert changes>

### Remove support for legacy channel codecs

We remove the code used to deserialize channel data from versions of eclair prior to v0.13.
Node operators running a version of `eclair` older than v0.13 must first upgrade to v0.13 to migrate their channel data, and then upgrade to the latest version.

### Move closed channels to dedicated database table

We previously kept closed channels in the same database table as active channels, with a flag indicating that it was closed.
This creates performance issues for nodes with a large history of channels, and creates backwards-compatibility issues when changing the channel data format.

We now store closed channels in a dedicated table, where we only keep relevant information regarding the channel.
When restarting your node, the channels table will automatically be cleaned up and closed channels will move to the new table.
This may take some time depending on your channels history, but will only happen once.

### Update minimal version of Bitcoin Core

With this release, eclair requires using Bitcoin Core 29.1.
Newer versions of Bitcoin Core may be used, but have not been extensively tested.

### Configuration changes

<insert changes>

### API changes

- the `closedchannels` API now returns human-readable channel data

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

<fill this section when publishing the release with `git log v0.13.0... --format=oneline --reverse`>
