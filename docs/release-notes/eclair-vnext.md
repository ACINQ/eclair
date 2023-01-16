# Eclair vnext

<insert here a high-level description of the release>

## Major changes

<insert changes>

### API changes

- `audit` now accepts `--count` and `--skip` parameters to limit the number of retrieved items (#2474, #2487)
- `sendtoroute` removes the `--trampolineNodes` argument and implicitly uses a single trampoline hop (#2480)
- `payinvoice` always returns the payment result when used with `--blocking`, even when using MPP (#2525)
- `node` returns high-level information about a remote node (#2568)

### Miscellaneous improvements and bug fixes

#### Strategies to handle locked utxos at start-up (#2278)

If some utxos are locked when eclair starts, it is likely because eclair was previously stopped in the middle of funding a transaction.
While this doesn't create any risk of loss of funds, these utxos will stay locked for no good reason and won't be used to fund future transactions.
Eclair offers three strategies to handle that scenario, that node operators can configure by setting `eclair.bitcoind.startup-locked-utxos-behavior` in their `eclair.conf`:

- `stop`: eclair won't start until the corresponding utxos are unlocked by the node operator
- `unlock`: eclair will automatically unlock the corresponding utxos
- `ignore`: eclair will leave these utxos locked and start

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
