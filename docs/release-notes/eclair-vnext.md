# Eclair vnext

<insert here a high-level description of the release>

## Major changes

<insert changes>

### API changes

- `audit` now accepts `--count` and `--skip` parameters to limit the number of retrieved items (#2474, #2487)
- `sendtoroute` removes the `--trampolineNodes` argument and implicitly uses a single trampoline hop (#2480)
- `payinvoice` always returns the payment result when used with `--blocking`, even when using MPP (#2525)
- `node` returns high-level information about a remote node (#2568)
- `channel-created` is a new websocket event that is published when a channel's funding transaction has been broadcast (#2567)
- `channel-opened` websocket event was updated to contain the final `channel_id` and be published when a channel is ready to process payments (#2567)

### Miscellaneous improvements and bug fixes

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

- Ubuntu 20.04
- AdoptOpenJDK 11.0.6
- Maven 3.8.1

Use the following command to generate the eclair-node package:

```sh
mvn clean install -DskipTests
```

That should generate `eclair-node/target/eclair-node-<version>-XXXXXXX-bin.zip` with sha256 checksums that match the one we provide and sign in `SHA256SUMS.asc`

(*) You may be able to build the exact same artefacts with other operating systems or versions of JDK 11, we have not tried everything.

## Upgrading

This release is fully compatible with previous eclair versions. You don't need to close your channels, just stop eclair, upgrade and restart.

## Changelog

<fill this section when publishing the release with `git log v0.8.0... --format=oneline --reverse`>
