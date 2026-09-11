# Eclair vnext

<insert here a high-level description of the release>

## Major changes

<insert changes>

### Configuration changes

#### Add `max-funding-feerate` configuration parameter

The feerate used for funding and splice transactions comes from our fee estimator. We added a new configuration value
to `eclair.conf` to cap that feerate, so that inaccurate fee estimates cannot make you pay arbitrarily high mining fees
on every channel open or splice, similarly to what `max-closing-feerate` does for closing transactions:

```conf
// Maximum feerate that will be used for funding and splice transactions, in satoshis per byte.
eclair.on-chain-fees.max-funding-feerate = 50
```

If your channel opens and splices don't confirm because the mempool is more congested than that, you can RBF them
with the `rbfopen` and `rbfsplice` API commands, or increase this value and restart your node.

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

<fill this section when publishing the release with `git log v0.14.2... --format=oneline --reverse`>
