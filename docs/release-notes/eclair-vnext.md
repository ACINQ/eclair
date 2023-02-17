# Eclair vnext

<insert here a high-level description of the release>

## Major changes

### Offers

Eclair now supports paying offers:
```shell
$ ./eclair-cli payoffer --offer=<offer-to-pay> --amountMsat=<amountToPay>
```
If the offer supports it, you can also specify `--quantity` to buy more than one at a time.
All the parameters from `payinvoice` are also supported.

Eclair will request an invoice and pay it (assuming it matches our request) without further interaction.

Offers are still experimental and some details could still change before they are widely supported.

### API changes

- `audit` now accepts `--count` and `--skip` parameters to limit the number of retrieved items (#2474, #2487)
- `sendtoroute` removes the `--trampolineNodes` argument and implicitly uses a single trampoline hop (#2480)
- `payinvoice` always returns the payment result when used with `--blocking`, even when using MPP (#2525)
- `node` returns high-level information about a remote node (#2568)
- `channel-created` is a new websocket event that is published when a channel's funding transaction has been broadcast (#2567)
- `channel-opened` websocket event was updated to contain the final `channel_id` and be published when a channel is ready to process payments (#2567)
- `getsentinfo` can now be used with `--offer` to list payments sent to a specific offer.

### Miscellaneous improvements and bug fixes

#### Strategies to handle locked utxos at start-up (#2278)

If some utxos are locked when eclair starts, it is likely because eclair was previously stopped in the middle of funding a transaction.
While this doesn't create any risk of loss of funds, these utxos will stay locked for no good reason and won't be used to fund future transactions.
Eclair offers three strategies to handle that scenario, that node operators can configure by setting `eclair.bitcoind.startup-locked-utxos-behavior` in their `eclair.conf`:

- `stop`: eclair won't start until the corresponding utxos are unlocked by the node operator
- `unlock`: eclair will automatically unlock the corresponding utxos
- `ignore`: eclair will leave these utxos locked and start

#### Add plugin support for channel open interception (#2552)

Eclair now supports plugins that intercept channel open requests and decide whether to accept or reject them. This is useful for example to enforce custom policies on who can open channels with you.

An example plugin that demonstrates this functionality can be found in the [eclair-plugins](https://github.com/ACINQ/eclair-plugins) repository.

#### Configurable channel open rate limits (#2552)

We have added parameters to `eclair.conf` to allow nodes to manage the number of channel open requests from peers that are pending on-chain confirmation. A limit exists for each public peer node individually and for all private peer nodes in aggregate.

The new configuration options and defaults are as follows:
```conf
// a list of public keys; we will ignore limits on pending channels from these peers
eclair.channel.channel-open-limits.channel-opener-whitelist = [] 

// maximum number of pending channels we will accept from a given peer
eclair.channel.channel-open-limits.max-pending-channels-per-peer = 3 

// maximum number of pending channels we will accept from all private nodes
eclair.channel.channel-open-limits.max-total-pending-channels-private-nodes = 99 
```

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
