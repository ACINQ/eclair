# API

Eclair can setup a JSON API on the `eclair.api.port` port set in the [configuration](./Configure.md). You first need to enable it:

```
eclair.api.enabled=true
eclair.api.password=changeit
```

:rotating_light: **Attention:** Eclair's API should NOT be accessible from the outside world.

The API cannot be used from a web browser: requests that set the `Origin` header (which browsers always do) are
rejected. This protects against cross-site request forgery, since browsers attach cached basic auth credentials to
cross-site requests. Command-line tools and other back-ends never set that header and are unaffected.

## Payment notification

Eclair accepts websocket connection on `ws://localhost:<port>/ws`, and emits a message containing the payment hash of a payment when receiving a payment.

## API calls

This API exposes the methods to read node state, open and close channels, and send and receive payments. Use this repository's documentation and issue tracker for XBT-specific behavior.

#### Example: open a channel

Your node listens on 8081. To open a channel, use a peer URI and node ID from the same XBT Lightning network, with a 30 mXBT `push`.

1/ connect to the node with the URI:

```shell
curl -X POST \
  -u :api_password \
  -F uri="NODE_ID@XBT_PEER_HOST:9735"  \
  http://127.0.0.1:8081/connect
```

2/ Open a channel with this node

```shell
curl -X POST \
  -u :api_password \
  -F nodeId=NODE_ID \
  -F fundingSatoshis=14000000 \
  http://127.0.0.1:8081/open
```

Feeling tired of writing all these `curls`? Good news, a CLI bash file is available. It uses this API to interact with your node. More information about `eclair-cli` is [here](./Usage.md).
