# Eclair v0.14.6

This is a patch release that contains security hardening fixes.
We highly recommend upgrading, as some of these issues can be exploited by malicious nodes.

Many thanks to @erickcestari for his responsible disclosures.

### Configuration changes

#### Add `require-confirmed-inputs-for-splicing` configuration parameter (enabled by default)

By default splices only spend confirmed transactions, and we limit the number of inputs and outputs our peer can add. 
Note that `require-confirmed-inputs-for-dual-funding` doesn't apply to splices anymore.

```conf
    // If true, splices only spend confirmed transactions: our peer's inputs must be confirmed, and we don't splice on top
    // of an unconfirmed splice transaction. Setting this to false is unsafe: our peer may then double-spend an unconfirmed
    // parent after signing, which makes the splice transaction invalid and can put our HTLCs at risk if we force-close
    // using it.
    require-confirmed-inputs-for-splicing = true
```

If your channel opens and splices don't confirm because the mempool is more congested than that, you can RBF them
with the `rbfopen` and `rbfsplice` API commands, or increase this value and restart your node.

### Miscellaneous improvements and bug fixes

A warning has been added against using `zero_fee_commitments` when splicing with untrusted peers (this feature was already disabled by default).
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
