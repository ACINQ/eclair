# Eclair vnext

<insert here a high-level description of the release>

## Major changes

### Alternate strategy to avoid mass force-close of channels in certain cases

The default strategy, when an unhandled exception or internal error happens, is to locally force-close the channel. Not only is there a delay before the channel balance gets refunded, but if the exception was due to some misconfiguration or bug in eclair that affects all channels, we risk force-closing all channels.

This is why an alternative behavior is to simply log an error and stop the node. Note that if you don't closely monitor your node, there is a risk that your peers take advantage of the downtime to try and cheat by publishing a revoked commitment. Additionally, while there is no known way of triggering an internal error in eclair from the outside, there may very well be a bug that allows just that, which could be used as a way to remotely stop the node (with the default behavior, it would "only" cause a local force-close of the channel).

### Separate log for important notifications

Eclair added a new log file (`notifications.log`) for important notifications that require an action from the node operator.
Node operators should watch this file very regularly.

An event is also sent to the event stream for every such notification.
This lets plugins notify the node operator via external systems (push notifications, email, etc).

### Initial support for onion messages

Eclair now supports the feature `option_onion_messages`. If this feature is enabled, eclair will relay onion messages.
It can also send onion messages with the `sendonionmessage` API.
Messages sent to Eclair can be read with the websocket API.

### Support for `option_payment_metadata`

Eclair now supports the `option_payment_metadata` feature (see https://github.com/lightning/bolts/pull/912).
This feature will let recipients generate "light" invoices that don't need to be stored locally until they're paid.
This is particularly useful for payment hubs that generate a lot of invoices (e.g. to be displayed on a website) but expect only a fraction of them to actually be paid.

Eclair includes a small `payment_metadata` field in all invoices it generates.
This lets node operators verify that payers actually support that feature.

### API changes

#### Timestamps

All timestamps are now returned as an object with two attributes:

- `iso`: ISO-8601 format with GMT time zone. Precision may be second or millisecond depending on the timestamp.
- `unix`: seconds since epoch formats (seconds since epoch). Precision is always second.

Examples:

- second-precision timestamp:
  - before:
  ```json
  {
    "timestamp": 1633357961
  }
  ```
  - after
  ```json
  {
    "timestamp": {
      "iso": "2021-10-04T14:32:41Z",
      "unix": 1633357961
    }
  }
  ```
- milli-second precision timestamp:
  - before:
  ```json
  {
    "timestamp": 1633357961456
  }
  ```
  - after (note how the unix format is in second precision):
  ```json
  {
    "timestamp": {
      "iso": "2021-10-04T14:32:41.456Z",
      "unix": 1633357961
    }
  }
  ```
  
#### Sending onion messages

You can now send onion messages with `sendonionmessage`.
It expects `--recipientNode`, the node id of the recipient if it is known or `--recipientBlindedRoute` a hexadecimal encoded blinded route to send the message to, and `--content` the content of the message as an encoded TLV stream in hexadecimal.
It also accepts `--intermediateNodes` a list of intermediate node ids to hide the origin of the message and `--replyPath` a possibly empty list of intermediate node ids for the reply path if we expect a response to the message.

#### Balance

The detailed balance json format has been slightly updated for channels in state `normal` and `shutdown`, and `closing`.

Amounts corresponding to incoming htlcs for which we knew the preimage were previously included in `toLocal`, they are
now grouped with outgoing htlcs amounts and the field has been renamed from `htlcOut` to `htlcs`.

#### Miscellaneous

This release contains many other API updates:

- `deleteinvoice` allows you to remove unpaid invoices (#1984)
- `findroute`, `findroutetonode` and `findroutebetweennodes` supports new output format `full` (#1969)
- `findroute`, `findroutetonode` and `findroutebetweennodes` now accept `--ignoreNodeIds` to specify nodes you want to be ignored in path-finding (#1969)
- `findroute`, `findroutetonode` and `findroutebetweennodes` now accept `--ignoreShortChannelIds` to specify channels you want to be ignored in path-finding (#1969)
- `findroute`, `findroutetonode` and `findroutebetweennodes` now accept `--maxFeeMsat` to specify an upper bound of fees (#1969)
- `getsentinfo` output includes `failedNode` field for all failed routes
- for `payinvoice` and `sendtonode`, `--feeThresholdSat` has been renamed to `--maxFeeFlatSat`
- the `networkstats` API has been removed

Have a look at our [API documentation](https://acinq.github.io/eclair) for more details.

### Miscellaneous improvements and bug fixes

- Eclair now supports cookie authentication for Bitcoin Core RPC (#1986)

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

<fill this section when publishing the release with `git log v0.6.2... --format=oneline --reverse`>
