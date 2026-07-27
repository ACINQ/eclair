# Eclair vnext

<insert here a high-level description of the release>

## Major changes

### Update minimal version of Bitcoin Core

With this release, eclair requires using Bitcoin Core 31.x.
Newer versions of Bitcoin Core may be used, but have not been extensively tested.

### Disable blinded path fee discount for Bolt12

We've disabled blinded path fee discount introduced in #2993 for Bolt12 payments.
It doesn't work well with MPP and need to be re-designed.
If you're using a custom offer-handler plugin, make sure you don't set `feeOverride_opt`
in the `InvoiceRequestActor.Route` you create, otherwise your node will be at risk.

See #3332 for more details.

### Configuration changes

<insert changes>

### API changes

<insert changes>

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

<fill this section when publishing the release with `git log v0.14.0... --format=oneline --reverse`>
