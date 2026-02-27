# Eclair vnext

<insert here a high-level description of the release>

## Major changes

### Remove support for non-anchor channels

We remove the code used to support legacy channels that don't use anchor outputs or taproot.
If you still have such channels, eclair won't start: you will need to close those channels, and will only be able to update eclair once they have been successfully closed.

### Channel lifecyle events rework

Eclair emits several events during a channel lifecycle, which can be received by plugins or through the websocket.
We reworked these events to be compatible with splicing and consistent with 0-conf:

- we removed the `channel-opened` event
- we introduced a `channel-funding-created` event
- we introduced a `channel-confirmed` event
- we introduced a `channel-ready` event

The `channel-funding-created` event is emitted when the funding transaction or a splice transaction has been signed and can be published.
Listeners can use the `fundingTxIndex` to detect whether this is the initial channel funding (`fundingTxIndex = 0`) or a splice (`fundingTxIndex > 0`).

The `channel-confirmed` event is emitted when the funding transaction or a splice transaction has enough confirmations.
Listeners can use the `fundingTxIndex` to detect whether this is the initial channel funding (`fundingTxIndex = 0`) or a splice (`fundingTxIndex > 0`).

The `channel-ready` event is emitted when the channel is ready to process payments, which generally happens after the `channel-confirmed` event.
However, when using zero-conf, this event may be emitted before the `channel-confirmed` event.

See #3237 for more details.

### Plugin validation of interactive transactions

We add a new `ValidateInteractiveTxPlugin` trait that can be extended by plugins that want to perform custom validation of remote inputs and outputs added to interactive transactions.
This can be used for example to reject transactions that send to specific addresses or use specific UTXOs.

Here is the trait definition:

```scala
/**
 * Plugins implementing this trait will be called to validate the remote inputs and outputs used in interactive-tx.
 * This can be used for example to reject interactive transactions that send to specific addresses before signing them.
 */
trait ValidateInteractiveTxPlugin extends PluginParams {
  /**
   * This function will be called for every interactive-tx, before signing it. The plugin should return:
   *  - [[Future.successful(())]] to accept the transaction
   *  - [[Future.failed(...)]] to reject it: the error message will be sent to the remote node, so make sure you don't
   *    include information that should stay private.
   *
   * Note that eclair will run standard validation on its own: you don't need for example to verify that inputs exist
   * and aren't already spent. This function should only be used for custom, non-standard validation that node operators
   * want to apply.
   */
  def validateSharedTx(remoteInputs: Map[OutPoint, TxOut], remoteOutputs: Seq[TxOut]): Future[Unit]
}
```

See #3258 for more details.

### Channel jamming accountability

We update our channel jamming mitigation to match the latest draft of the [spec](https://github.com/lightning/bolts/pull/1280).
Note that we use a different architecture for channel bucketing and confidence scoring than what is described in the BOLTs.
We don't yet fail HTLCs that don't meet these restrictions: we're only collecting data so far to evaluate how the algorithm performs.

If you want to disable this feature entirely, you can set the following values in `eclair.conf`:

```conf
eclair.relay.peer-reputation.enabled = false
eclair.relay.reserved-for-accountable = 0.0
```

### Configuration changes

<insert changes>

### API changes

- `findroute`, `findroutetonode` and `findroutebetweennodes` now include a `maxCltvExpiryDelta` parameter (#3234)
- `channel-opened` was removed from the websocket in favor of `channel-funding-created`, `channel-confirmed` and `channel-ready` (#3237 and #3256)

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
