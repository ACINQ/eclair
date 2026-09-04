# Eclair vnext

<insert here a high-level description of the release>

## Major changes

<insert changes>

### Configuration changes

<insert changes>

### API changes

The API now rejects requests that carry an `Origin` header, which means it cannot be used from a web browser anymore.

Our API relies on HTTP basic authentication, which web browsers attach to cross-site requests once it has been cached:
any web page the node operator visits could then forge authenticated API calls, and since our endpoints accept
form-encoded parameters, a plain HTML form is enough (the attacker cannot read the response, but the API call has
already been made). Browsers set the `Origin` header on those requests, while `curl` and `eclair-cli` never do.

Command-line usage is unaffected. If you were serving a web front-end for the API, you now need to put a back-end of
your own in front of it.

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

<fill this section when publishing the release with `git log --reverse --abbrev=7 --pretty=format:'- [%h](https://github.com/ACINQ/eclair/commit/%H) %s' v0.14.3... --format=oneline --reverse`>
