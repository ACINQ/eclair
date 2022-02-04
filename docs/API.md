# API

Eclair can setup a JSON API on the `eclair.api.port` port set in the [configuration](./Configure.md). You first need to enable it:

```
eclair.api.enabled=true
eclair.api.password=changeit
```

:rotating_light: **Attention:** Eclair's API should NOT be accessible from the outside world (similarly to Bitcoin Core API).

## Payment notification

Eclair accepts websocket connection on `ws://localhost:<port>/ws`, and emits a message containing the payment hash of a payment when receiving a payment.

## API calls

This API exposes all the necessary methods to read the current state of the node, open/close channels and send/receive payments. For the full documentation please visit https://acinq.github.io/eclair

#### Example: open a channel

Your node listens on 8081. You want to open a 140 mBTC channel with `endurance.acinq.co` on Testnet with a 30 mBTC `push`

1/ connect to the node with the URI:

```shell
curl -X POST \
  -u :api_password \
  -F uri="03933884aaf1d6b108397e5efe5c86bcf2d8ca8d2f700eda99db9214fc2712b134@endurance.acinq.co:9735"  \
  http://127.0.0.1:8081/connect
```

2/ Open a channel with this node

```shell
curl -X POST \
  -u :api_password \
  -F nodeId=03933884aaf1d6b108397e5efe5c86bcf2d8ca8d2f700eda99db9214fc2712b134 \
  -F fundingSatoshis=14000000 \
  http://127.0.0.1:8081/open
```

Feeling tired of writing all these `curls`? Good news, a CLI bash file is available. It uses this API to interact with your node. More information about `eclair-cli` is [here](./Usage.md).