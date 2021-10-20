# Eclair vnext

<insert here a high-level description of the release>

## Major changes

<insert changes>

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

This release contains many API updates:

- `findroute`, `findroutetonode` and `findroutebetweennodes` supports new output format `full` (#1969)
- `findroute`, `findroutetonode` and `findroutebetweennodes` now accept `--ignoreNodeIds` to specify nodes you want to be ignored in path-finding (#1969)
- `findroute`, `findroutetonode` and `findroutebetweennodes` now accept `--ignoreShortChannelIds` to specify channels you want to be ignored in path-finding (#1969)
- `findroute`, `findroutetonode` and `findroutebetweennodes` now accept `--maxFeeMsat` to specify an upper bound of fees (#1969)

Have a look at our [API documentation](https://acinq.github.io/eclair) for more details.

### Miscellaneous improvements and bug fixes

<insert changes>

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
