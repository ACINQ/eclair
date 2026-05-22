---
title: Eclair API

language_tabs: # must be one of https://git.io/vQNgJ
  - shell

toc_footers:
  - <a href='https://github.com/lord/slate'>Documentation Powered by Slate</a>

includes:
  - errors

search: true
---

# Introduction

Welcome to the Eclair API, this website contains documentation and code examples about how to interact with the Eclair lightning node via its API.
Feel free to suggest improvements and fixes to this documentation by submitting a pull request to the [repo](https://github.com/ACINQ/eclair).
The API uses [HTTP form data](https://en.wikipedia.org/wiki/POST_(HTTP)#Use_for_submitting_web_forms) and returns JSON-encoded objects or simple strings if no objects are being returned. All errors are handled with a JSON response (more info [here](#errors)).
All monetary values are in millisatoshi unless stated otherwise.

# Authentication

Eclair uses HTTP Basic authentication and expects to receive the correct header with every request.
To set an API password, use the [configuration](https://github.com/ACINQ/eclair/blob/master/eclair-core/src/main/resources/reference.conf).
The rest of this document will use '21satoshi' as password which encoded as _base64_ results in `OjIxc2F0b3NoaQ==`.

<aside class="notice">
 Please note that eclair only expects a password and an empty user name.
</aside>

`Authorization: Base64Encoded("":<eclair_api_password>)`

# GetInfo

## GetInfo

```shell
curl -s -u :<eclair_api_password> -X POST "http://localhost:8080/getinfo"

# with eclair-cli
eclair-cli getinfo
```

> The above command returns JSON structured like this:

```json
{
  "version": "0.14.0-ec8c63b",
  "nodeId": "028e2403fbfddb3d787843361f91adbda64c6f622921b19fb48f5766508bcadb29",
  "alias": "ACINQ",
  "color": "#49daaa",
  "features": {
    "activated": {
      "option_simple_close": "optional",
      "option_route_blinding": "optional",
      "option_attribution_data": "optional",
      "option_dual_fund": "optional",
      "gossip_queries_ex": "optional",
      "option_data_loss_protect": "mandatory",
      "option_static_remotekey": "mandatory",
      "option_scid_alias": "optional",
      "option_onion_messages": "optional",
      "option_support_large_channel": "optional",
      "option_anchors_zero_fee_htlc_tx": "optional",
      "payment_secret": "mandatory",
      "option_shutdown_anysegwit": "optional",
      "option_channel_type": "mandatory",
      "basic_mpp": "optional",
      "gossip_queries": "optional",
      "option_quiesce": "optional",
      "option_payment_metadata": "optional",
      "option_simple_taproot": "optional",
      "var_onion_optin": "mandatory",
      "option_splice": "optional"
    }
  },
  "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
  "network": "regtest",
  "blockHeight": 68394,
  "publicAddresses": [
    "34.239.230.56:9735",
    "of7husrflx7sforh3fw6yqlpwstee3wg5imvvmkp4bz6rbjxtg5nljad.onion:9735"
  ],
  "instanceId": "dd41b470-aae2-4c98-b609-4367b6d58ca8"
}
```

Returns information about this instance such as version, features, **nodeId** and current block height as seen by eclair.

### HTTP Request

`POST http://localhost:8080/getinfo`

# Connect

## Connect via URI

```shell
curl -s -u :<eclair_api_password> -X POST -F uri=<target_uri>  "http://localhost:8080/connect"

# with eclair-cli
eclair-cli connect --uri=<target_uri>
```

> The above command returns:

```shell
connected
```

Connect to another lightning node. This will perform a connection but no channels will be opened.
Note that in the _URI_, the port is optional, and if missing, the default port (9735) will be used.

### HTTP Request

`POST http://localhost:8080/connect`

### Parameters

Parameter | Description                          | Optional | Type  
--------- | ------------------------------------ | -------- | ------
uri       | The URI in format 'nodeId@host:port' | No       | String

## Connect manually

```shell
curl -s -u :<eclair_api_password> -X POST -F nodeId=<node_id> -F host=<host> "http://localhost:8080/connect"

# with eclair-cli
eclair-cli connect --nodeId=<node_id> --host=<host>
```

> The above command returns:

```shell
connected
```

Connect to another lightning node. This will perform a connection but no channels will be opened.

### HTTP Request

`POST http://localhost:8080/connect`

### Parameters

Parameter | Description                                       | Optional | Type
--------- | ------------------------------------------------- | -------- | ---------------------------
nodeId    | The **nodeId** of the node you want to connect to | No       | 33-bytes-HexString (String)
host      | The IPv4 host address of the node                 | No       | String
port      | The port of the node (default: 9735)              | Yes      | Integer

## Connect via NodeId

```shell
curl -s -u :<eclair_api_password> -X POST -F nodeId=<nodeId>  "http://localhost:8080/connect"

# with eclair-cli
eclair-cli connect --nodeId=<nodeId>
```

> The above command returns:

```shell
connected
```

Connect to another lightning node. This will perform a connection but no channels will be opened.
This API does not require a target address. Instead, eclair will use one of the addresses published
by the remote peer in its `node_announcement` messages.

### HTTP Request

`POST http://localhost:8080/connect`

### Parameters

Parameter | Description                                       | Optional | Type
--------- | ------------------------------------------------- | -------- | ---------------------------
nodeId    | The **nodeId** of the node you want to connect to | No       | 33-bytes-HexString (String)

## Disconnect

```shell
curl -s -u :<eclair_api_password> -X POST -F nodeId=<nodeId>  "http://localhost:8080/disconnect"

# with eclair-cli
eclair-cli disconnect --nodeId=<nodeId>
```

> The above command returns:

```shell
peer <nodeId> disconnecting
```

Disconnect from a peer.

### HTTP Request

`POST http://localhost:8080/disconnect`

### Parameters

Parameter | Description                                            | Optional | Type
--------- | ------------------------------------------------------ | -------- | ---------------------------
nodeId    | The **nodeId** of the node you want to disconnect from | No       | 33-bytes-HexString (String)

# Open

## Open

```shell
curl -s -u :<eclair_api_password> -X POST -F nodeId=<node_id> -F fundingSatoshis=<funding_satoshis> fundingFeeBudgetSatoshis=<funding_fee_budget_satoshis> "http://localhost:8080/open"

# with eclair-cli
eclair-cli open --nodeId=<node_id> --fundingSatoshis=<funding_satoshis> --fundingFeeBudgetSatoshis=<funding_fee_budget_satoshis>
```

> The above command returns the channelId of the newly created channel:

```shell
created channel e872f515dc5d8a3d61ccbd2127f33141eaa115807271dcc5c5c727f3eca914d3 with fundingTxId=bc2b8db55b9588d3a18bd06bd0e284f63ee8cc149c63138d51ac8ef81a72fc6f and fees=720 sat
```

Open a channel to another lightning node. You must specify the target **nodeId** and the funding satoshis for the new channel. Optionally
you can send to the remote a _pushMsat_ value and you can specify whether this should be a public or private channel (default is set in the config).

If you already have another channel to the same node, the routing fees that will be used for this new channel will be the same as your existing channel.
Otherwise the values from `eclair.conf` will be used (see `eclair.relay.fees` in your `eclair.conf`).

If you want to override the routing fees that will be used, you must use the `updaterelayfee` API before opening the channel.

### HTTP Request

`POST http://localhost:8080/open`

### Parameters

Parameter                | Description                                                                | Optional | Type
------------------------ | -------------------------------------------------------------------------- | -------- | ---------------------------
nodeId                   | The **nodeId** of the node you want to open a channel with                 | No       | 33-bytes-HexString (String)
fundingSatoshis          | Amount of satoshis to spend in the funding of the channel                  | No       | Satoshis (Integer)
fundingFeeBudgetSatoshis | Maximum fees (in satoshis) of the funding transaction                      | No       | Satoshis (Integer)
channelType              | Channel type (standard, static_remotekey, anchor_outputs_zero_fee_htlc_tx) | Yes      | String
pushMsat                 | Amount of millisatoshi to unilaterally push to the counterparty            | Yes      | Millisatoshis (Integer)
fundingFeerateSatByte    | Feerate in sat/byte to apply to the funding transaction                    | Yes      | Satoshis (Integer)
announceChannel          | True for public channels, false otherwise                                  | Yes      | Boolean
openTimeoutSeconds       | Timeout for the operation to complete                                      | Yes      | Seconds (Integer)

## RbfOpen

```shell
curl -s -u :<eclair_api_password> -X POST -F channelId=<channel_id> -F targetFeerateSatByte=<target_feerate> fundingFeeBudgetSatoshis=<funding_fee_budget_satoshis> "http://localhost:8080/rbfopen"

# with eclair-cli
eclair-cli rbfopen --channelId=<channel_id> --targetFeerateSatByte=<target_feerate> --fundingFeeBudgetSatoshis=<funding_fee_budget_satoshis>
```

> The above command returns:

```shell
{
  "rbfIndex": 0,
  "fundingTxId": "ba31c8aa6196afbad450686d32738fdd98dcb3c2f82c5f15a8a49ef09b024a29",
  "fee": 3297
}
```

Increase the fees of an unconfirmed dual-funded channel to speed up confirmation.
You must specify the target **channelId** and the feerate that should be set for the funding transaction.
A negotiation will start with your channel peer, and if they agree, your node will publish an updated funding transaction paying more fees.

### HTTP Request

`POST http://localhost:8080/rbfopen`

### Parameters

Parameter                | Description                                             | Optional | Type
------------------------ | ------------------------------------------------------- | -------- | ---------------------------
channelId                | The **channelId** of the channel that should be RBF-ed  | No       | 32-bytes-HexString (String)
targetFeerateSatByte     | Feerate in sat/byte to apply to the funding transaction | No       | Satoshis (Integer)
fundingFeeBudgetSatoshis | Maximum fees (in satoshis) of the funding transaction   | No       | Satoshis (Integer)
lockTime                 | The nLockTime to apply to the funding transaction       | Yes      | Integer

## CpfpBumpFees

```shell
curl -s -u :<eclair_api_password> -X POST -F outpoints=<unconfirmed_utxos> -F targetFeerateSatByte=<target_feerate> "http://localhost:8080/cpfpbumpfees"

# with eclair-cli
eclair-cli cpfpbumpfees --outpoints=<unconfirmed_utxos> --targetFeerateSatByte=<target_feerate>
```

> The above command returns:

```shell
"83d4f64bd3f7708caad602de0c372a94fcdc50f128519c9505169013215f598f"
```

Increase the fees of a set of unconfirmed transactions by publishing a high-fee child transaction.
The **targetFeerateSatByte** will be applied to the whole package containing the unconfirmed transactions and the child transaction.
You must identify the set of **outpoints** that belong to your bitcoin wallet in the unconfirmed transactions (usually change outputs).
This command returns the txid of the child transaction that was published.

### HTTP Request

`POST http://localhost:8080/cpfpbumpfees`

### Parameters

Parameter            | Description                                                  | Optional | Type
-------------------- | ------------------------------------------------------------ | -------- | -------------------------------------
outpoints            | Utxos that should be spent by the child transaction          | No       | CSV list of outpoints (txid:vout)
targetFeerateSatByte | Feerate in sat/byte to apply to the unconfirmed transactions | No       | Satoshis (Integer)

# Splice

## Splice-in

```shell
curl -s -u :<eclair_api_password> -X POST -F channelId=<channel> -F amountIn=<amount_satoshis> "http://localhost:8080/splicein"

# with eclair-cli
eclair-cli splicein --channelId=<channel> --amountIn=<amount_satoshis>
```

> The above command returns:

```shell
{
  "fundingTxIndex": 1,
  "fundingTxId": "adaf98b2897b1cd78593f185ebbf6cdfbca73644127940610fdb4c55c3861a6b",
  "capacity": 350000,
  "balance": 350000000
}
```

Increase the local balance of a given channel by the provided amount, by creating an on-chain transaction.
Note that **amountIn** will be taken from the on-chain wallet, which needs to have enough funds available.

### HTTP Request

`POST http://localhost:8080/splicein`

### Parameters

Parameter   | Description                                                     | Optional | Type
----------- | --------------------------------------------------------------- | -------- | -------------------------------------
channelId   | The channelId of the channel you want to update                 | No       | 32-bytes-HexString (String)
amountIn    | Amount of satoshis that will be added to the channel            | No       | Satoshis (Integer)
pushMsat    | Amount of millisatoshi to unilaterally push to the counterparty | Yes      | Millisatoshis (Integer)

## Splice-out

```shell
curl -s -u :<eclair_api_password> -X POST -F channelId=<channel> -F amountOut=<amount_satoshis> -F address=<on_chain_address> "http://localhost:8080/spliceout"

# with eclair-cli
eclair-cli spliceout --channelId=<channel> --amountOut=<amount_satoshis> --address=<on_chain_address>
```

> The above command returns:

```shell
{
  "fundingTxIndex": 2,
  "fundingTxId": "5abf88cdcee34a2f45a2dd48e1c6597b3a8756cc6054e01d3cfd8ca45f32b0b9",
  "capacity": 309098,
  "balance": 309098000
}
```

Decrease the local balance of a given channel by the provided amount, by creating an on-chain transaction that sends **amountOut** to the provided **address**.

### HTTP Request

`POST http://localhost:8080/spliceout`

### Parameters

Parameter    | Description                                                     | Optional | Type
------------ | --------------------------------------------------------------- | -------- | -------------------------------------
channelId    | The channelId of the channel you want to update                 | No       | 32-bytes-HexString (String)
amountOut    | Amount of satoshis that will be taken from the channel          | No       | Satoshis (Integer)
address      | On-chain address to which the funds will be sent                | Yes (*)  | String (bech32)
scriptPubKey | Script to which the funds will be sent                          | Yes (*)  | HexString

(*) Either **address** or **scriptPubKey** must be provided, to know where funds should be sent.

## Splice RBF

```shell
curl -s -u :<eclair_api_password> -X POST -F channelId=<channel> -F targetFeerateSatByte=<target_feerate_satoshis_per_byte> -F fundingFeeBudgetSatoshis=<funding_fee_budget_satoshis> "http://localhost:8080/rbfsplice"

# with eclair-cli
eclair-cli rbfsplice --channelId=<channel> --targetFeerateSatByte=<target_feerate_satoshis_per_byte> --fundingFeeBudgetSatoshis=<funding_fee_budget_satoshis>
```

> The above command returns:

```shell
{
  "rbfIndex": 1,
  "fundingTxId": "5abf88cdcee34a2f45a2dd48e1c6597b3a8756cc6054e01d3cfd8ca45f32b0b9",
  "fee": 3413
}
```

Increase the feerate of a splice transaction that isn't confirming (replace-by-fee).
This can be called even for splice transaction that were initiated by the remote peer.

### HTTP Request

`POST http://localhost:8080/rbfsplice`

### Parameters

Parameter                | Description                                                         | Optional | Type
------------------------ | ------------------------------------------------------------------- | -------- | -------------------------------------
channelId                | The channelId of the channel with an unconfirmed splice transaction | No       | 32-bytes-HexString (String)
fundingFeeBudgetSatoshis | Maximum fees (in satoshis) of the splice transaction                | No       | Satoshis (Integer)
targetFeerateSatByte     | Feerate in sat/byte to apply to the splice transaction              | No       | Satoshis (Integer)
lockTime                 | The nLockTime to apply to the splice transaction                    | Yes      | Integer

# Close

## Close

```shell
curl -s -u :<eclair_api_password> -X POST -F channelId=<channel> "http://localhost:8080/close"

# with eclair-cli
eclair-cli close --channelId=<channel>
```

> The above command returns:

```shell
{
  "<channel>": "ok"
}
```

Initiates a cooperative close for given channels that belong to this eclair node.
The API returns once the _closing_signed_ message has been negotiated.
The endpoint supports receiving multiple channel id(s) or short channel id(s); to close multiple channels, you can use the parameters `channelIds` or `shortChannelIds` below.

If you specified a `scriptPubKey` then the closing transaction will spend to that address.

You can specify a fee range for the closing transaction with the `preferredFeerateSatByte`, `minFeerateSatByte` and `maxFeerateSatByte`.

### HTTP Request

`POST http://localhost:8080/close`

### Parameters

Parameter               | Description                                                         | Optional | Type
----------------------- | ------------------------------------------------------------------- | -------- | ---------------------------
channelId               | The channelId of the channel you want to close                      | No       | 32-bytes-HexString (String)
shortChannelId          | The shortChannelId of the channel you want to close                 | Yes      | ShortChannelId (String)
channelIds              | List of channelIds to close                                         | Yes      | CSV or JSON list of channelId
shortChannelIds         | List of shortChannelIds to close                                    | Yes      | CSV or JSON list of shortChannelId
scriptPubKey            | A serialized scriptPubKey that you want to use to close the channel | Yes      | HexString (String)
preferredFeerateSatByte | Preferred feerate (sat/byte) for the closing transaction            | Yes      | Satoshis (Integer)
minFeerateSatByte       | Minimum feerate (sat/byte) for the closing transaction              | Yes      | Satoshis (Integer)
maxFeerateSatByte       | Maximum feerate (sat/byte) for the closing transaction              | Yes      | Satoshis (Integer)

## ForceClose

```shell
curl -s -u :<eclair_api_password> -X POST -F channelId=<channel> "http://localhost:8080/forceclose"

# with eclair-cli
eclair-cli forceclose --channelId=<channel>
```

> The above command returns:

```shell
{
  "<channel>": "ok"
}
```

Initiates a unilateral close for given channels that belong to this eclair node.
Once the commitment has been broadcast, the API returns its transaction id.
The endpoint supports receiving multiple channel id(s) or short channel id(s); to close multiple channels, you can use the parameters `channelIds` or `shortChannelIds` below.

### HTTP Request

`POST http://localhost:8080/forceclose`

### Parameters

Parameter                | Description                                             | Optional | Type
------------------------ | ------------------------------------------------------- | -------- | ---------------------------
channelId                | The channelId of the channel you want to close          | No       | 32-bytes-HexString (String)
shortChannelId           | The shortChannelId of the channel you want to close     | Yes      | ShortChannelId (String)
channelIds               | List of channelIds to force-close                       | Yes      | CSV or JSON list of channelId
shortChannelIds          | List of shortChannelIds to force-close                  | Yes      | CSV or JSON list of shortChannelId
maxClosingFeerateSatByte | Maximum feerate (sat/byte) of non-critical transactions | Yes      | Satoshis (Integer)

## BumpForceClose

```shell
curl -s -u :<eclair_api_password> -X POST -F channelId=<channel> -F priority=<priority> "http://localhost:8080/bumpforceclose"

# with eclair-cli
eclair-cli bumpforceclose --channelId=<channel> --priority=<priority>
```
> The above command returns:

```shell
{
  "<channel>": "ok"
}
```

Changes the priority of the automatic fee-bumping that is applied to a closing channel.
This can be useful when you want to get your funds back faster and don't mind paying more fees for that.
The endpoint supports receiving multiple channel id(s) or short channel id(s); to close multiple channels, you can use the parameters `channelIds` or `shortChannelIds` below.

### HTTP Request

`POST http://localhost:8080/bumpforceclose`

### Parameters

Parameter      | Description                                            | Optional | Type
-------------- | ------------------------------------------------------ | -------- | ---------------------------
channelId      | The channelId of the channel you want to bump          | No       | 32-bytes-HexString (String)
priority       | The priority for that transaction (slow, medium, fast) | No       | Priority (String)
shortChannelId | The shortChannelId of the channel you want to bump     | Yes      | ShortChannelId (String)
channelIds     | List of closing channelIds to bump                     | Yes      | CSV or JSON list of channelId
shortChannelIds| List of closing shortChannelIds to bump                | Yes      | CSV or JSON list of shortChannelId

# UpdateRelayFee

## UpdateRelayFee

```shell
curl -s -u :<eclair_api_password> -X POST -F nodeId=<node_id> \
     -F feeBaseMsat=<feebase> -F feeProportionalMillionths=<feeproportional> \
     "http://localhost:8080/updaterelayfee"

#eclair-cli
eclair-cli updaterelayfee \
  --nodeId=<node_id> \
  --feeBaseMsat=<feebase> \
  --feeProportionalMillionths=<feeproportional>
```

> The above command returns:

```shell
{
  "<channelId>": "ok"
}
```

Updates the fee policy for the specified _nodeId_.
The endpoint supports receiving multiple node id(s); to update multiple nodes, you can use the `nodeIds` parameter instead of `nodeId`.

New updates for every channel you have with the selected node(s) will be broadcast to the network.
Note that you can call this API even without having any channel with the selected node(s).
That will ensure that when you open channels to the selected node(s), the fees you have configured will be automatically applied (instead of the default fees from your `eclair.conf`).

### HTTP Request

`POST http://localhost:8080/updaterelayfee`

### Parameters

Parameter                 | Description                                          | Optional | Type
------------------------- | ---------------------------------------------------- | -------- | -----------------------------------------------
nodeId                    | The **nodeId** of the peer you want to update        | Yes (*)  | 33-bytes-HexString (String)
nodeIds                   | The **nodeIds** of the peers you want to update      | Yes (*)  | CSV or JSON list of 33-bytes-HexString (String)
feeBaseMsat               | The new base fee to use                              | No       | Millisatoshi (Integer)
feeProportionalMillionths | The new proportional fee to use                      | No       | Integer

(*): you must specify either nodeId or nodeIds, but not both.

# Peers

## Peers

```shell
curl -s -u :<eclair_api_password> -X POST "http://localhost:8080/peers"

# with eclair-cli
eclair-cli peers
```

> The above command returns:

```json
[
   {
      "nodeId":"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
      "state":"CONNECTED",
      "address":"34.239.230.56:9735",
      "channels":1
   },
   {
      "nodeId":"039dc0e0b1d25905e44fdf6f8e89755a5e219685840d0bc1d28d3308f9628a3585",
      "state":"DISCONNECTED",
      "channels":1
   }
]
```

Returns the list of currently known peers, both connected and disconnected.

### HTTP Request

`POST http://localhost:8080/peers`

# Channels

## Channels

```shell
curl -s -u :<eclair_api_password> -X POST "http://localhost:8080/channels"

# with eclair-cli
eclair-cli channels
```

The units of returned fields that are not obvious from their names:

field           | unit
----------------|--------
dustLimit       | sats
channelReserve  | sats
htlcMinimum     | msats
toSelfDelay     | blocks
commitTxFeerate | sats/kw

> The above command returns:

```json
[
  {
    "nodeId": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257",
    "channelId": "da9171e06fbb7d18a50deeab567dbf35498c69da7542d0222c2f22da7fa2e300",
    "state": "NORMAL",
    "data": {
      "type": "DATA_NORMAL",
      "commitments": {
        "channelParams": {
          "channelId": "da9171e06fbb7d18a50deeab567dbf35498c69da7542d0222c2f22da7fa2e300",
          "channelConfig": [
            "funding_pubkey_based_channel_keypath"
          ],
          "channelFeatures": [
            "option_dual_fund"
          ],
          "localParams": {
            "nodeId": "023b19f5990b2eee1a3bfc66cb9fdfeba62f7e058f13faf236249cc8d53543f20b",
            "fundingKeyPath": [
              2783369202,
              2564506293,
              2430501769,
              3674750487,
              3321711430,
              82756978,
              1950369508,
              453888639,
              2147483649
            ],
            "isChannelOpener": true,
            "paysCommitTxFees": true,
            "initFeatures": {
              "activated": {
                "option_simple_close": "optional",
                "option_route_blinding": "optional",
                "option_attribution_data": "optional",
                "option_dual_fund": "optional",
                "gossip_queries_ex": "optional",
                "option_quiesce": "optional",
                "option_data_loss_protect": "mandatory",
                "option_static_remotekey": "mandatory",
                "option_scid_alias": "optional",
                "option_onion_messages": "optional",
                "option_support_large_channel": "optional",
                "option_anchors_zero_fee_htlc_tx": "optional",
                "payment_secret": "mandatory",
                "option_shutdown_anysegwit": "optional",
                "option_channel_type": "mandatory",
                "basic_mpp": "optional",
                "gossip_queries": "optional",
                "option_simple_taproot": "optional",
                "var_onion_optin": "mandatory",
                "option_splice": "optional"
              }
            }
          },
          "remoteParams": {
            "nodeId": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257",
            "revocationBasepoint": "034ba17b08490e0ef8feacca5ef1a9b9f2f7d00b31e76d80f0dcf266cb1c21de9d",
            "paymentBasepoint": "039262ef17683a6950dc4da3bbe43ac7361243e28a0c558c34635652a8fa0ca0f3",
            "delayedPaymentBasepoint": "03fce0346771feb9e6c0a5cb87b7daa54cf5dc6b884cf03709c8daf2e51399fbba",
            "htlcBasepoint": "025270d5456149fc611cafa7910efe354b88efd086e544e3da483d7d783c723316",
            "initFeatures": {
              "activated": {
                "option_simple_close": "optional",
                "option_route_blinding": "optional",
                "option_attribution_data": "optional",
                "option_dual_fund": "optional",
                "gossip_queries_ex": "optional",
                "option_quiesce": "optional",
                "option_data_loss_protect": "mandatory",
                "option_static_remotekey": "mandatory",
                "option_scid_alias": "optional",
                "option_onion_messages": "optional",
                "option_support_large_channel": "optional",
                "option_anchors_zero_fee_htlc_tx": "optional",
                "payment_secret": "mandatory",
                "option_shutdown_anysegwit": "optional",
                "option_channel_type": "mandatory",
                "basic_mpp": "optional",
                "gossip_queries": "optional",
                "option_simple_taproot": "optional",
                "var_onion_optin": "mandatory",
                "option_splice": "optional"
              }
            }
          },
          "channelFlags": {
            "nonInitiatorPaysCommitFees": false,
            "announceChannel": true
          }
        },
        "changes": {
          "localChanges": {
            "proposed": [],
            "signed": [],
            "acked": []
          },
          "remoteChanges": {
            "proposed": [],
            "acked": [],
            "signed": []
          },
          "localNextHtlcId": 1,
          "remoteNextHtlcId": 0
        },
        "active": [
          {
            "fundingTxIndex": 2,
            "fundingInput": "5abf88cdcee34a2f45a2dd48e1c6597b3a8756cc6054e01d3cfd8ca45f32b0b9:1",
            "fundingAmount": 309098,
            "localFunding": {
              "status": "confirmed",
              "shortChannelId": "68415x1x1"
            },
            "remoteFunding": {
              "status": "locked"
            },
            "commitmentFormat": "anchor_outputs",
            "localCommitParams": {
              "dustLimit": 546,
              "htlcMinimum": 1000,
              "maxHtlcValueInFlight": 90000000,
              "maxAcceptedHtlcs": 10,
              "toSelfDelay": 100
            },
            "localCommit": {
              "index": 2,
              "spec": {
                "htlcs": [],
                "commitTxFeerate": 2500,
                "toLocal": 269098000,
                "toRemote": 40000000
              },
              "txId": "073a4d7c017c7dc8abe581a139d633e3f89da0f9bd715df6c04ef12f228271dd",
              "remoteSig": {
                "sig": "a4256a58aa56a292ad5d90069b68e4596e67551cb9d2c150e3489923eaa64eae5e7d3f3d7e873fc391e7abfd4711280faa57f6d4479a8a5f7fc76daa82717350"
              },
              "htlcRemoteSigs": []
            },
            "remoteCommitParams": {
              "dustLimit": 546,
              "htlcMinimum": 500,
              "maxHtlcValueInFlight": 75000000,
              "maxAcceptedHtlcs": 15,
              "toSelfDelay": 144
            },
            "remoteCommit": {
              "index": 2,
              "spec": {
                "htlcs": [],
                "commitTxFeerate": 2500,
                "toLocal": 40000000,
                "toRemote": 269098000
              },
              "txId": "7daf6be99b7edc538659f4963405dd364f788cbc90f3e226324c160e70659580",
              "remotePerCommitmentPoint": "02b21682c1eea89970f939d8d89482a1a6e0b26c91ce9578d61563689c7f0d8f73"
            }
          }
        ],
        "inactive": [
          {
            "fundingTxIndex": 1,
            "fundingInput": "adaf98b2897b1cd78593f185ebbf6cdfbca73644127940610fdb4c55c3861a6b:0",
            "fundingAmount": 350000,
            "localFunding": {
              "status": "confirmed",
              "shortChannelId": "68405x1x0"
            },
            "remoteFunding": {
              "status": "locked"
            },
            "commitmentFormat": "anchor_outputs",
            "localCommitParams": {
              "dustLimit": 546,
              "htlcMinimum": 1000,
              "maxHtlcValueInFlight": 90000000,
              "maxAcceptedHtlcs": 10,
              "toSelfDelay": 100
            },
            "localCommit": {
              "index": 0,
              "spec": {
                "htlcs": [],
                "commitTxFeerate": 2500,
                "toLocal": 350000000,
                "toRemote": 0
              },
              "txId": "a00d3005cb1d21d4fc535b4bf38bc3db9d02a440da67c1d656a81ad5b6306448",
              "remoteSig": {
                "sig": "55de47e2099e49e0ebeb445a274f91de1e971b4fad93b906f7846e99d0e52f785a08f0c68240f9f3c113d5301a8cfddff7220077125b1c3a1e10091ed9c14ba1"
              },
              "htlcRemoteSigs": []
            },
            "remoteCommitParams": {
              "dustLimit": 546,
              "htlcMinimum": 500,
              "maxHtlcValueInFlight": 75000000,
              "maxAcceptedHtlcs": 15,
              "toSelfDelay": 144
            },
            "remoteCommit": {
              "index": 0,
              "spec": {
                "htlcs": [],
                "commitTxFeerate": 2500,
                "toLocal": 0,
                "toRemote": 350000000
              },
              "txId": "54f58d643ca0b032d25e3d0e0d580bbe2473232eacf95b2584426c63f93cf18b",
              "remotePerCommitmentPoint": "0282dd0c03f94c50333fa8c3139484c2570f156caabf12ceacbc97a86edc6bef42"
            }
          }
        ],
        "remoteNextCommitInfo": "02cc7070166e66990abee671336e1f6289d7dc38cb3548852acf6c01e63e59f3c3",
        "remotePerCommitmentSecrets": null,
        "originChannels": {}
      },
      "aliases": {
        "localAlias": "0x543ce32b242f0e",
        "remoteAlias": "0x1bbbc9005b30bee"
      },
      "lastAnnouncement_opt": {
        "nodeSignature1": "44cbc4efa68ce464e7d340dbb4b8378e03398199003eb1cb42fd375b0c6b51653d31e49ac3d26ff513cf18bdc09e686838b07a2176994ead0b2b8123f02aa3f7",
        "nodeSignature2": "341dcedf1f82ac0d67c0aa89a4203389de342d6bb3cabb673f1b0c500f82cfc87a0c926d5c8a34d1e4498c94767a8cf8b2fea3d2e794b5573f21f6c625b84649",
        "bitcoinSignature1": "24781b9e40f68564edaa0403a2604e3a97132b4cff9f8d4126cfc69b9f73bf17598f2e48522f29072be8b855846ed037d975fc998e0da548c9f69fa5ee81f14a",
        "bitcoinSignature2": "e666ba9cf60994ae81f42c20b873842038c396ef8c30d593bcde841d98c5150d7f3e91adabc63e3787b4cef9357e38a02b5dc571dd69bd32caf3c5329b97a610",
        "features": {
          "activated": {}
        },
        "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
        "shortChannelId": "68415x1x1",
        "nodeId1": "023b19f5990b2eee1a3bfc66cb9fdfeba62f7e058f13faf236249cc8d53543f20b",
        "nodeId2": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257",
        "bitcoinKey1": "02bedf835005654cbb33fbb1ed8de7caa794b5b5b9ee55e5ba08f700a58975a0a2",
        "bitcoinKey2": "037bc091661c7416ec9587275384258258dfa72e95c833d3730e1d8bf892e3cb62",
        "tlvStream": {}
      },
      "channelUpdate": {
        "signature": "6b9ecd8c963a2e77dd2996f3b64c8aeabee3aa10be75fd43716c433a0c3ac4aa496b9dbdd0c1e6350f1482ff7dbd60fe775b7cd0697737c05802278df0a7eadf",
        "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
        "shortChannelId": "68415x1x1",
        "timestamp": {
          "iso": "2026-05-22T11:50:31Z",
          "unix": 1779450631
        },
        "messageFlags": {
          "dontForward": false
        },
        "channelFlags": {
          "isEnabled": true,
          "isNode1": true
        },
        "cltvExpiryDelta": 144,
        "htlcMinimumMsat": 500,
        "feeBaseMsat": 15,
        "feeProportionalMillionths": 500,
        "htlcMaximumMsat": 75000000,
        "tlvStream": {}
      }
    }
  },
  {
    "nodeId": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257",
    "channelId": "bbc8ccc2dfa98426c38d3f30a2fa92d509b19d9971d5bcb519a8fb50b0c90911",
    "state": "NORMAL",
    "data": {
      "type": "DATA_NORMAL",
      "commitments": {
        "channelParams": {
          "channelId": "bbc8ccc2dfa98426c38d3f30a2fa92d509b19d9971d5bcb519a8fb50b0c90911",
          "channelConfig": [
            "funding_pubkey_based_channel_keypath"
          ],
          "channelFeatures": [
            "option_dual_fund",
            "option_scid_alias"
          ],
          "localParams": {
            "nodeId": "023b19f5990b2eee1a3bfc66cb9fdfeba62f7e058f13faf236249cc8d53543f20b",
            "fundingKeyPath": [
              942782628,
              4294410734,
              273297146,
              1031007703,
              1399293918,
              4112967135,
              4046378412,
              1768396913,
              2147483649
            ],
            "isChannelOpener": true,
            "paysCommitTxFees": true,
            "initFeatures": {
              "activated": {
                "option_simple_close": "optional",
                "option_route_blinding": "optional",
                "option_attribution_data": "optional",
                "option_dual_fund": "optional",
                "gossip_queries_ex": "optional",
                "option_quiesce": "optional",
                "option_data_loss_protect": "mandatory",
                "option_static_remotekey": "mandatory",
                "option_scid_alias": "optional",
                "option_onion_messages": "optional",
                "option_support_large_channel": "optional",
                "option_anchors_zero_fee_htlc_tx": "optional",
                "payment_secret": "mandatory",
                "option_shutdown_anysegwit": "optional",
                "option_channel_type": "mandatory",
                "basic_mpp": "optional",
                "gossip_queries": "optional",
                "option_simple_taproot": "optional",
                "var_onion_optin": "mandatory",
                "option_splice": "optional"
              }
            }
          },
          "remoteParams": {
            "nodeId": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257",
            "revocationBasepoint": "035ada31eb90b2631d5a12321ab9d05aa91ef3d37a1354618123cd5bf83ce78e09",
            "paymentBasepoint": "03c490b5b1b0bc36f74a93b0eb540cd35b922e11c89ad8c15767c7daaec6cfc6b2",
            "delayedPaymentBasepoint": "03ba647b47e76cd0f973a3e8ed931a64198141be5a0b6a9177fc52426f26de9f80",
            "htlcBasepoint": "02eab73e4b3a435f9c67c87e9e1a0fb6021f1b2040bbf313e082d634ea2f2a2ccb",
            "initFeatures": {
              "activated": {
                "option_simple_close": "optional",
                "option_route_blinding": "optional",
                "option_attribution_data": "optional",
                "option_dual_fund": "optional",
                "gossip_queries_ex": "optional",
                "option_quiesce": "optional",
                "option_data_loss_protect": "mandatory",
                "option_static_remotekey": "mandatory",
                "option_scid_alias": "optional",
                "option_onion_messages": "optional",
                "option_support_large_channel": "optional",
                "option_anchors_zero_fee_htlc_tx": "optional",
                "payment_secret": "mandatory",
                "option_shutdown_anysegwit": "optional",
                "option_channel_type": "mandatory",
                "basic_mpp": "optional",
                "gossip_queries": "optional",
                "option_simple_taproot": "optional",
                "var_onion_optin": "mandatory",
                "option_splice": "optional"
              }
            }
          },
          "channelFlags": {
            "nonInitiatorPaysCommitFees": false,
            "announceChannel": false
          }
        },
        "changes": {
          "localChanges": {
            "proposed": [],
            "signed": [],
            "acked": []
          },
          "remoteChanges": {
            "proposed": [],
            "acked": [],
            "signed": []
          },
          "localNextHtlcId": 0,
          "remoteNextHtlcId": 0
        },
        "active": [
          {
            "fundingTxIndex": 0,
            "fundingInput": "5586f707cbcf64e1114d0ec6bed6aa8961ed3d153e2f724d7103f29e5762c747:0",
            "fundingAmount": 280000,
            "localFunding": {
              "status": "confirmed",
              "shortChannelId": "68425x1x0"
            },
            "remoteFunding": {
              "status": "locked"
            },
            "commitmentFormat": "simple_taproot",
            "localCommitParams": {
              "dustLimit": 546,
              "htlcMinimum": 1000,
              "maxHtlcValueInFlight": 84000000,
              "maxAcceptedHtlcs": 10,
              "toSelfDelay": 100
            },
            "localCommit": {
              "index": 0,
              "spec": {
                "htlcs": [],
                "commitTxFeerate": 2500,
                "toLocal": 280000000,
                "toRemote": 0
              },
              "txId": "b1ea78fc6cc2ffa7c98a912947d8c9950b6cc13ba8a63fdd4a7f5e102d6c27fa",
              "remoteSig": {
                "partialSig": "ff651c56e2d5507bc2c601d160a8c2a0194b1c684de4920633bc787e22e7bd3b",
                "nonce": "0352a7d7ce88e416bd9e4933b5886da08f2d18d80bbb590ab97219ccb1434ea47e03ce028338819653ef20460e063481d49088ce50cc075fc5e364252192336ed87b"
              },
              "htlcRemoteSigs": []
            },
            "remoteCommitParams": {
              "dustLimit": 546,
              "htlcMinimum": 500,
              "maxHtlcValueInFlight": 70000000,
              "maxAcceptedHtlcs": 15,
              "toSelfDelay": 144
            },
            "remoteCommit": {
              "index": 0,
              "spec": {
                "htlcs": [],
                "commitTxFeerate": 2500,
                "toLocal": 0,
                "toRemote": 280000000
              },
              "txId": "c93980bd6e582ff8a39bfdea75b5a7f4a82081a604f7145089e672c96ddcde41",
              "remotePerCommitmentPoint": "02b26e989f1ea02612fd719dd410fba74e3e2e16c695cdc8b7b2509f909b98e860"
            }
          }
        ],
        "inactive": [],
        "remoteNextCommitInfo": "03ad43b83de456453749e69fe57067f4dde98434329c0234ebe54e5bc2c4e14497",
        "remotePerCommitmentSecrets": null,
        "originChannels": {}
      },
      "aliases": {
        "localAlias": "0x241b2817588036e",
        "remoteAlias": "0x1ecfd3418a47592"
      },
      "channelUpdate": {
        "signature": "9659f77ea03458907c8b1c29ee36ce1c1f0ab616d9985b7cce7a5c12288673646d89356cc8a97bba656599094ae3a7780f701015309eec6adb3afe8322b932b8",
        "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
        "shortChannelId": "0x241b2817588036e",
        "timestamp": {
          "iso": "2026-05-22T11:51:57Z",
          "unix": 1779450717
        },
        "messageFlags": {
          "dontForward": true
        },
        "channelFlags": {
          "isEnabled": true,
          "isNode1": true
        },
        "cltvExpiryDelta": 144,
        "htlcMinimumMsat": 500,
        "feeBaseMsat": 15,
        "feeProportionalMillionths": 500,
        "htlcMaximumMsat": 70000000,
        "tlvStream": {}
      }
    }
  }
]
```

Returns the list of local channels, optionally filtered by remote node.

### HTTP Request

`POST http://localhost:8080/channels`

### Parameters

Parameter | Description                                                 | Optional | Type
--------- | ----------------------------------------------------------- | -------- | ---------------------------
nodeId    | The remote **nodeId** to be used as filter for the channels | Yes      | 33-bytes-HexString (String)

## Channel

```shell
curl -s -u :<eclair_api_password> -X POST -F channelId=<channel>  "http://localhost:8080/channel"

# with eclair-cli
eclair-cli channel --channelId=<channel>
```

The units of returned fields that are not obvious from their names:

field           | unit
----------------|--------
dustLimit       | sats
channelReserve  | sats
htlcMinimum     | msats
toSelfDelay     | blocks
commitTxFeerate | sats/kw

> The above command returns:

```json
{
  "nodeId": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257",
  "channelId": "bbc8ccc2dfa98426c38d3f30a2fa92d509b19d9971d5bcb519a8fb50b0c90911",
  "state": "NORMAL",
  "data": {
    "type": "DATA_NORMAL",
    "commitments": {
      "channelParams": {
        "channelId": "bbc8ccc2dfa98426c38d3f30a2fa92d509b19d9971d5bcb519a8fb50b0c90911",
        "channelConfig": [
          "funding_pubkey_based_channel_keypath"
        ],
        "channelFeatures": [
          "option_dual_fund",
          "option_scid_alias"
        ],
        "localParams": {
          "nodeId": "023b19f5990b2eee1a3bfc66cb9fdfeba62f7e058f13faf236249cc8d53543f20b",
          "fundingKeyPath": [
            942782628,
            4294410734,
            273297146,
            1031007703,
            1399293918,
            4112967135,
            4046378412,
            1768396913,
            2147483649
          ],
          "isChannelOpener": true,
          "paysCommitTxFees": true,
          "initFeatures": {
            "activated": {
              "option_simple_close": "optional",
              "option_route_blinding": "optional",
              "option_attribution_data": "optional",
              "option_dual_fund": "optional",
              "gossip_queries_ex": "optional",
              "option_quiesce": "optional",
              "option_data_loss_protect": "mandatory",
              "option_static_remotekey": "mandatory",
              "option_scid_alias": "optional",
              "option_onion_messages": "optional",
              "option_support_large_channel": "optional",
              "option_anchors_zero_fee_htlc_tx": "optional",
              "payment_secret": "mandatory",
              "option_shutdown_anysegwit": "optional",
              "option_channel_type": "mandatory",
              "basic_mpp": "optional",
              "gossip_queries": "optional",
              "option_simple_taproot": "optional",
              "var_onion_optin": "mandatory",
              "option_splice": "optional"
            }
          }
        },
        "remoteParams": {
          "nodeId": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257",
          "revocationBasepoint": "035ada31eb90b2631d5a12321ab9d05aa91ef3d37a1354618123cd5bf83ce78e09",
          "paymentBasepoint": "03c490b5b1b0bc36f74a93b0eb540cd35b922e11c89ad8c15767c7daaec6cfc6b2",
          "delayedPaymentBasepoint": "03ba647b47e76cd0f973a3e8ed931a64198141be5a0b6a9177fc52426f26de9f80",
          "htlcBasepoint": "02eab73e4b3a435f9c67c87e9e1a0fb6021f1b2040bbf313e082d634ea2f2a2ccb",
          "initFeatures": {
            "activated": {
              "option_simple_close": "optional",
              "option_route_blinding": "optional",
              "option_attribution_data": "optional",
              "option_dual_fund": "optional",
              "gossip_queries_ex": "optional",
              "option_quiesce": "optional",
              "option_data_loss_protect": "mandatory",
              "option_static_remotekey": "mandatory",
              "option_scid_alias": "optional",
              "option_onion_messages": "optional",
              "option_support_large_channel": "optional",
              "option_anchors_zero_fee_htlc_tx": "optional",
              "payment_secret": "mandatory",
              "option_shutdown_anysegwit": "optional",
              "option_channel_type": "mandatory",
              "basic_mpp": "optional",
              "gossip_queries": "optional",
              "option_simple_taproot": "optional",
              "var_onion_optin": "mandatory",
              "option_splice": "optional"
            }
          }
        },
        "channelFlags": {
          "nonInitiatorPaysCommitFees": false,
          "announceChannel": false
        }
      },
      "changes": {
        "localChanges": {
          "proposed": [],
          "signed": [],
          "acked": []
        },
        "remoteChanges": {
          "proposed": [],
          "acked": [],
          "signed": []
        },
        "localNextHtlcId": 0,
        "remoteNextHtlcId": 0
      },
      "active": [
        {
          "fundingTxIndex": 0,
          "fundingInput": "5586f707cbcf64e1114d0ec6bed6aa8961ed3d153e2f724d7103f29e5762c747:0",
          "fundingAmount": 280000,
          "localFunding": {
            "status": "confirmed",
            "shortChannelId": "68425x1x0"
          },
          "remoteFunding": {
            "status": "locked"
          },
          "commitmentFormat": "simple_taproot",
          "localCommitParams": {
            "dustLimit": 546,
            "htlcMinimum": 1000,
            "maxHtlcValueInFlight": 84000000,
            "maxAcceptedHtlcs": 10,
            "toSelfDelay": 100
          },
          "localCommit": {
            "index": 0,
            "spec": {
              "htlcs": [],
              "commitTxFeerate": 2500,
              "toLocal": 280000000,
              "toRemote": 0
            },
            "txId": "b1ea78fc6cc2ffa7c98a912947d8c9950b6cc13ba8a63fdd4a7f5e102d6c27fa",
            "remoteSig": {
              "partialSig": "ff651c56e2d5507bc2c601d160a8c2a0194b1c684de4920633bc787e22e7bd3b",
              "nonce": "0352a7d7ce88e416bd9e4933b5886da08f2d18d80bbb590ab97219ccb1434ea47e03ce028338819653ef20460e063481d49088ce50cc075fc5e364252192336ed87b"
            },
            "htlcRemoteSigs": []
          },
          "remoteCommitParams": {
            "dustLimit": 546,
            "htlcMinimum": 500,
            "maxHtlcValueInFlight": 70000000,
            "maxAcceptedHtlcs": 15,
            "toSelfDelay": 144
          },
          "remoteCommit": {
            "index": 0,
            "spec": {
              "htlcs": [],
              "commitTxFeerate": 2500,
              "toLocal": 0,
              "toRemote": 280000000
            },
            "txId": "c93980bd6e582ff8a39bfdea75b5a7f4a82081a604f7145089e672c96ddcde41",
            "remotePerCommitmentPoint": "02b26e989f1ea02612fd719dd410fba74e3e2e16c695cdc8b7b2509f909b98e860"
          }
        }
      ],
      "inactive": [],
      "remoteNextCommitInfo": "03ad43b83de456453749e69fe57067f4dde98434329c0234ebe54e5bc2c4e14497",
      "remotePerCommitmentSecrets": null,
      "originChannels": {}
    },
    "aliases": {
      "localAlias": "0x241b2817588036e",
      "remoteAlias": "0x1ecfd3418a47592"
    },
    "channelUpdate": {
      "signature": "9659f77ea03458907c8b1c29ee36ce1c1f0ab616d9985b7cce7a5c12288673646d89356cc8a97bba656599094ae3a7780f701015309eec6adb3afe8322b932b8",
      "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
      "shortChannelId": "0x241b2817588036e",
      "timestamp": {
        "iso": "2026-05-22T11:51:57Z",
        "unix": 1779450717
      },
      "messageFlags": {
        "dontForward": true
      },
      "channelFlags": {
        "isEnabled": true,
        "isNode1": true
      },
      "cltvExpiryDelta": 144,
      "htlcMinimumMsat": 500,
      "feeBaseMsat": 15,
      "feeProportionalMillionths": 500,
      "htlcMaximumMsat": 70000000,
      "tlvStream": {}
    }
  }
}
```

Returns detailed information about a local channel.

### HTTP Request

`POST http://localhost:8080/channel`

### Parameters

Parameter | Description                             | Optional | Type
--------- | --------------------------------------- | -------- | ---------------------------
channelId | The channel id of the requested channel | No       | 32-bytes-HexString (String)

## ClosedChannels

```shell
curl -s -u :<eclair_api_password> -X POST  "http://localhost:8080/closedchannels"

# with eclair-cli
eclair-cli closedchannels
```

> The above command returns:

```json
[
  {
    "channelId": "da9171e06fbb7d18a50deeab567dbf35498c69da7542d0222c2f22da7fa2e300",
    "remoteNodeId": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257",
    "fundingTxId": "5abf88cdcee34a2f45a2dd48e1c6597b3a8756cc6054e01d3cfd8ca45f32b0b9",
    "fundingOutputIndex": 1,
    "fundingTxIndex": 2,
    "fundingKeyPath": "m/635885554'/417022645'/283018121'/1527266839'/1174227782'/82756978/1950369508/453888639/1'",
    "channelFeatures": "option_dual_fund",
    "isChannelOpener": true,
    "commitmentFormat": "anchor_outputs",
    "announced": true,
    "capacity": 309098,
    "closingTxId": "f5311e98b46a217057e4a03e2ef16d86f4a5e568b3d45b8431e911e8cb0ca2fc",
    "closingType": "mutual-close",
    "closingScript": "00141a2d08d41a06c33e934caa6001a0950b8a050b58",
    "localBalance": 269098000,
    "remoteBalance": 40000000,
    "closingAmount": 269098
  }
]
```

Returns the list of recently closed local channels.

### HTTP Request

`POST http://localhost:8080/closedchannels`

### Parameters

Parameter | Description                             | Optional | Type
--------- | --------------------------------------- | -------- | ---------------------------
nodeId    | The **nodeId** of the channel peer      | Yes      | 33-bytes-HexString (String)
count     | Limits the number of results returned   | Yes      | Integer
skip      | Skip some number of results             | Yes      | Integer

# Network

A set of API methods to query the network view of eclair.

## Nodes

```shell
curl -s -u :<eclair_api_password> -X POST "http://localhost:8080/nodes"

# with eclair-cli
eclair-cli nodes
```

> The above command returns:

```json
[
  {
    "signature": "84906bbb6c42a47dc872bb009aa25b79750d9bd5d108551e0e6d9a8dd9260d12284d82760c5084c3b1fea64a47973a4a61ec302d22a93d099926e4c4571868f7",
    "features": {
      "activated": {
        "option_simple_close": "optional",
        "option_route_blinding": "optional",
        "option_attribution_data": "optional",
        "option_dual_fund": "optional",
        "gossip_queries_ex": "optional",
        "option_quiesce": "optional",
        "option_data_loss_protect": "mandatory",
        "option_static_remotekey": "mandatory",
        "option_scid_alias": "optional",
        "option_onion_messages": "optional",
        "option_support_large_channel": "optional",
        "option_anchors_zero_fee_htlc_tx": "optional",
        "payment_secret": "mandatory",
        "option_shutdown_anysegwit": "optional",
        "option_channel_type": "mandatory",
        "basic_mpp": "optional",
        "gossip_queries": "optional",
        "option_simple_taproot": "optional",
        "var_onion_optin": "mandatory",
        "option_splice": "optional"
      }
    },
    "timestamp": {
      "iso": "2026-05-22T11:44:31Z",
      "unix": 1779450271
    },
    "nodeId": "023b19f5990b2eee1a3bfc66cb9fdfeba62f7e058f13faf236249cc8d53543f20b",
    "rgbColor": "#49daaa",
    "alias": "alice",
    "addresses": [],
    "tlvStream": {}
  },
  {
    "signature": "bb541000c8c12a02d7bd474ae82c1d858ce29db9952b7bf4d029b7ab2fd7304723cf3e4dbbc191c30b5869930713dc54974b2b6fc0b150a955bac75249c63038",
    "features": {
      "activated": {
        "option_simple_close": "optional",
        "option_route_blinding": "optional",
        "option_attribution_data": "optional",
        "option_dual_fund": "optional",
        "gossip_queries_ex": "optional",
        "option_quiesce": "optional",
        "option_data_loss_protect": "mandatory",
        "option_static_remotekey": "mandatory",
        "option_scid_alias": "optional",
        "option_onion_messages": "optional",
        "option_support_large_channel": "optional",
        "option_anchors_zero_fee_htlc_tx": "optional",
        "payment_secret": "mandatory",
        "option_shutdown_anysegwit": "optional",
        "option_channel_type": "mandatory",
        "basic_mpp": "optional",
        "gossip_queries": "optional",
        "option_simple_taproot": "optional",
        "var_onion_optin": "mandatory",
        "option_splice": "optional"
      }
    },
    "timestamp": {
      "iso": "2026-05-22T11:44:31Z",
      "unix": 1779450271
    },
    "nodeId": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257",
    "rgbColor": "#49daaa",
    "alias": "bob",
    "addresses": [],
    "tlvStream": {}
  }
]
```

Returns information about public nodes on the lightning network; this information is taken from the _node_announcement_ network message.

### HTTP Request

`POST http://localhost:8080/nodes`

### Parameters

Parameter         | Description                                       | Optional | Type
----------------- | ------------------------------------------------- | -------- | -----------------------------------------------
nodeIds           | The **nodeIds** of the nodes to return            | Yes      | CSV or JSON list of 33-bytes-HexString (String)
liquidityProvider | If true, only returns nodes selling liquidity ads | Yes      | Boolean

## Node

```shell
curl -s -u :<eclair_api_password> -X POST -F nodeId=<some_node> "http://localhost:8080/node"

# with eclair-cli
eclair-cli node --nodeId=<some_node>
```

> The above command returns:

```json
{
  "announcement": {
    "signature": "bb541000c8c12a02d7bd474ae82c1d858ce29db9952b7bf4d029b7ab2fd7304723cf3e4dbbc191c30b5869930713dc54974b2b6fc0b150a955bac75249c63038",
    "features": {
      "activated": {
        "option_simple_close": "optional",
        "option_route_blinding": "optional",
        "option_attribution_data": "optional",
        "option_dual_fund": "optional",
        "gossip_queries_ex": "optional",
        "option_quiesce": "optional",
        "option_data_loss_protect": "mandatory",
        "option_static_remotekey": "mandatory",
        "option_scid_alias": "optional",
        "option_onion_messages": "optional",
        "option_support_large_channel": "optional",
        "option_anchors_zero_fee_htlc_tx": "optional",
        "payment_secret": "mandatory",
        "option_shutdown_anysegwit": "optional",
        "option_channel_type": "mandatory",
        "basic_mpp": "optional",
        "gossip_queries": "optional",
        "option_simple_taproot": "optional",
        "var_onion_optin": "mandatory",
        "option_splice": "optional"
      }
    },
    "timestamp": {
      "iso": "2026-05-22T11:44:31Z",
      "unix": 1779450271
    },
    "nodeId": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257",
    "rgbColor": "#49daaa",
    "alias": "bob",
    "addresses": [],
    "tlvStream": {}
  },
  "activeChannels": 2,
  "totalCapacity": 569980
}
```

Returns information about a specific node on the lightning network, including its _node_announcement_ and some channel statistics.

### HTTP Request

`POST http://localhost:8080/node`

### Parameters

Parameter | Description                            | Optional | Type
--------- | -------------------------------------- | -------- | -----------------------------------------------
nodeId    | The **nodeId** of the requested node   | No       | 33-bytes-HexString (String)

## AllChannels

```shell
curl -s -u :<eclair_api_password> -X POST "http://localhost:8080/allchannels"

# with eclair-cli
eclair-cli allchannels
```

> The above command returns:

```json
[
  {
    "shortChannelId": "508856x657x0",
    "a": "0206c7b60457550f512d80ecdd9fb6eb798ce7e91bf6ec08ad9c53d72e94ef620d",
    "b": "02f6725f9c1c40333b67faea92fd211c183050f28df32cac3f9d69685fe9665432"
  },
  {
    "shortChannelId": "512733x303x0",
    "a": "024bd94f0425590434538fd21d4e58982f7e9cfd8f339205a73deb9c0e0341f5bd",
    "b": "02eae56f155bae8a8eaab82ddc6fef04d5a79a6b0b0d7bcdd0b60d52f3015af031"
  }
]
```

Returns non-detailed information about all public channels in the network.

## AllUpdates

```shell
curl -s -u :<eclair_api_password> -X POST "http://localhost:8080/allupdates"

# with eclair-cli
eclair-cli allupdates
```

> The above command returns:

```json
[
  {
    "signature": "15e49530f7b104ff62711d361ad226a327bba55d11cf2e832a3e626b0fcd2b6703477a12e2dae6e88779afc1e65af6006eb10755c9a3dc9f3703eeadb0528fb2",
    "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
    "shortChannelId": "68415x1x1",
    "timestamp": {
      "iso": "2026-05-22T11:56:14Z",
      "unix": 1779450974
    },
    "messageFlags": {
      "dontForward": false
    },
    "channelFlags": {
      "isEnabled": false,
      "isNode1": true
    },
    "cltvExpiryDelta": 144,
    "htlcMinimumMsat": 500,
    "feeBaseMsat": 15,
    "feeProportionalMillionths": 500,
    "htlcMaximumMsat": 75000000,
    "tlvStream": {}
  },
  {
    "signature": "96749c1e22a70dc8c435fc0ad176ea33d2b125529f2e2e1d6897c9ba47f686580486fab5ddc9a0c8fc1594fa9e6b4eec14004dac25218338a0818a6380ee1ee9",
    "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
    "shortChannelId": "68415x1x1",
    "timestamp": {
      "iso": "2026-05-22T11:56:14Z",
      "unix": 1779450974
    },
    "messageFlags": {
      "dontForward": false
    },
    "channelFlags": {
      "isEnabled": false,
      "isNode1": false
    },
    "cltvExpiryDelta": 144,
    "htlcMinimumMsat": 1000,
    "feeBaseMsat": 1000,
    "feeProportionalMillionths": 200,
    "htlcMaximumMsat": 75000000,
    "tlvStream": {}
  },
  {
    "signature": "5f55d169d1f29c74e7db67155fc3dbfee2368829d46dffa93a1d06a56239cc781f85cab4345402185dc4c64142da613a528ccd6286725e93383f824138dca4d5",
    "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
    "shortChannelId": "68435x2x1",
    "timestamp": {
      "iso": "2026-05-22T11:56:23Z",
      "unix": 1779450983
    },
    "messageFlags": {
      "dontForward": false
    },
    "channelFlags": {
      "isEnabled": true,
      "isNode1": true
    },
    "cltvExpiryDelta": 144,
    "htlcMinimumMsat": 500,
    "feeBaseMsat": 15,
    "feeProportionalMillionths": 500,
    "htlcMaximumMsat": 75000000,
    "tlvStream": {}
  },
  {
    "signature": "1a3343dc148b7ac2c87b3aaca333a063ce02a0eaad559fda5216ba89b5a065df7cc1df528bc2b4fe052740f20cebdf0b8b75f7b72604dc2adff7d6747442076a",
    "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
    "shortChannelId": "68435x2x1",
    "timestamp": {
      "iso": "2026-05-22T11:56:23Z",
      "unix": 1779450983
    },
    "messageFlags": {
      "dontForward": false
    },
    "channelFlags": {
      "isEnabled": true,
      "isNode1": false
    },
    "cltvExpiryDelta": 144,
    "htlcMinimumMsat": 1000,
    "feeBaseMsat": 1000,
    "feeProportionalMillionths": 200,
    "htlcMaximumMsat": 75000000,
    "tlvStream": {}
  },
  {
    "signature": "9659f77ea03458907c8b1c29ee36ce1c1f0ab616d9985b7cce7a5c12288673646d89356cc8a97bba656599094ae3a7780f701015309eec6adb3afe8322b932b8",
    "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
    "shortChannelId": "0x241b2817588036e",
    "timestamp": {
      "iso": "2026-05-22T11:51:57Z",
      "unix": 1779450717
    },
    "messageFlags": {
      "dontForward": true
    },
    "channelFlags": {
      "isEnabled": true,
      "isNode1": true
    },
    "cltvExpiryDelta": 144,
    "htlcMinimumMsat": 500,
    "feeBaseMsat": 15,
    "feeProportionalMillionths": 500,
    "htlcMaximumMsat": 70000000,
    "tlvStream": {}
  },
  {
    "signature": "b9c49ae715c6d9cff5a4508a8801609bb99fc1880b36ad415128e6e359ceaa9000690e8bec1dfcf67ab09936534f7a2b5ad99b080c059e9638ee13833d8b0001",
    "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
    "shortChannelId": "147890x8484232x878",
    "timestamp": {
      "iso": "2026-05-22T11:51:57Z",
      "unix": 1779450717
    },
    "messageFlags": {
      "dontForward": true
    },
    "channelFlags": {
      "isEnabled": true,
      "isNode1": false
    },
    "cltvExpiryDelta": 144,
    "htlcMinimumMsat": 1000,
    "feeBaseMsat": 1000,
    "feeProportionalMillionths": 100,
    "htlcMaximumMsat": 70000000,
    "tlvStream": {}
  }
]
```

`cltvExpiryDelta` is expressed as number of blocks.

Returns detailed information about all public channels in the network; the information is mostly taken from the _channel_update_ network messages.

<aside class="warning">
The allupdates API is CPU intensive for eclair and might slow down the application.
</aside>

### HTTP Request

`POST http://localhost:8080/allupdates`

### Parameters

Parameter | Description                                                     | Optional | Type
--------- | --------------------------------------------------------------- | -------- | ---------------------------
nodeId    | The **nodeId** of the node to be used as filter for the updates | Yes      | 33-bytes-HexString (String)

# Payments

Interfaces for sending and receiving payments through eclair.

## CreateInvoice

```shell
curl -s -u :<eclair_api_password> -X POST -F description=<some_description> \
     -F amountMsat=<some_amount> "http://localhost:8080/createinvoice"

# with eclair-cli
eclair-cli createinvoice --description=<some_description> --amountMsat=<some_amount>
```

The units of returned fields that are not obvious from their names:

field          | unit
---------------|--------
expiry         | seconds
amount         | msats

> The above command returns:

```json
{
  "prefix": "lnbcrt",
  "timestamp": 1779451267,
  "nodeId": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257",
  "serialized": "lnbcrt400u1p4pq3vrpp5phu0m9jh704v0sgluh47nd57c5gjd9kr9pwajtdfzxsy6kc7ddfqdpyf35kw6r5de5kueeqd9ejqgmjv43kkmr9wdessp5yw8w32p52c7q33jf3u67x7q7tfsfuttqld8qya8vcpex84kzsq9qmqz9gxqrrsscqp7lqq9q7sqqqqqqqqqqqqqqqqqqqsqyqqqysgq38j0sxzex0d7wudrvhmg7f8dpyteh4l7yq8ezj9mpy0sgz8zkgmrtrjpaq9a2cnj4s8xwtyhvh6mjs3uut9sen4h4y7mx6sy865m69gp0p80ll",
  "description": "Lightning is #reckless",
  "paymentHash": "0df8fd9657f3eac7c11fe5ebe9b69ec5112696c3285dd92da911a04d5b1e6b52",
  "paymentMetadata": "2a",
  "expiry": 3600,
  "minFinalCltvExpiry": 30,
  "amount": 40000000,
  "features": {
    "activated": {
      "option_attribution_data": "optional",
      "payment_secret": "mandatory",
      "basic_mpp": "optional",
      "option_payment_metadata": "optional",
      "var_onion_optin": "mandatory"
    }
  },
  "routingInfo": []
}
```

Create a **BOLT11** payment invoice.

### HTTP Request

`POST http://localhost:8080/createinvoice`

### Parameters

Parameter         | Description                                                | Optional | Type
----------------- | ---------------------------------------------------------- | -------- | ---------------------------
description       | A description for the invoice                              | Yes (*)  | String
descriptionHash   | Hash of the description for the invoice                    | Yes (*)  | 32-bytes-HexString (String)
amountMsat        | Amount in millisatoshi for this invoice                    | Yes      | Millisatoshi (Integer)
expireIn          | Number of seconds that the invoice will be valid           | Yes      | Seconds (Integer)
fallbackAddress   | An on-chain fallback address to receive the payment        | Yes      | Bitcoin address (String)
paymentPreimage   | A user defined input for the generation of the paymentHash | Yes      | 32-bytes-HexString (String)
privateChannelIds | List of private channels to include as routing hints       | Yes      | CSV or JSON list of channelId

(*): you must specify either description or descriptionHash, but not both.

## DeleteInvoice

```shell
curl -s -u :<eclair_api_password> -X POST -F paymentHash=<payment_hash> "http://localhost:8080/deleteinvoice"

# with eclair-cli
eclair-cli deleteinvoice --paymentHash=<payment_hash>
```

> The above command returns:

```shell
deleted invoice 6f0864735283ca95eaf9c50ef77893f55ee3dd11cb90710cbbfb73f018798a68
```

> If the invoice has already been paid, this command returns:

```shell
Cannot remove a received incoming payment
```

Delete an unpaid **BOLT11** payment invoice.

### HTTP Request

`POST http://localhost:8080/deleteinvoice`

### Parameters

Parameter       | Description                                                | Optional | Type
--------------- | ---------------------------------------------------------- | -------- | ---------------------------
paymentHash     | The payment hash of the invoice                            | No       | 32-bytes-HexString (String)

## ParseInvoice

```shell
curl -s -u :<eclair_api_password> -X POST -F invoice=<some_bolt11invoice> "http://localhost:8080/parseinvoice"

# with eclair-cli
eclair-cli parseinvoice --invoice=<some_bolt11invoice>
```

The units of returned fields that are not obvious from their names:

field          | unit
---------------|--------
expiry         | seconds
amount         | msats

> The above command returns:

```json
{
  "prefix": "lnbcrt",
  "timestamp": 1779451267,
  "nodeId": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257",
  "serialized": "lnbcrt400u1p4pq3vrpp5phu0m9jh704v0sgluh47nd57c5gjd9kr9pwajtdfzxsy6kc7ddfqdpyf35kw6r5de5kueeqd9ejqgmjv43kkmr9wdessp5yw8w32p52c7q33jf3u67x7q7tfsfuttqld8qya8vcpex84kzsq9qmqz9gxqrrsscqp7lqq9q7sqqqqqqqqqqqqqqqqqqqsqyqqqysgq38j0sxzex0d7wudrvhmg7f8dpyteh4l7yq8ezj9mpy0sgz8zkgmrtrjpaq9a2cnj4s8xwtyhvh6mjs3uut9sen4h4y7mx6sy865m69gp0p80ll",
  "description": "Lightning is #reckless",
  "paymentHash": "0df8fd9657f3eac7c11fe5ebe9b69ec5112696c3285dd92da911a04d5b1e6b52",
  "paymentMetadata": "2a",
  "expiry": 3600,
  "minFinalCltvExpiry": 30,
  "amount": 40000000,
  "features": {
    "activated": {
      "option_attribution_data": "optional",
      "payment_secret": "mandatory",
      "basic_mpp": "optional",
      "option_payment_metadata": "optional",
      "var_onion_optin": "mandatory"
    }
  },
  "routingInfo": []
}
```

Returns detailed information about the given invoice.

### HTTP Request

`POST http://localhost:8080/parseinvoice`

### Parameters

Parameter | Description                    | Optional | Type
--------- | ------------------------------ | -------- | ------
invoice   | The invoice you want to decode | No       | String

## PayInvoice

```shell
curl -s -u :<eclair_api_password> -X POST -F invoice=<some_invoice> "http://localhost:8080/payinvoice"

# with eclair-cli
eclair-cli payinvoice --invoice=<some_invoice>
```

> The above command returns:

```json
"e4227601-38b3-404e-9aa0-75a829e9bec0"
```

Pays a **BOLT11** invoice. In case of failure, the payment will be retried up to `maxAttempts` times.
The default number of attempts is read from the configuration.
The API works in a fire-and-forget fashion where the unique identifier for this payment attempt is immediately returned to the caller.
It's possible to add an extra `externalId` and this will be returned as part of the [payment data](#getsentinfo).

When `--blocking=true` is provided, the API will instead block until the payment completes.
It will then return full details about the payment (succeeded or failed).

### HTTP Request

`POST http://localhost:8080/payinvoice`

### Parameters

Parameter                 | Description                                                                                    | Optional | Type
------------------------- | ---------------------------------------------------------------------------------------------- | -------- | ----------------------
invoice                   | The invoice you want to pay                                                                    | No       | String
amountMsat                | Amount to pay if the invoice does not have one                                                 | Yes      | Millisatoshi (Integer)
maxAttempts               | Max number of retries                                                                          | Yes      | Integer
maxFeeFlatSat             | Fee threshold to be paid along the payment route                                               | Yes      | Satoshi (Integer)
maxFeePct                 | Max percentage to be paid in fees along the payment route (ignored if below `maxFeeFlatSat`)   | Yes      | Double (between 0 and 100)
externalId                | Extra payment identifier specified by the caller                                               | Yes      | String
pathFindingExperimentName | Name of the path-finding configuration that should be used                                     | Yes      | String
blocking                  | Block until the payment completes                                                              | Yes      | Boolean

## CreateOffer

```shell
curl -s -u :<eclair_api_password> -X POST -F amountMsat=<amount_msat> "http://localhost:8080/createoffer"

# with eclair-cli
eclair-cli createoffer --amountMsat=<amount_msat>
```

> The above command returns:

```json
{
  "amountMsat": 10000000,
  "description": "Donation",
  "issuer": "ACINQ",
  "nodeId": "03ad873be7d8e152f878af1e58f894920a220c291f19ef24d916c5702bc79018b8",
  "createdAt": {
    "iso": "2025-09-10T15:21:11.438Z",
    "unix": 1757517671
  },
  "disabled": false,
  "encoded": "lno1qgsqvgnwgcg35z6ee2h3yczraddm72xrfua9uve2rlrm9deu7xyfzrcgqwvfdqq2ppzx7mnpw35k7msjq4q5xj2w2ytzzqadsua70k8p2tu83tc7truffys2ygxzj8ceaujdj9k9wq4u0yqchq"
}
```

Create a **BOLT12** offer, allowing recurring payments.
This offer will be active until `disableoffer` is called.

All parameters are optional: when none are specified, this will create the smallest possible offer associated with your public `node_id`.

If you specify `blindedPathsFirstNodeId`, your public node id will not appear in the offer: you will instead be hidden behind a blinded path starting at the node that you have chosen.
You can configure the number and length of blinded paths used in `eclair.conf` in the `offers` section.

### HTTP Request

`POST http://localhost:8080/createoffer`

### Parameters

Parameter               | Description                                                                       | Optional | Type
----------------------- | --------------------------------------------------------------------------------- | -------- | ----------------------
amountMsat              | Amount that should be paid                                                        | Yes      | Millisatoshi (Integer)
issuer                  | Public information about the offer issuer (e.g. merchant website)                 | Yes      | String
description             | Offer description                                                                 | Yes      | String
expireInSeconds         | Duration (in seconds) after which this offer automatically expires                | Yes      | Integer
blindedPathsFirstNodeId | Introduction node that must be used for blinded paths to hide the node's identity | Yes      | 33-bytes-HexString (String)

## ListOffers

```shell
curl -s -u :<eclair_api_password> -X POST -F activeOnly=<active_only> "http://localhost:8080/listoffers"

# with eclair-cli
eclair-cli listoffers --activeOnly=false
```

> The above command returns:

```json
[
  {
    "amountMsat": 2000000,
    "description": "Plushy",
    "nodeId": "03ad873be7d8e152f878af1e58f894920a220c291f19ef24d916c5702bc79018b8",
    "createdAt": {
      "iso": "2025-09-10T15:22:08.785Z",
      "unix": 1757517728
    },
    "disabled": false,
    "encoded": "lno1qgsqvgnwgcg35z6ee2h3yczraddm72xrfua9uve2rlrm9deu7xyfzrcgqv0gfqq2qegxcatndpu3vggr4krnhe7cu9f0s790rev039yjpg3qc2glr8hjfkgkc4czh3usrzuq"
  },
  {
    "amountMsat": 10000000,
    "description": "Donation",
    "issuer": "ACINQ",
    "nodeId": "03ad873be7d8e152f878af1e58f894920a220c291f19ef24d916c5702bc79018b8",
    "createdAt": {
      "iso": "2025-09-10T15:21:11.438Z",
      "unix": 1757517671
    },
    "disabled": false,
    "encoded": "lno1qgsqvgnwgcg35z6ee2h3yczraddm72xrfua9uve2rlrm9deu7xyfzrcgqwvfdqq2ppzx7mnpw35k7msjq4q5xj2w2ytzzqadsua70k8p2tu83tc7truffys2ygxzj8ceaujdj9k9wq4u0yqchq"
  }
]
```

List **BOLT12** offers.

### HTTP Request

`POST http://localhost:8080/listoffers`

### Parameters

Parameter               | Description                                | Optional | Type
----------------------- | ------------------------------------------ | -------- | -------
activeOnly              | If false, also includes disabled offers    | Yes      | Boolean

## DisableOffer

```shell
curl -s -u :<eclair_api_password> -X POST -F offer=<lnoxxx> "http://localhost:8080/disableoffer"

# with eclair-cli
eclair-cli disableoffer --offer=lnoxxx
```

> The above command returns:

```json
{
  "ace52412d549ad5371f84b808716dda14c3604ddcf365a91ceac62217d0100af": true
}
```

Disable a **BOLT12** offer, which means that future payments to this offer will be rejected.

### HTTP Request

`POST http://localhost:8080/disableoffer`

### Parameters

Parameter          | Description                      | Optional | Type
------------------ | -------------------------------- | -------- | -------
offer              | Offer that should be disabled    | No       | String

## ParseOffer

```shell
curl -s -u :<eclair_api_password> -X POST -F offer=<lnoxxx> "http://localhost:8080/parseoffer"

# with eclair-cli
eclair-cli parseoffer --offer=lnoxxx
```

> The above command returns:

```json
{
  "amountMsat": 10000000,
  "description": "Donation",
  "issuer": "ACINQ",
  "nodeId": "03ad873be7d8e152f878af1e58f894920a220c291f19ef24d916c5702bc79018b8",
  "createdAt": {
    "iso": "2025-09-10T15:21:11.438Z",
    "unix": 1757517671
  },
  "disabled": false,
  "encoded": "lno1qgsqvgnwgcg35z6ee2h3yczraddm72xrfua9uve2rlrm9deu7xyfzrcgqwvfdqq2ppzx7mnpw35k7msjq4q5xj2w2ytzzqadsua70k8p2tu83tc7truffys2ygxzj8ceaujdj9k9wq4u0yqchq"
}
```

Display the contents of a **BOLT12** offer.

### HTTP Request

`POST http://localhost:8080/parseoffer`

### Parameters

Parameter          | Description                      | Optional | Type
------------------ | -------------------------------- | -------- | -------
offer              | Offer that should be parsed      | No       | String

## PayOffer

```shell
curl -s -u :<eclair_api_password> -X POST -F offer=<some_offer> amountMsat=<amount_msat> "http://localhost:8080/payoffer"

# with eclair-cli
eclair-cli payoffer --offer=<some_offer> --amountMsat=<amount_msat>
```

> The above command returns:

```json
"e4227601-38b3-404e-9aa0-75a829e9bec0"
```

Pays a **BOLT12** offer. In case of failure, the payment will be retried up to `maxAttempts` times.
The default number of attempts is read from the configuration.
The API works in a fire-and-forget fashion where the unique identifier for this payment attempt is immediately returned to the caller.
It's possible to add an extra `externalId` and this will be returned as part of the [payment data](#getsentinfo).

When `--blocking=true` is provided, the API will instead block until the payment completes.
It will then return full details about the payment (succeeded or failed).

### HTTP Request

`POST http://localhost:8080/payoffer`

### Parameters

Parameter                 | Description                                                                                    | Optional | Type
------------------------- | ---------------------------------------------------------------------------------------------- | -------- | ----------------------
offer                     | The Bolt12 offer you want to pay                                                               | No       | String
amountMsat                | Amount to pay                                                                                  | No       | Millisatoshi (Integer)
quantity                  | Number of items to pay for, if the offer supports it                                           | Yes      | Integer
connectDirectly           | If true, directly connect to the offer's introduction node to request an invoice               | Yes      | Boolean
maxAttempts               | Max number of retries                                                                          | Yes      | Integer
maxFeeFlatSat             | Fee threshold to be paid along the payment route                                               | Yes      | Satoshi (Integer)
maxFeePct                 | Max percentage to be paid in fees along the payment route (ignored if below `maxFeeFlatSat`)   | Yes      | Double (between 0 and 100)
externalId                | Extra payment identifier specified by the caller                                               | Yes      | String
pathFindingExperimentName | Name of the path-finding configuration that should be used                                     | Yes      | String
blocking                  | Block until the payment completes                                                              | Yes      | Boolean

## SendToNode

```shell
curl -s -u :<eclair_api_password> -X POST -F nodeId=<some_node> \
  -F amountMsat=<amount> -F paymentHash=<some_hash> "http://localhost:8080/sendtonode"

# with eclair-cli
eclair-cli sendtonode --nodeId=<some_node> --amountMsat=<amount> --paymentHash=<some_hash>
```

> The above command returns:

```json
"e4227601-38b3-404e-9aa0-75a829e9bec0"
```

Sends money to a node using `keysend` (spontaneous payment without a Bolt11 invoice) as specified in [blip 3](https://github.com/lightning/blips/blob/master/blip-0003.md).
In case of failure, the payment will be retried up to `maxAttempts` times.
The default number of attempts is read from the configuration.
The API works in a fire-and-forget fashion where the unique identifier for this payment attempt is immediately returned to the caller.
It's possible to add an extra `externalId` and this will be returned as part of the [payment data](#getsentinfo).

Note that this feature isn't specified in the BOLTs, so it may be removed or updated in the future.
If the recipient has given you an invoice, you should instead of the `payinvoice` API.

### HTTP Request

`POST http://localhost:8080/sendtonode`

### Parameters

Parameter                 | Description                                                                                    | Optional | Type
------------------------- | ---------------------------------------------------------------------------------------------- | -------- | ---------------------------
nodeId                    | The recipient of this payment                                                                  | No       | 33-bytes-HexString (String)
amountMsat                | Amount to pay                                                                                  | No       | Millisatoshi (Integer)
maxAttempts               | Max number of retries                                                                          | Yes      | Integer
maxFeeFlatSat             | Fee threshold to be paid along the payment route                                               | Yes      | Satoshi (Integer)
maxFeePct                 | Max percentage to be paid in fees along the payment route (ignored if below `maxFeeFlatSat`)   | Yes      | Double (between 0 and 100)
externalId                | Extra payment identifier specified by the caller                                               | Yes      | String
pathFindingExperimentName | Name of the path-finding configuration that should be used                                     | Yes      | String

## SendToRoute

```shell
curl -s -u :<eclair_api_password> -X POST -F nodeIds=node1,node2 \
  -F amountMsat=<amount> \
  -F paymentHash=<some_hash> \
  -F finalCltvExpiry=<some_value> \
  -F invoice=<some_invoice> \
  "http://localhost:8080/sendtoroute"

curl -s -u :<eclair_api_password> -X POST -F shortChannelIds=42x1x0,56x7x3 \
  -F amountMsat=<amount> \
  -F paymentHash=<some_hash> \
  -F finalCltvExpiry=<some_value> \
  -F invoice=<some_invoice> \
  "http://localhost:8080/sendtoroute"

# with eclair-cli
eclair-cli sendtoroute --nodeIds=node1,node2 --amountMsat=<amount> --paymentHash=<some_hash> --finalCltvExpiry=<some_value> --invoice=<some_invoice>
eclair-cli sendtoroute --shortChannelIds=42x1x0,56x7x3 --amountMsat=<amount> --paymentHash=<some_hash> --finalCltvExpiry=<some_value> --invoice=<some_invoice>
```

> The above command returns:

```json
{
  "paymentId": "15798966-5e95-4dce-84a0-825bd2f2a8d1",
  "parentId": "20b2a854-261a-4e9f-a4ca-59b381aee4bc"
}
```

Sends money to a node forcing the payment to go through the given route.
The API works in a fire-and-forget fashion where the unique identifier for this payment attempt is immediately returned to the caller.
The route parameter can either be a list of **nodeIds** that the payment will traverse or a list of shortChannelIds.
If **nodeIds** are specified, a suitable channel will be automatically selected for each hop (note that in that case, the specified nodes need to have public channels between them).

This route can either be a json-encoded array (same as [findroute](#findroute) output) or a comma-separated list.
It's possible to add an extra `externalId` and this will be returned as part of the [payment data](#getsentinfo).

This command may also be used to send multipart payments with your own splitting algorithm.
Go to the [wiki](https://github.com/ACINQ/eclair/wiki) for details on how to do that.

### HTTP Request

`POST http://localhost:8080/sendtoroute`

### Parameters

Parameter           | Description                                                         | Optional | Type
------------------- | ------------------------------------------------------------------- | -------- | ---------------------------
invoice             | The invoice you want to pay                                         | No       | String
nodeIds             | A list of **nodeIds** from source to destination of the payment     | Yes (*)  | List of nodeIds
shortChannelIds     | A list of shortChannelIds from source to destination of the payment | Yes (*)  | List of shortChannelIds
amountMsat          | Amount to pay                                                       | No       | Millisatoshi (Integer)
paymentHash         | The payment hash for this payment                                   | No       | 32-bytes-HexString (String)
finalCltvExpiry     | The total CLTV expiry value for this payment                        | No       | Integer
maxFeeMsat          |  Maximum fee allowed for this payment                               | Yes      | Millisatoshi (Integer)
recipientAmountMsat | Total amount that the recipient should receive (if using MPP)       | Yes      | Millisatoshi (Integer)
parentId            | Id of the whole payment (if using MPP)                              | Yes      | Java's UUID (String)
externalId          | Extra payment identifier specified by the caller                    | Yes      | String

(*): you must specify either nodeIds or shortChannelIds, but not both.

## GetSentInfo

```shell
curl -s -u :<eclair_api_password> -X POST -F paymentHash=<some_hash> "http://localhost:8080/getsentinfo"

# with eclair-cli
eclair-cli getsentinfo --paymentHash=<some_hash>
```

The units of returned fields that are not obvious from their names:

field           | unit
----------------|------
recipientAmount | msats
amount          | msats
feesPaid        | msats

Possible returned `status.type` values:

- pending
- failed
- sent

> The above command returns:

```json
[
  {
    "id": "d43622ca-e38d-43f3-98d1-eb91b4a4030e",
    "parentId": "89a91297-aa90-4d8c-b4a9-67cc7380c45f",
    "paymentHash": "2c90ca142c635e13b7ae59322e3ba5a397946d4a2cb66ae2cf8f3a1f3ea2c71c",
    "paymentType": "Standard",
    "amount": 20000000,
    "recipientAmount": 20000000,
    "recipientNodeId": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257",
    "createdAt": {
      "iso": "2026-05-22T12:06:19.887Z",
      "unix": 1779451579
    },
    "invoice": {
      "prefix": "lnbcrt",
      "timestamp": 1779451566,
      "nodeId": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257",
      "serialized": "lnbcrt200u1p4pq34wpp59jgv59pvvd0p8dawtyezuwa95wtegm229jmx4ck03uap704zcuwqdpyf35kw6r5de5kueeqd9ejqgmjv43kkmr9wdessp5l7e0gvhj2c0g03ppyxrlnhnh3rwufjtmr6vzkykxgq7tylmremjqmqz9gxqrrsscqp7lqq9q7sqqqqqqqqqqqqqqqqqqqsqyqqqysgqen5nzc7pf2hdnex3kpj3p8eh7yf5yalwm07qwax53zjwhf5m4q3yq9lkaplfkwg5xw2myytw0vptk3wclwdsy28ttu77tkarpl7lymqq38pqdf",
      "description": "Lightning is #reckless",
      "paymentHash": "2c90ca142c635e13b7ae59322e3ba5a397946d4a2cb66ae2cf8f3a1f3ea2c71c",
      "paymentMetadata": "2a",
      "expiry": 3600,
      "minFinalCltvExpiry": 30,
      "amount": 20000000,
      "features": {
        "activated": {
          "option_attribution_data": "optional",
          "payment_secret": "mandatory",
          "basic_mpp": "optional",
          "option_payment_metadata": "optional",
          "var_onion_optin": "mandatory"
        }
      },
      "routingInfo": []
    },
    "status": {
      "type": "sent",
      "paymentPreimage": "6a93c0a2b701ec0e3fc92b504d3dddf0431980beaedcda51b79007ef550340e0",
      "feesPaid": 0,
      "route": [
        {
          "nodeId": "023b19f5990b2eee1a3bfc66cb9fdfeba62f7e058f13faf236249cc8d53543f20b",
          "nextNodeId": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257",
          "shortChannelId": "147890x8484232x878"
        }
      ],
      "completedAt": {
        "iso": "2026-05-22T12:06:19.949Z",
        "unix": 1779451579
      }
    }
  }
]
```

Returns a list of attempts to send an outgoing payment.
The status field contains detailed information about the payment attempt.
If the attempt was unsuccessful the `status` field contains a non empty array of detailed failures descriptions.
The API can be queried by `paymentHash` OR by `uuid`.

Note that when you provide the `id` instead of the `payment_hash`, eclair will only return results for this particular attempt.
For multi-part payments, the `id` provided must be the `parentId`, not the `paymentId` of a partial payment.

### HTTP Request

`POST http://localhost:8080/getsentinfo`

### Parameters

Parameter   | Description                                                     | Optional | Type
----------- | --------------------------------------------------------------  | -------- | ---------------------------
paymentHash | The payment hash common to all payment attempts to be retrieved | No       | 32-bytes-HexString (String)
id          | The unique id of the payment attempt                            | Yes      | Java's UUID (String)

## GetReceivedInfo

```shell
curl -s -u :<eclair_api_password> -X POST -F paymentHash=<some_hash> "http://localhost:8080/getreceivedinfo"

# with eclair-cli
eclair-cli getreceivedinfo --paymentHash=<some_hash>
```

The units of returned fields that are not obvious from their names:

field    | unit
---------|--------
expiry   | seconds
amount   | msats

Possible returned `status.type` values:

- pending
- expired
- received

> The above command returns:

```json
{
  "invoice": {
    "prefix": "lnbcrt",
    "timestamp": 1779451566,
    "nodeId": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257",
    "serialized": "lnbcrt200u1p4pq34wpp59jgv59pvvd0p8dawtyezuwa95wtegm229jmx4ck03uap704zcuwqdpyf35kw6r5de5kueeqd9ejqgmjv43kkmr9wdessp5l7e0gvhj2c0g03ppyxrlnhnh3rwufjtmr6vzkykxgq7tylmremjqmqz9gxqrrsscqp7lqq9q7sqqqqqqqqqqqqqqqqqqqsqyqqqysgqen5nzc7pf2hdnex3kpj3p8eh7yf5yalwm07qwax53zjwhf5m4q3yq9lkaplfkwg5xw2myytw0vptk3wclwdsy28ttu77tkarpl7lymqq38pqdf",
    "description": "Lightning is #reckless",
    "paymentHash": "2c90ca142c635e13b7ae59322e3ba5a397946d4a2cb66ae2cf8f3a1f3ea2c71c",
    "paymentMetadata": "2a",
    "expiry": 3600,
    "minFinalCltvExpiry": 30,
    "amount": 20000000,
    "features": {
      "activated": {
        "option_attribution_data": "optional",
        "payment_secret": "mandatory",
        "basic_mpp": "optional",
        "option_payment_metadata": "optional",
        "var_onion_optin": "mandatory"
      }
    },
    "routingInfo": []
  },
  "paymentPreimage": "6a93c0a2b701ec0e3fc92b504d3dddf0431980beaedcda51b79007ef550340e0",
  "paymentType": "Standard",
  "createdAt": {
    "iso": "2026-05-22T12:06:06Z",
    "unix": 1779451566
  },
  "status": {
    "type": "received",
    "amount": 20000000,
    "receivedAt": {
      "iso": "2026-05-22T12:06:19.937Z",
      "unix": 1779451579
    }
  }
}
```

Checks whether a payment corresponding to the given `paymentHash` has been received.
It is possible to use a **BOLT11** invoice as parameter instead of the `paymentHash` but at least one of the two must be specified.

### HTTP Request

`POST http://localhost:8080/getreceivedinfo`

### Parameters

Parameter   | Description                             | Optional | Type
----------- | --------------------------------------- | -------- | ---------------------------
paymentHash | The payment hash you want to check      | Yes (*)  | 32-bytes-HexString (String)
invoice     | The invoice containing the payment hash | Yes (*)  | String

(*): you must specify either paymentHash or invoice.

## ListReceivedPayments

```shell
curl -s -u :<eclair_api_password> -X POST "http://localhost:8080/listreceivedpayments"

# with eclair-cli
eclair-cli listreceivedpayments
```

> The above command returns:

```json
[
  {
    "invoice": {
      "prefix": "lnbcrt",
      "timestamp": 1779450738,
      "nodeId": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257",
      "serialized": "lnbcrt400u1p4pqsmjpp565f0zksd6t0wuwj7vql3sl9z5q96jg6m0528kskzaqtmsls905esdpyf35kw6r5de5kueeqd9ejqgmjv43kkmr9wdessp5jwcg7g7z9f6cf5x8zfmssnu3yclf8kqmmm9daggt26t3treah8vqmqz9gxqrrsscqp7lqq9q7sqqqqqqqqqqqqqqqqqqqsqyqqqysgqzqga2am45s3gkrva9plkqh2lstjaps7dkxn5wvapmskgm2qu8mt5aysd0pdp6t6tpcjssh6926259tjfsqnjdhd7nrsdpg7ja06eu7gqzvgr8l",
      "description": "Lightning is #reckless",
      "paymentHash": "d512f15a0dd2deee3a5e603f187ca2a00ba9235b7d147b42c2e817b87e057d33",
      "paymentMetadata": "2a",
      "expiry": 3600,
      "minFinalCltvExpiry": 30,
      "amount": 40000000,
      "features": {
        "activated": {
          "option_attribution_data": "optional",
          "payment_secret": "mandatory",
          "basic_mpp": "optional",
          "option_payment_metadata": "optional",
          "var_onion_optin": "mandatory"
        }
      },
      "routingInfo": []
    },
    "paymentPreimage": "005b19580bc9763f502ff463a6c4297a7d87cb1e7a970df56b19bee82587aafc",
    "paymentType": "Standard",
    "createdAt": {
      "iso": "2026-05-22T11:52:18Z",
      "unix": 1779450738
    },
    "status": {
      "type": "received",
      "amount": 40000000,
      "receivedAt": {
        "iso": "2026-05-22T11:52:28.954Z",
        "unix": 1779450748
      }
    }
  },
  {
    "invoice": {
      "records": {
        "OfferChains": {
          "chains": [
            "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f"
          ]
        },
        "InvoiceRequestChain": {
          "hash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f"
        },
        "InvoiceNodeId": {
          "nodeId": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257"
        },
        "OfferNodeId": {
          "publicKey": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257"
        },
        "InvoiceRequestPayerId": {
          "publicKey": "0277be64abfd950a648a1eadca5403e14f1e2e81818cd34b1dc0037a75673f466a"
        },
        "InvoiceCreatedAt": {
          "timestamp": {
            "iso": "2026-05-22T12:03:45Z",
            "unix": 1779451425
          }
        },
        "InvoiceRequestQuantity": {
          "quantity": 1
        },
        "InvoicePaymentHash": {
          "hash": "f456821221cf9df9992b76f4ea2fcdd33377dd98be96e2e75f4080dd3b72137f"
        },
        "InvoiceAmount": {
          "amount": 15000000
        }
      }
    },
    "paymentPreimage": "3f1e2ec8a23c99a090d9a7f4a1f8b297b8357bb4eb153a503b0e0fd52195f3e6",
    "paymentType": "Blinded",
    "createdAt": {
      "iso": "2026-05-22T12:03:45Z",
      "unix": 1779451425
    },
    "status": {
      "type": "received",
      "amount": 15000000,
      "receivedAt": {
        "iso": "2026-05-22T12:03:45.886Z",
        "unix": 1779451425
      }
    }
  },
  {
    "invoice": {
      "prefix": "lnbcrt",
      "timestamp": 1779451566,
      "nodeId": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257",
      "serialized": "lnbcrt200u1p4pq34wpp59jgv59pvvd0p8dawtyezuwa95wtegm229jmx4ck03uap704zcuwqdpyf35kw6r5de5kueeqd9ejqgmjv43kkmr9wdessp5l7e0gvhj2c0g03ppyxrlnhnh3rwufjtmr6vzkykxgq7tylmremjqmqz9gxqrrsscqp7lqq9q7sqqqqqqqqqqqqqqqqqqqsqyqqqysgqen5nzc7pf2hdnex3kpj3p8eh7yf5yalwm07qwax53zjwhf5m4q3yq9lkaplfkwg5xw2myytw0vptk3wclwdsy28ttu77tkarpl7lymqq38pqdf",
      "description": "Lightning is #reckless",
      "paymentHash": "2c90ca142c635e13b7ae59322e3ba5a397946d4a2cb66ae2cf8f3a1f3ea2c71c",
      "paymentMetadata": "2a",
      "expiry": 3600,
      "minFinalCltvExpiry": 30,
      "amount": 20000000,
      "features": {
        "activated": {
          "option_attribution_data": "optional",
          "payment_secret": "mandatory",
          "basic_mpp": "optional",
          "option_payment_metadata": "optional",
          "var_onion_optin": "mandatory"
        }
      },
      "routingInfo": []
    },
    "paymentPreimage": "6a93c0a2b701ec0e3fc92b504d3dddf0431980beaedcda51b79007ef550340e0",
    "paymentType": "Standard",
    "createdAt": {
      "iso": "2026-05-22T12:06:06Z",
      "unix": 1779451566
    },
    "status": {
      "type": "received",
      "amount": 20000000,
      "receivedAt": {
        "iso": "2026-05-22T12:06:19.937Z",
        "unix": 1779451579
      }
    }
  }
]
```

Returns the list of payments received by your node.

### HTTP Request

`POST http://localhost:8080/listreceivedpayments`

### Parameters

Parameter | Description                                           | Optional | Type
--------- | ----------------------------------------------------- | -------- | ---------------------------
from      | Filters elements no older than this unix-timestamp    | Yes      | Unix timestamp in seconds (Integer)
to        | Filters elements no younger than this unix-timestamp  | Yes      | Unix timestamp in seconds (Integer)
count     | Limits the number of results returned                 | Yes      | Integer
skip      | Skip some number of results                           | Yes      | Integer

## GetInvoice

```shell
curl -s -u :<eclair_api_password> -X POST -F paymentHash=<some_hash> "http://localhost:8080/getinvoice"

# with eclair-cli
eclair-cli getinvoice --paymentHash=<some_hash>
```

The units of returned fields that are not obvious from their names:

field    | unit
---------|--------
expiry   | seconds
amount   | msats

> The above command returns:

```json
{
  "prefix": "lnbcrt",
  "timestamp": 1779451267,
  "nodeId": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257",
  "serialized": "lnbcrt400u1p4pq3vrpp5phu0m9jh704v0sgluh47nd57c5gjd9kr9pwajtdfzxsy6kc7ddfqdpyf35kw6r5de5kueeqd9ejqgmjv43kkmr9wdessp5yw8w32p52c7q33jf3u67x7q7tfsfuttqld8qya8vcpex84kzsq9qmqz9gxqrrsscqp7lqq9q7sqqqqqqqqqqqqqqqqqqqsqyqqqysgq38j0sxzex0d7wudrvhmg7f8dpyteh4l7yq8ezj9mpy0sgz8zkgmrtrjpaq9a2cnj4s8xwtyhvh6mjs3uut9sen4h4y7mx6sy865m69gp0p80ll",
  "description": "Lightning is #reckless",
  "paymentHash": "0df8fd9657f3eac7c11fe5ebe9b69ec5112696c3285dd92da911a04d5b1e6b52",
  "paymentMetadata": "2a",
  "expiry": 3600,
  "minFinalCltvExpiry": 30,
  "amount": 40000000,
  "features": {
    "activated": {
      "option_attribution_data": "optional",
      "payment_secret": "mandatory",
      "basic_mpp": "optional",
      "option_payment_metadata": "optional",
      "var_onion_optin": "mandatory"
    }
  },
  "routingInfo": []
}
```

Queries the payment DB for a stored invoice with the given `paymentHash`. If none is found, it responds HTTP 404.

### HTTP Request

`POST http://localhost:8080/getinvoice`

### Parameters

Parameter   | Description                                          | Optional | Type
----------- | ---------------------------------------------------- | -------- | ---------------------------
paymentHash | The payment hash of the invoice you want to retrieve | No       | 32-bytes-HexString (String)

## ListInvoices

```shell
curl -s -u :<eclair_api_password> -X POST "http://localhost:8080/listinvoices"

# with eclair-cli
eclair-cli listinvoices
```

The units of returned fields that are not obvious from their names:

field    | unit
---------|-----
expiry   | seconds
amount   | msats

> The above command returns:

```json
[
  {
    "prefix": "lnbcrt",
    "timestamp": 1779450738,
    "nodeId": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257",
    "serialized": "lnbcrt400u1p4pqsmjpp565f0zksd6t0wuwj7vql3sl9z5q96jg6m0528kskzaqtmsls905esdpyf35kw6r5de5kueeqd9ejqgmjv43kkmr9wdessp5jwcg7g7z9f6cf5x8zfmssnu3yclf8kqmmm9daggt26t3treah8vqmqz9gxqrrsscqp7lqq9q7sqqqqqqqqqqqqqqqqqqqsqyqqqysgqzqga2am45s3gkrva9plkqh2lstjaps7dkxn5wvapmskgm2qu8mt5aysd0pdp6t6tpcjssh6926259tjfsqnjdhd7nrsdpg7ja06eu7gqzvgr8l",
    "description": "Lightning is #reckless",
    "paymentHash": "d512f15a0dd2deee3a5e603f187ca2a00ba9235b7d147b42c2e817b87e057d33",
    "paymentMetadata": "2a",
    "expiry": 3600,
    "minFinalCltvExpiry": 30,
    "amount": 40000000,
    "features": {
      "activated": {
        "option_attribution_data": "optional",
        "payment_secret": "mandatory",
        "basic_mpp": "optional",
        "option_payment_metadata": "optional",
        "var_onion_optin": "mandatory"
      }
    },
    "routingInfo": []
  },
  {
    "prefix": "lnbcrt",
    "timestamp": 1779451267,
    "nodeId": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257",
    "serialized": "lnbcrt400u1p4pq3vrpp5phu0m9jh704v0sgluh47nd57c5gjd9kr9pwajtdfzxsy6kc7ddfqdpyf35kw6r5de5kueeqd9ejqgmjv43kkmr9wdessp5yw8w32p52c7q33jf3u67x7q7tfsfuttqld8qya8vcpex84kzsq9qmqz9gxqrrsscqp7lqq9q7sqqqqqqqqqqqqqqqqqqqsqyqqqysgq38j0sxzex0d7wudrvhmg7f8dpyteh4l7yq8ezj9mpy0sgz8zkgmrtrjpaq9a2cnj4s8xwtyhvh6mjs3uut9sen4h4y7mx6sy865m69gp0p80ll",
    "description": "Lightning is #reckless",
    "paymentHash": "0df8fd9657f3eac7c11fe5ebe9b69ec5112696c3285dd92da911a04d5b1e6b52",
    "paymentMetadata": "2a",
    "expiry": 3600,
    "minFinalCltvExpiry": 30,
    "amount": 40000000,
    "features": {
      "activated": {
        "option_attribution_data": "optional",
        "payment_secret": "mandatory",
        "basic_mpp": "optional",
        "option_payment_metadata": "optional",
        "var_onion_optin": "mandatory"
      }
    },
    "routingInfo": []
  },
  {
    "records": {
      "OfferChains": {
        "chains": [
          "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f"
        ]
      },
      "InvoiceRequestChain": {
        "hash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f"
      },
      "InvoiceNodeId": {
        "nodeId": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257"
      },
      "OfferNodeId": {
        "publicKey": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257"
      },
      "InvoiceRequestPayerId": {
        "publicKey": "0277be64abfd950a648a1eadca5403e14f1e2e81818cd34b1dc0037a75673f466a"
      },
      "InvoiceCreatedAt": {
        "timestamp": {
          "iso": "2026-05-22T12:03:45Z",
          "unix": 1779451425
        }
      },
      "InvoiceRequestQuantity": {
        "quantity": 1
      },
      "InvoicePaymentHash": {
        "hash": "f456821221cf9df9992b76f4ea2fcdd33377dd98be96e2e75f4080dd3b72137f"
      },
      "InvoiceAmount": {
        "amount": 15000000
      }
    }
  },
  {
    "prefix": "lnbcrt",
    "timestamp": 1779451566,
    "nodeId": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257",
    "serialized": "lnbcrt200u1p4pq34wpp59jgv59pvvd0p8dawtyezuwa95wtegm229jmx4ck03uap704zcuwqdpyf35kw6r5de5kueeqd9ejqgmjv43kkmr9wdessp5l7e0gvhj2c0g03ppyxrlnhnh3rwufjtmr6vzkykxgq7tylmremjqmqz9gxqrrsscqp7lqq9q7sqqqqqqqqqqqqqqqqqqqsqyqqqysgqen5nzc7pf2hdnex3kpj3p8eh7yf5yalwm07qwax53zjwhf5m4q3yq9lkaplfkwg5xw2myytw0vptk3wclwdsy28ttu77tkarpl7lymqq38pqdf",
    "description": "Lightning is #reckless",
    "paymentHash": "2c90ca142c635e13b7ae59322e3ba5a397946d4a2cb66ae2cf8f3a1f3ea2c71c",
    "paymentMetadata": "2a",
    "expiry": 3600,
    "minFinalCltvExpiry": 30,
    "amount": 20000000,
    "features": {
      "activated": {
        "option_attribution_data": "optional",
        "payment_secret": "mandatory",
        "basic_mpp": "optional",
        "option_payment_metadata": "optional",
        "var_onion_optin": "mandatory"
      }
    },
    "routingInfo": []
  }
]
```

Returns all the **BOLT11** invoices stored.

### HTTP Request

`POST http://localhost:8080/listinvoices`

### Parameters

Parameter | Description                                           | Optional | Type
--------- | ----------------------------------------------------- | -------- | -----------------------------------
from      | Filters elements no older than this unix-timestamp    | Yes      | Unix timestamp in seconds (Integer)
to        | Filters elements no younger than this unix-timestamp  | Yes      | Unix timestamp in seconds (Integer)
count     | Limits the number of results returned                 | Yes      | Integer
skip      | Skip some number of results                           | Yes      | Integer

## ListPendingInvoices

```shell
curl -s -u :<eclair_api_password> -X POST "http://localhost:8080/listpendinginvoices"

# with eclair-cli
eclair-cli listpendinginvoices
```

The units of returned fields that are not obvious from their names:

field    | unit
---------|--------
expiry   | seconds
amount   | msats

> The above command returns:

```json
[
  {
    "prefix": "lnbcrt",
    "timestamp": 1779451267,
    "nodeId": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257",
    "serialized": "lnbcrt400u1p4pq3vrpp5phu0m9jh704v0sgluh47nd57c5gjd9kr9pwajtdfzxsy6kc7ddfqdpyf35kw6r5de5kueeqd9ejqgmjv43kkmr9wdessp5yw8w32p52c7q33jf3u67x7q7tfsfuttqld8qya8vcpex84kzsq9qmqz9gxqrrsscqp7lqq9q7sqqqqqqqqqqqqqqqqqqqsqyqqqysgq38j0sxzex0d7wudrvhmg7f8dpyteh4l7yq8ezj9mpy0sgz8zkgmrtrjpaq9a2cnj4s8xwtyhvh6mjs3uut9sen4h4y7mx6sy865m69gp0p80ll",
    "description": "Lightning is #reckless",
    "paymentHash": "0df8fd9657f3eac7c11fe5ebe9b69ec5112696c3285dd92da911a04d5b1e6b52",
    "paymentMetadata": "2a",
    "expiry": 3600,
    "minFinalCltvExpiry": 30,
    "amount": 40000000,
    "features": {
      "activated": {
        "option_attribution_data": "optional",
        "payment_secret": "mandatory",
        "basic_mpp": "optional",
        "option_payment_metadata": "optional",
        "var_onion_optin": "mandatory"
      }
    },
    "routingInfo": []
  }
]
```

Returns all non-paid, non-expired **BOLT11** invoices stored.
The invoices can be filtered by date and are output in descending order.

### HTTP Request

`POST http://localhost:8080/listpendinginvoices`

### Parameters

Parameter | Description                                           | Optional | Type
--------- | ----------------------------------------------------- | -------- | -----------------------------------
from      | Filters elements no older than this unix-timestamp    | Yes      | Unix timestamp in seconds (Integer)
to        | Filters elements no younger than this unix-timestamp  | Yes      | Unix timestamp in seconds (Integer)
count     | Limits the number of results returned                 | Yes      | Integer
skip      | Skip some number of results                           | Yes      | Integer

# Route

## FindRoute

```shell
curl -s -u :<eclair_api_password> -X POST -F invoice=<some_bolt11invoice> "http://localhost:8080/findroute"

# with eclair-cli
eclair-cli findroute --invoice=<some_bolt11invoice>
```

> The above command returns:

```json
{
  "routes": [
    {
      "amount": 5000,
      "nodeIds": [
        "036d65409c41ab7380a43448f257809e7496b52bf92057c09c4f300cbd61c50d96",
        "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
        "03d06758583bb5154774a6eb221b1276c9e82d65bbaceca806d90e20c108f4b1c7"
      ]
    }
  ]
}
```

Finds a route to the node specified by the invoice.
If the invoice does not specify an amount, you must do so via the `amountMsat` parameter.

You can specify various formats for the route returned with the `format` parameter.
When using `format=shortChannelId`, the above command would return:

```json
{
  "routes": [
    {
      "amount": 5000,
      "shortChannelIds": [
        "11203x1x0",
        "11203x7x5",
        "11205x3x3"
      ]
    }
  ]
}
```

When using `format=full`, the above command would return the last `channel_update` for each hop:

```json
{
  "routes": [
    {
      "amount": 5000,
      "hops": [
        {
          "nodeId": "02fe677ac8cd61399d097535a3e8a51a0849e57cdbab9b34796c86f3e33568cbe2",
          "nextNodeId": "028e2403fbfddb3d787843361f91adbda64c6f622921b19fb48f5766508bcadb29",
          "lastUpdate": {
            "signature": "dcc9daf6610ccae90470b6ac2d4d3ed65bc01e23c7b71e78654971dea099e58436eaa29e0c3971e53acd3225a837f69a33b39cc07065ca73150b41c3543eb07f",
            "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
            "shortChannelId": "2899x1x1",
            "timestamp": {
              "iso": "2022-02-01T12:40:19Z",
              "unix": 1643719219
            },
            "messageFlags": {
              "dontForward": false
            },
            "channelFlags": {
              "isEnabled": true,
              "isNode1": false
            },
            "cltvExpiryDelta": 48,
            "htlcMinimumMsat": 1,
            "feeBaseMsat": 1000,
            "feeProportionalMillionths": 200,
            "htlcMaximumMsat": 450000000,
            "tlvStream": {}
          }
        }
      ]
    }
  ]
}

```

The formats currently supported are `nodeId`, `shortChannelId` and `full`.

### HTTP Request

`POST http://localhost:8080/findroute`

### Parameters

Parameter                 | Description                                                | Optional | Type
------------------------- | ---------------------------------------------------------- | -------- | ----------------------
invoice                   | The invoice containing the destination                     | No       | String
amountMsat                | The amount that should go through the route                | Yes      | Millisatoshi (Integer)
ignoreNodeIds             | A list of nodes to exclude from path-finding               | Yes      | List of nodeIds
ignoreShortChannelIds     | A list of channels to exclude from path-finding            | Yes      | List of shortChannelIds
format                    | Format that will be used for the resulting route           | Yes      | String
maxFeeMsat                | Maximum fee allowed for this payment                       | Yes      | Millisatoshi (Integer)
maxCltvExpiryDelta        | Maximum cltv_expiry_delta of the complete route            | Yes      | Integer
includeLocalChannelCost   | If true, the relay fees of local channels will be counted  | Yes      | Boolean
pathFindingExperimentName | Name of the path-finding configuration that should be used | Yes      | String

## FindRouteToNode

```shell
curl -s -u :<eclair_api_password> -X POST -F nodeId=<some_node> \
     -F amountMsat=<some_amount> "http://localhost:8080/findroutetonode"

# with eclair-cli
eclair-cli --nodeId=<some_node> --amountMsat=<some_amount>
```

> The above command returns:

```json
{
  "routes": [
    {
      "amount": 5000,
      "nodeIds": [
        "036d65409c41ab7380a43448f257809e7496b52bf92057c09c4f300cbd61c50d96",
        "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
        "03d06758583bb5154774a6eb221b1276c9e82d65bbaceca806d90e20c108f4b1c7"
      ]
    }
  ]
}
```

Finds a route to the given node.

### HTTP Request

`POST http://localhost:8080/findroutetonode`

### Parameters

Parameter                 | Description                                                | Optional | Type
------------------------- | ---------------------------------------------------------- | -------- | ---------------------------
nodeId                    | The destination of the route                               | No       | 33-bytes-HexString (String)
amountMsat                | The amount that should go through the route                | No       | Millisatoshi (Integer)
ignoreNodeIds             | A list of nodes to exclude from path-finding               | Yes      | List of nodeIds
ignoreShortChannelIds     | A list of channels to exclude from path-finding            | Yes      | List of shortChannelIds
format                    | Format that will be used for the resulting route           | Yes      | String
maxFeeMsat                | Maximum fee allowed for this payment                       | Yes      | Millisatoshi (Integer)
maxCltvExpiryDelta        | Maximum cltv_expiry_delta of the complete route            | Yes      | Integer
includeLocalChannelCost   | If true, the relay fees of local channels will be counted  | Yes      | Boolean
pathFindingExperimentName | Name of the path-finding configuration that should be used | Yes      | String

## FindRouteBetweenNodes

```shell
curl -s -u :<eclair_api_password> -X POST -F sourceNodeId=<some_node> -F targetNodeId=<some_node> \
     -F amountMsat=<some_amount> "http://localhost:8080/findroutebetweennodes"

# with eclair-cli
eclair-cli --sourceNodeId=<some_node> --targetNodeId=<some_node> --amountMsat=<some_amount>
```

> The above command returns:

```json
{
  "routes": [
    {
      "amount": 5000,
      "nodeIds": [
        "036d65409c41ab7380a43448f257809e7496b52bf92057c09c4f300cbd61c50d96",
        "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
        "03d06758583bb5154774a6eb221b1276c9e82d65bbaceca806d90e20c108f4b1c7"
      ]
    }
  ]
}
```

Finds a route between two nodes.

### HTTP Request

`POST http://localhost:8080/findroutebetweennodes`

### Parameters

Parameter                 | Description                                                | Optional | Type
------------------------- | ---------------------------------------------------------- | -------- | ---------------------------
sourceNodeId              | The start of the route                                     | No       | 33-bytes-HexString (String)
targetNodeId              | The destination of the route                               | No       | 33-bytes-HexString (String)
amountMsat                | The amount that should go through the route                | No       | Millisatoshi (Integer)
ignoreNodeIds             | A list of nodes to exclude from path-finding               | Yes      | List of nodeIds
ignoreShortChannelIds     | A list of channels to exclude from path-finding            | Yes      | List of shortChannelIds
format                    | Format that will be used for the resulting route           | Yes      | String
maxFeeMsat                | Maximum fee allowed for this payment                       | Yes      | Millisatoshi (Integer)
maxCltvExpiryDelta        | Maximum cltv_expiry_delta of the complete route            | Yes      | Integer
includeLocalChannelCost   | If true, the relay fees of local channels will be counted  | Yes      | Boolean
pathFindingExperimentName | Name of the path-finding configuration that should be used | Yes      | String

# On-Chain

## GetNewAddress

```shell
curl -s -u :<eclair_api_password> -X POST "http://localhost:8080/getnewaddress"

# with eclair-cli
eclair-cli getnewaddress
```

> The above command returns:

```shell
bcrt1qaq9azfugal9usaffv3cj89gpeq36xst9ms53xl
```

Get a new on-chain address from the wallet. This can be used to deposit funds that will later be used
to fund channels. The API is only available with the bitcoin-core watcher type, and the resulting addresses
depend on the configured address-type in `bitcoin.conf`.

### HTTP Request

`POST http://localhost:8080/getnewaddress`

## SendOnChain

```shell
curl -s -u :<eclair_api_password> -X POST -F address=<bitcoin_address> \
     -F amountSatoshis=<amount> -F confirmationTarget=<number_of_blocks> "http://localhost:8080/sendonchain"

# with eclair-cli
eclair-cli sendonchain --address=2NEDjKwa56LFcFVjPefuwkN3pyABkMrqpJn --amountSatoshis=25000 --confirmationTarget=6
```

> The above command returns:

```json
"d19c45509b2e39c92f2f84a6e07fab95509f5c1959e98f3085c66dc148582751"
```

Send an on-chain transaction to the given address. The API is only available with the bitcoin-core watcher type.
The API returns the txid of the bitcoin transaction sent.

### HTTP Request

`POST http://localhost:8080/sendonchain`

### Parameters

Parameter          | Description                          | Optional | Type
------------------ | ------------------------------------ | -------- | ------------------------
address            | The bitcoin address of the recipient | No       | Bitcoin address (String)
amountSatoshis     | The amount that should be sent       | No       | Satoshi (Integer)
confirmationTarget | The confirmation target (blocks)     | Yes (*)  | Satoshi (Integer)
feeRatePerByte     | The feerate in sat/byte              | Yes (*)  | Satoshi (Integer)

(*) You must provide either `confirmationTarget` or `feeRatePerByte`.

## OnChainBalance

```shell
curl -s -u :<eclair_api_password> -X POST "http://localhost:8080/onchainbalance"

# with eclair-cli
eclair-cli onchainbalance
```

> The above command returns:

```json
{
  "confirmed": 1304986456540,
  "unconfirmed": 0
}
```

Retrieves information about the available on-chain bitcoin balance (amounts are in satoshis).
Unconfirmed balance refers to incoming transactions seen in the mempool.

## OnChainTransactions

```shell
curl -s -u :<eclair_api_password> -X -F count=<number_of_results> -F skip=<skipped_results> POST "http://localhost:8080/onchaintransactions"

# with eclair-cli
eclair-cli onchaintransactions --count=2 --skip=1
```

The units of returned fields that are not obvious from their names:

field    | unit
---------|-----
amount   | sats
fees     | sats

> The above command returns:

```json
[
  {
    "address": "2NEDjKwa56LFcFVjPefuwkN3pyABkMrqpJn",
    "amount": 25000,
    "fees": 0,
    "blockHash": "0000000000000000000000000000000000000000000000000000000000000000",
    "confirmations": 0,
    "txid": "d19c45509b2e39c92f2f84a6e07fab95509f5c1959e98f3085c66dc148582751",
    "timestamp": 1593700112
  },
  {
    "address": "2NEDjKwa56LFcFVjPefuwkN3pyABkMrqpJn",
    "amount": 625000000,
    "fees": 0,
    "blockHash": "3f66e75bb70c1bc28edda9456fcf96ac68f10053020bee39f4cd45c240a1f05d",
    "confirmations": 1,
    "txid": "467e0f4c1fed9db56760e7bdcedb335c6b649fdaa82f51da80481a1101a98329",
    "timestamp": 1593698170
  }
]
```

Retrieves information about the latest on-chain transactions made by our Bitcoin wallet (most recent transactions first).

### HTTP Request

`POST http://localhost:8080/onchaintransactions`

### Parameters

Parameter | Description                      | Optional | Type
--------- | -------------------------------- | -------- | -------
count     | Number of transactions to return | Yes      | Integer
skip      | Number of transactions to skip   | Yes      | Integer

# Messages

## SendOnionMessage

```shell
curl -s -u :<eclair_api_password> -X POST -F content=2b03ffffff -F recipientNode=<node_id> "http://localhost:8080/sendonionmessage"

# with eclair-cli
eclair-cli sendonionmessage --content=2b03ffffff --recipientNode=<node_id>
```

> When sending without a reply path, this command will return:

```json
{
  "sent": true
}
```

> If the message cannot be sent, this command will return:

```json
{
  "sent": false,
  "failureMessage": "<details_about_the_failure>"
}
```

> When sending with a reply path, this command will return the response we received (encoded inside application-specific tlv fields):

```json
{
  "sent": true,
  "response": {
    "unknownTlvs": {
      "211": "deadbeef"
    }
  }
}
```

> If we don't receive a response, this command will return an error after a timeout:

```json
{
  "sent": true,
  "failureMessage": "No response"
}
```

Send an onion message to a remote recipient.

There are two ways to specify that recipient:

- when you're sending to a known `nodeId`, you must set it in the `--recipientNode` field
- when you're sending to an unknown node behind a blinded route, you must provide the blinded route in the `--recipientBlindedRoute` field

If you're not connected to the recipient and don't have channels with them, eclair will try connecting to them based on the best address it knows (usually from their `node_announcement`).
If that fails, or if you don't want to expose your `nodeId` by directly connecting to the recipient, you should find a route to them and specify the nodes in that route in the `--intermediateNodes` field.

You can send arbitrary data to the recipient, by providing a hex-encoded tlv stream in the `--content` field.

If you expect a response, you should provide a route from the recipient back to you in the `--replyPath` field.
Eclair will automatically create a corresponding blinded route to ensure that the recipient doesn't learn your `nodeId`.
The API will then wait for a response (or timeout if it doesn't receive a response).

### HTTP Request

`POST http://localhost:8080/sendonionmessage`

### Parameters

Parameter             | Description                                                | Optional | Type
--------------------- | ---------------------------------------------------------- | -------- | -----------------------------------------------
content               | Message sent to the recipient (encoded as a tlv stream)    | No       | HexString (String)
expectsReply          | Whether a response to that message is expected             | No       | Boolean
recipientNode         | NodeId of the recipient, if known.                         | Yes (*)  | 33-bytes-HexString (String)
recipientBlindedRoute | Blinded route provided by the recipient (encoded as a tlv) | Yes (*)  | HexString (String)
intermediateNodes     | Intermediates nodes to insert before the recipient         | Yes      | CSV or JSON list of 33-bytes-HexString (String)

(*): you must specify either recipientNode or recipientBlindedRoute, but not both.

## SignMessage

```shell
curl -s -u :<eclair_api_password> -X POST -F msg=aGVsbG8gd29ybGQ= "http://localhost:8080/signmessage"

# with eclair-cli
eclair-cli signmessage --msg=$(echo -n 'hello world' | base64)
```

> The above command returns:

```json
{
  "nodeId": "0334171a1d556289f583b7c138c5cb5d02d4553245d5713a62d9953f6566a6fe12",
  "message": "aGVsbG8gd29ybGQ=",
  "signature": "1f9a6cc947bdb6fc14caae87be6bd76a6877d87cc83a80dec9aa8d1a23d1529fad418ce4ab5a7fb7afcfb351b317deb83d8141e68ba442f4aa4bbb534a8d27f851"
}
```

Sign a base64-encoded message with the node's private key.

### HTTP Request

`POST http://localhost:8080/signmessage`

### Parameters

Parameter | Description                    | Optional | Type
--------- | ------------------------------ | -------- | ---------------
msg       | Base64-encoded message to sign | No       | String (Base64)

## VerifyMessage

```shell
curl -s -u :<eclair_api_password> -X POST -F msg=aGVsbG8gd29ybGQ= \
  -F sig=1f9a6cc947bdb6fc14caae87be6bd76a6877d87cc83a80dec9aa8d1a23d1529fad418ce4ab5a7fb7afcfb351b317deb83d8141e68ba442f4aa4bbb534a8d27f851 \
  "http://localhost:8080/verifymessage"

# with eclair-cli
eclair-cli verifymessage --msg=$(echo -n 'hello world' | base64) --sig=1f9a6cc947bdb6fc14caae87be6bd76a6877d87cc83a80dec9aa8d1a23d1529fad418ce4ab5a7fb7afcfb351b317deb83d8141e68ba442f4aa4bbb534a8d27f851
```

> The above command returns:

```json
{
  "valid": true,
  "publicKey": "0334171a1d556289f583b7c138c5cb5d02d4553245d5713a62d9953f6566a6fe12"
}
```

Verify a base64-encoded message signature.
The public key of the signing node will be identified and returned.

### HTTP Request

`POST http://localhost:8080/verifymessage`

### Parameters

Parameter | Description                      | Optional | Type
--------- | -------------------------------- | -------- | ---------------
msg       | Base64-encoded message to verify | No       | String (Base64)
sig       | Message signature                | No       | String (Hex)

# Miscellaneous

## Audit

```shell
curl -s -u :<eclair_api_password> -X POST "http://localhost:8080/audit"

# with eclair-cli
eclair-cli audit
```

> The above command returns:

```json
{
  "sent": [
    {
      "type": "payment-sent",
      "id": "92871365-792e-49a8-a4db-226d62ba7fc0",
      "paymentHash": "8027352ba5e4d7ee1a34b41e25c53063388f5bf2656c02701a44573c2c44fcfc",
      "paymentPreimage": "0f9caf8ab7af20531e4c55d6c7a90cf11a50a30c41653d660f16be028f8be9b7",
      "recipientAmount": 25000000,
      "recipientNodeId": "03231a64020e2f95dda014b3ca424e36f7b2f4b17d1e959da415b7c1d64c3829e9",
      "parts": [
        {
          "id": "fb80e29c-3921-4470-b4cd-fd7db912809b",
          "channelId": "ddf85a7737004f9bcfb94573e6abac0873c25aa4fdc7e9fba279e53a49ec0de5",
          "nextNodeId": "03231a64020e2f95dda014b3ca424e36f7b2f4b17d1e959da415b7c1d64c3829e9",
          "amountWithFees": 25000000,
          "fees": 0,
          "startedAt": {
            "iso": "2026-05-22T12:13:26.191Z",
            "unix": 1779452006
          },
          "settledAt": {
            "iso": "2026-05-22T12:13:26.316Z",
            "unix": 1779452006
          }
        }
      ],
      "fees": 0,
      "startedAt": {
        "iso": "2026-05-22T12:13:26.191Z",
        "unix": 1779452006
      },
      "settledAt": {
        "iso": "2026-05-22T12:13:26.316Z",
        "unix": 1779452006
      }
    },
    {
      "type": "payment-sent",
      "id": "c0f55d65-f91f-4d48-b9f2-bb882bee267f",
      "paymentHash": "aaf4f70f86562a7b11961142f9b9005ec7015922de041bf0351e6dcf21ca6082",
      "paymentPreimage": "b582838c99e5b9b013f4ffd842febd55891fc86fe4b579d860b38f613ec989e6",
      "recipientAmount": 10000000,
      "recipientNodeId": "03231a64020e2f95dda014b3ca424e36f7b2f4b17d1e959da415b7c1d64c3829e9",
      "parts": [
        {
          "id": "4da72405-59ab-44b2-80a4-0929ecc2f8b8",
          "channelId": "ddf85a7737004f9bcfb94573e6abac0873c25aa4fdc7e9fba279e53a49ec0de5",
          "nextNodeId": "03231a64020e2f95dda014b3ca424e36f7b2f4b17d1e959da415b7c1d64c3829e9",
          "amountWithFees": 10000000,
          "fees": 0,
          "startedAt": {
            "iso": "2026-05-22T12:13:56.283Z",
            "unix": 1779452036
          },
          "settledAt": {
            "iso": "2026-05-22T12:13:56.359Z",
            "unix": 1779452036
          }
        }
      ],
      "fees": 0,
      "startedAt": {
        "iso": "2026-05-22T12:13:56.283Z",
        "unix": 1779452036
      },
      "settledAt": {
        "iso": "2026-05-22T12:13:56.359Z",
        "unix": 1779452036
      }
    }
  ],
  "received": [
    {
      "type": "payment-received",
      "paymentHash": "d512f15a0dd2deee3a5e603f187ca2a00ba9235b7d147b42c2e817b87e057d33",
      "parts": [
        {
          "channelId": "da9171e06fbb7d18a50deeab567dbf35498c69da7542d0222c2f22da7fa2e300",
          "remoteNodeId": "023b19f5990b2eee1a3bfc66cb9fdfeba62f7e058f13faf236249cc8d53543f20b",
          "amount": 40000000,
          "receivedAt": {
            "iso": "2026-05-22T11:52:28.954Z",
            "unix": 1779450748
          }
        }
      ]
    },
    {
      "type": "payment-received",
      "paymentHash": "f456821221cf9df9992b76f4ea2fcdd33377dd98be96e2e75f4080dd3b72137f",
      "parts": [
        {
          "channelId": "bbc8ccc2dfa98426c38d3f30a2fa92d509b19d9971d5bcb519a8fb50b0c90911",
          "remoteNodeId": "023b19f5990b2eee1a3bfc66cb9fdfeba62f7e058f13faf236249cc8d53543f20b",
          "amount": 15000000,
          "receivedAt": {
            "iso": "2026-05-22T12:03:45.886Z",
            "unix": 1779451425
          }
        }
      ]
    },
    {
      "type": "payment-received",
      "paymentHash": "2c90ca142c635e13b7ae59322e3ba5a397946d4a2cb66ae2cf8f3a1f3ea2c71c",
      "parts": [
        {
          "channelId": "bbc8ccc2dfa98426c38d3f30a2fa92d509b19d9971d5bcb519a8fb50b0c90911",
          "remoteNodeId": "023b19f5990b2eee1a3bfc66cb9fdfeba62f7e058f13faf236249cc8d53543f20b",
          "amount": 20000000,
          "receivedAt": {
            "iso": "2026-05-22T12:06:19.937Z",
            "unix": 1779451579
          }
        }
      ]
    }
  ],
  "relayed": [
    {
      "type": "payment-relayed",
      "paymentHash": "8bcbd47bee565c051641944961915cd6498d324c5a38437091e6b8990e02f856",
      "incoming": [
        {
          "channelId": "bbc8ccc2dfa98426c38d3f30a2fa92d509b19d9971d5bcb519a8fb50b0c90911",
          "remoteNodeId": "023b19f5990b2eee1a3bfc66cb9fdfeba62f7e058f13faf236249cc8d53543f20b",
          "amount": 12003400,
          "receivedAt": {
            "iso": "2026-05-22T12:14:20.264Z",
            "unix": 1779452060
          }
        }
      ],
      "outgoing": [
        {
          "channelId": "ddf85a7737004f9bcfb94573e6abac0873c25aa4fdc7e9fba279e53a49ec0de5",
          "remoteNodeId": "03231a64020e2f95dda014b3ca424e36f7b2f4b17d1e959da415b7c1d64c3829e9",
          "amount": 12000000,
          "settledAt": {
            "iso": "2026-05-22T12:14:20.331Z",
            "unix": 1779452060
          }
        }
      ]
    }
  ]
}
```

Retrieves information about payments handled by this node such as: sent, received and relayed payments.
All monetary values are expressed in millisatoshi.

### HTTP Request

`POST http://localhost:8080/audit`

### Parameters

Parameter | Description                                           | Optional | Type
--------- | ----------------------------------------------------- | -------- | -----------------------------------
from      | Filters elements no older than this unix-timestamp    | Yes      | Unix timestamp in seconds (Integer)
to        | Filters elements no younger than this unix-timestamp  | Yes      | Unix timestamp in seconds (Integer)
count     | Limits the number of results returned                 | Yes      | Integer
skip      | Skip some number of results                           | Yes      | Integer

## RelayStats

```shell
curl -s -u :<eclair_api_password> -X POST "http://localhost:8080/relaystats"

# with eclair-cli
eclair-cli relaystats
```

> The above command returns:

```json
[
  {
    "remoteNodeId": "03231a64020e2f95dda014b3ca424e36f7b2f4b17d1e959da415b7c1d64c3829e9",
    "incomingPaymentCount": 0,
    "totalAmountIn": 0,
    "outgoingPaymentCount": 1,
    "totalAmountOut": 12000000,
    "relayFeeEarned": 3400,
    "onChainTransactionsCount": 1,
    "onChainFeePaid": 825,
    "liquidityFeeEarned": 0,
    "liquidityFeePaid": 0,
    "from": {
      "iso": "2026-05-21T12:15:37Z",
      "unix": 1779365737
    },
    "to": {
      "iso": "2026-05-22T12:15:37Z",
      "unix": 1779452137
    }
  },
  {
    "remoteNodeId": "023b19f5990b2eee1a3bfc66cb9fdfeba62f7e058f13faf236249cc8d53543f20b",
    "incomingPaymentCount": 1,
    "totalAmountIn": 12003400,
    "outgoingPaymentCount": 0,
    "totalAmountOut": 0,
    "relayFeeEarned": 0,
    "onChainTransactionsCount": 6,
    "onChainFeePaid": 842,
    "liquidityFeeEarned": 0,
    "liquidityFeePaid": 0,
    "from": {
      "iso": "2026-05-21T12:15:37Z",
      "unix": 1779365737
    },
    "to": {
      "iso": "2026-05-22T12:15:37Z",
      "unix": 1779452137
    }
  }
]
```

Retrieves information about payment statistics for each of our peers (fees earned, fees paid, etc).
This can be used to identify good-performing peers.

### HTTP Request

`POST http://localhost:8080/relaystats`

### Parameters

Parameter | Description                                                 | Optional | Type
--------- | ----------------------------------------------------------- | -------- | -----------------------------------
nodeId    | If provided, returns statistics only for the specific peer. | Yes      | 33-bytes-HexString (String)
from      | Filters elements no older than this unix-timestamp          | Yes      | Unix timestamp in seconds (Integer)
to        | Filters elements no younger than this unix-timestamp        | Yes      | Unix timestamp in seconds (Integer)
count     | Limits the number of results returned                       | Yes      | Integer
skip      | Skip some number of results                                 | Yes      | Integer

## UsableBalances

```shell
curl -s -u :<eclair_api_password> -X POST "http://localhost:8080/usablebalances"

# with eclair-cli
eclair-cli usablebalances
```

> The above command returns:

```json
[
  {
    "remoteNodeId": "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
    "realScid": "562890x809x0",
    "aliases": {
      "localAlias": "0x17537e03b55a01e",
      "remoteAlias": "0xcde44c7ebd1449"
    },
    "canSend": 131219000,
    "canReceive": 466000,
    "isPublic": true,
    "isEnabled": true
  }
]
```

Retrieves information about the available balance of local channels, excluding channels that are disabled or empty.

### HTTP Request

`POST http://localhost:8080/usablebalances`

## ChannelBalances

```shell
curl -s -u :<eclair_api_password> -X POST "http://localhost:8080/channelbalances"

# with eclair-cli
eclair-cli channelbalances
```

> The above command returns:

```json
[
  {
    "remoteNodeId": "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
    "realScid": "562890x809x0",
    "aliases": {
      "localAlias": "0x17537e03b55a01e",
      "remoteAlias": "0xcde44c7ebd1449"
    },
    "canSend": 131219000,
    "canReceive": 466000,
    "isPublic": true,
    "isEnabled": true
  },
  {
    "remoteNodeId": "02865c138ddfb0e1e8c62aa8cebbed383d5b343c2d40fa22c31773a6725854154f",
    "realScid": "562890x809x1",
    "aliases": {
      "localAlias": "0x8676ba94f75888",
      "remoteAlias": "0x317b1df704e350f"
    },
    "canSend": 0,
    "canReceive": 1250000,
    "isPublic": true,
    "isEnabled": false
  }
]
```

Retrieves information about the available balance of all local channels, including channels that are disabled or empty.

### HTTP Request

`POST http://localhost:8080/channelbalances`

## GlobalBalance

```shell
curl -s -u :<eclair_api_password> -X POST "http://localhost:8080/globalbalance"

# with eclair-cli
eclair-cli globalbalance
```

> The above command returns:

```json
{
  "total": 0.31086253,
  "onChain": {
    "total": 0.09854434,
    "deeplyConfirmed": {
      "14e1ef26060acef6be7d59c72a8ccde2e9caffa9e2332544720f6880eeb4c542:0": 0.00076293,
      "9b0f34327850ea4248b564434a719413c8532f334f3cfeec2584ee8954595331:0": 0.00000596
    },
    "recentlyConfirmed": {
      "39ddf4690775bb0eb58c14c224638de48a639678c9c30d4f3447d5fa4bb89793:0": 0.00009536
    },
    "unconfirmed": {
      "573cdfd776ac5f640a76b0428cc1a8a103501a784764c5de2fdc729d1b26caf1:0": 0.00002384,
      "6985f97b2e58f963e7d81127d0b8dbd30a0c63939929efff745c2f789f550cd5:0": 0.09765625
    }
  },
  "offChain": {
    "waitForFundingConfirmed": 0,
    "waitForChannelReady": 0,
    "normal": {
      "toLocal": 0.12445015,
      "htlcs": 0.01523656
    },
    "shutdown": {
      "toLocal": 0,
      "htlcs": 0
    },
    "negotiating": {
      "toLocal": 0,
      "htlcs": 0
    },
    "closing": {
      "toLocal": 0.07210841,
      "htlcs": 0.00052307
    },
    "waitForPublishFutureCommitment": 0
  }
}
```

Retrieves information about the total balance of your node, taking into account pending transactions when a channel is closing.
This API can be used to regularly check that your node is not losing funds.
However, it is computationally intensive, so you should not call it too often to avoid disrupting your node's operations.

All amounts are in bitcoin.

### HTTP Request

`POST http://localhost:8080/globalbalance`

## GetMasterXpub

```shell
curl -s -u :<eclair_api_password> -X POST "http://localhost:8080/getmasterxpub"

# with eclair-cli
eclair-cli getmasterxpub
```

> The above command returns:

```json
{
  "xpub": "xpub6EE2N7jrues5kfjrsyFA5f7hknixqqAEKs8vyMN4QW9vDmYnChzpeBPkBYduBobbe4miQ34xHG4Jpwuq5bHXLZY1xixoGynW31ySUqqVvcU"
}
```

Returns the master BIP32 extended public key of your on-chain wallet.
This is useful when eclair manages the on-chain keys instead of delegating that to Bitcoin Core.

### HTTP Request

`POST http://localhost:8080/getmasterxpub`

### Parameters

Parameter | Description                              | Optional | Type
--------- | ---------------------------------------- | -------- | --------
account   | BIP32 account (derived from root key)    | Yes      | Integer

## GetDescriptors

```shell
curl -s -u :<eclair_api_password> -X POST "http://localhost:8080/getdescriptors"

# with eclair-cli
eclair-cli getdescriptors
```

> The above command returns:

```json
[
  {
    "desc": "<receive_descriptor>",
    "internal": false,
    "active": true,
    "timestamp": 0
  },
  {
    "desc": "<change_descriptor>",
    "internal": true,
    "active": true,
    "timestamp": 0
  }
]
```

Returns output script descriptors for the main and change addresses of your on-chain wallet.
This is useful when eclair manages the on-chain keys instead of delegating that to Bitcoin Core.

### HTTP Request

`POST http://localhost:8080/getdescriptors`

### Parameters

Parameter | Description                              | Optional | Type
--------- | ---------------------------------------- | -------- | --------
account   | BIP32 account (derived from root key)    | Yes      | Integer

# WebSocket

## WS

This is a simple [WebSocket](https://tools.ietf.org/html/rfc6455) that will output payment related events. It supports
several types covering all the possible outcomes. All monetary values are expressed in millisatoshi.

> Payment relayed event

```json
{
  "type": "payment-relayed",
  "paymentHash": "8bcbd47bee565c051641944961915cd6498d324c5a38437091e6b8990e02f856",
  "incoming": [
    {
      "channelId": "bbc8ccc2dfa98426c38d3f30a2fa92d509b19d9971d5bcb519a8fb50b0c90911",
      "remoteNodeId": "023b19f5990b2eee1a3bfc66cb9fdfeba62f7e058f13faf236249cc8d53543f20b",
      "amount": 12003400,
      "receivedAt": {
        "iso": "2026-05-22T12:14:20.264Z",
        "unix": 1779452060
      }
    }
  ],
  "outgoing": [
    {
      "channelId": "ddf85a7737004f9bcfb94573e6abac0873c25aa4fdc7e9fba279e53a49ec0de5",
      "remoteNodeId": "03231a64020e2f95dda014b3ca424e36f7b2f4b17d1e959da415b7c1d64c3829e9",
      "amount": 12000000,
      "settledAt": {
        "iso": "2026-05-22T12:14:20.331Z",
        "unix": 1779452060
      }
    }
  ]
}
```

> Payment received event

```json
{
  "type": "payment-received",
  "paymentHash": "2c90ca142c635e13b7ae59322e3ba5a397946d4a2cb66ae2cf8f3a1f3ea2c71c",
  "parts": [
    {
      "channelId": "bbc8ccc2dfa98426c38d3f30a2fa92d509b19d9971d5bcb519a8fb50b0c90911",
      "remoteNodeId": "023b19f5990b2eee1a3bfc66cb9fdfeba62f7e058f13faf236249cc8d53543f20b",
      "amount": 20000000,
      "receivedAt": {
        "iso": "2026-05-22T12:06:19.937Z",
        "unix": 1779451579
      }
    }
  ]
}
```

> Payment failed event

```json
{
  "type": "payment-failed",
  "id": "2704e79f-9f6e-4a0f-bc79-db26c7461d30",
  "paymentHash": "cf1315043744b4da2cae631b88e754f0bc8b22067d1cc34927de4cdce53cbf8e",
  "failures": [
    {
      "amount": 55000000,
      "route": [
        {
          "shortChannelId": "68455x1x1",
          "nodeId": "03231a64020e2f95dda014b3ca424e36f7b2f4b17d1e959da415b7c1d64c3829e9",
          "nextNodeId": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257",
          "params": {
            "type": "announcement",
            "channelUpdate": {
              "signature": "f324b74ab16306d35c1e203fc1f6222ea0c60246849c9ec40aad5ca84da4f4905de7541106c60245919541ed0394abaecfaf8d684fcb6a2d92ed7560e9d1db99",
              "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
              "shortChannelId": "68455x1x1",
              "timestamp": {
                "iso": "2026-05-22T12:12:59Z",
                "unix": 1779451979
              },
              "messageFlags": {
                "dontForward": false
              },
              "channelFlags": {
                "isEnabled": true,
                "isNode1": false
              },
              "cltvExpiryDelta": 144,
              "htlcMinimumMsat": 500,
              "feeBaseMsat": 1000,
              "feeProportionalMillionths": 200,
              "htlcMaximumMsat": 75000000,
              "tlvStream": {}
            }
          }
        },
        {
          "shortChannelId": "68435x2x1",
          "nodeId": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257",
          "nextNodeId": "023b19f5990b2eee1a3bfc66cb9fdfeba62f7e058f13faf236249cc8d53543f20b",
          "params": {
            "type": "announcement",
            "channelUpdate": {
              "signature": "1a3343dc148b7ac2c87b3aaca333a063ce02a0eaad559fda5216ba89b5a065df7cc1df528bc2b4fe052740f20cebdf0b8b75f7b72604dc2adff7d6747442076a",
              "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
              "shortChannelId": "68435x2x1",
              "timestamp": {
                "iso": "2026-05-22T11:56:23Z",
                "unix": 1779450983
              },
              "messageFlags": {
                "dontForward": false
              },
              "channelFlags": {
                "isEnabled": true,
                "isNode1": false
              },
              "cltvExpiryDelta": 144,
              "htlcMinimumMsat": 1000,
              "feeBaseMsat": 1000,
              "feeProportionalMillionths": 200,
              "htlcMaximumMsat": 75000000,
              "tlvStream": {}
            }
          }
        }
      ],
      "e": {
        "originNode": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257",
        "index": 1,
        "failureMessage": "channel is currently unavailable (scid=Some(68435x2x1))"
      },
      "startedAt": {
        "iso": "2026-05-22T12:19:14.064Z",
        "unix": 1779452354
      },
      "failedAt": {
        "iso": "2026-05-22T12:19:14.144Z",
        "unix": 1779452354
      }
    },
    {
      "amount": 55000000,
      "route": [],
      "t": "route not found"
    }
  ],
  "startedAt": {
    "iso": "2026-05-22T12:19:14.010Z",
    "unix": 1779452354
  },
  "settledAt": {
    "iso": "2026-05-22T12:19:14.175Z",
    "unix": 1779452354
  }
}
```

> Payment sent event

```json
{
  "type": "payment-sent",
  "id": "c690a208-6ee6-4061-a5ac-c19f3487c04a",
  "paymentHash": "8bcbd47bee565c051641944961915cd6498d324c5a38437091e6b8990e02f856",
  "paymentPreimage": "7ab5a4d1f9f99a5fed985d82a29ad2fbafd8058ef5bde5c8d8a5e7bc19caf510",
  "recipientAmount": 12000000,
  "recipientNodeId": "03231a64020e2f95dda014b3ca424e36f7b2f4b17d1e959da415b7c1d64c3829e9",
  "parts": [
    {
      "id": "749dfeec-a858-45ec-ace0-9a9fae863b85",
      "channelId": "bbc8ccc2dfa98426c38d3f30a2fa92d509b19d9971d5bcb519a8fb50b0c90911",
      "nextNodeId": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257",
      "amountWithFees": 12003400,
      "fees": 3400,
      "route": [
        {
          "shortChannelId": "0x241b2817588036e",
          "nodeId": "023b19f5990b2eee1a3bfc66cb9fdfeba62f7e058f13faf236249cc8d53543f20b",
          "nextNodeId": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257",
          "params": {
            "type": "announcement",
            "channelUpdate": {
              "signature": "9659f77ea03458907c8b1c29ee36ce1c1f0ab616d9985b7cce7a5c12288673646d89356cc8a97bba656599094ae3a7780f701015309eec6adb3afe8322b932b8",
              "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
              "shortChannelId": "0x241b2817588036e",
              "timestamp": {
                "iso": "2026-05-22T11:51:57Z",
                "unix": 1779450717
              },
              "messageFlags": {
                "dontForward": true
              },
              "channelFlags": {
                "isEnabled": true,
                "isNode1": true
              },
              "cltvExpiryDelta": 144,
              "htlcMinimumMsat": 500,
              "feeBaseMsat": 15,
              "feeProportionalMillionths": 500,
              "htlcMaximumMsat": 70000000,
              "tlvStream": {}
            }
          }
        },
        {
          "shortChannelId": "68455x1x1",
          "nodeId": "0251fa1fd468243289c3203a582de9c22f57ffb96397e50a7026c082cb6e4a3257",
          "nextNodeId": "03231a64020e2f95dda014b3ca424e36f7b2f4b17d1e959da415b7c1d64c3829e9",
          "params": {
            "type": "announcement",
            "channelUpdate": {
              "signature": "9a922bd407433258ef08bd1e63077b2171636a5e1123dcbb037e8c1d317624111a0749921ebbe6599320e8d84daa3a6cf584b70bb5cfa69dd63df6e826963cbf",
              "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
              "shortChannelId": "68455x1x1",
              "timestamp": {
                "iso": "2026-05-22T12:12:59Z",
                "unix": 1779451979
              },
              "messageFlags": {
                "dontForward": false
              },
              "channelFlags": {
                "isEnabled": true,
                "isNode1": true
              },
              "cltvExpiryDelta": 144,
              "htlcMinimumMsat": 1,
              "feeBaseMsat": 1000,
              "feeProportionalMillionths": 200,
              "htlcMaximumMsat": 75000000,
              "tlvStream": {}
            }
          }
        }
      ],
      "startedAt": {
        "iso": "2026-05-22T12:14:20.222Z",
        "unix": 1779452060
      },
      "settledAt": {
        "iso": "2026-05-22T12:14:20.336Z",
        "unix": 1779452060
      }
    }
  ],
  "fees": 3400,
  "startedAt": {
    "iso": "2026-05-22T12:14:20.213Z",
    "unix": 1779452060
  },
  "settledAt": {
    "iso": "2026-05-22T12:14:20.336Z",
    "unix": 1779452060
  }
}
```

> Payment settling on-chain event

```json
{
   "type": "payment-settling-onchain",
   "id": "487da196-a4dc-4b1e-92b4-3e5e905e9f3f",
   "amount": 21,
   "paymentHash": "0100000000000000000000000000000000000000000000000000000000000000",
   "timestamp": {
     "iso": "2022-02-01T12:40:19.438Z",
     "unix": 1643719219
   }
}
```

> Channel created event

```json
{
  "type": "channel-created",
  "remoteNodeId": "02d150875194d076f662d4252a8dee7077ed4cc4a848bb9f83fb467b6d3c120199",
  "isOpener": true,
  "temporaryChannelId": "d4eb1fac020d877c73bb75788e23fc70398d6a891bb773f7860481bdba5af04b",
  "commitTxFeeratePerKw": 1200,
  "fundingTxFeeratePerKw": 2000
}
```

> Channel funding transaction created event

```json
{
  "type": "channel-funding-created",
  "remoteNodeId": "02d150875194d076f662d4252a8dee7077ed4cc4a848bb9f83fb467b6d3c120199",
  "channelId": "d4eb1fac020d877c73bb75788e23fc70398d6a891bb773f7860481bdba5af04b",
  "fundingTxId": "6bd3827228f1c078868ddb8b018c550241964a910cff82f566366c15042850b3",
  "fundingTxIndex": 0
}
```

> Channel funding transaction confirmed event

```json
{
  "type": "channel-confirmed",
  "remoteNodeId": "02d150875194d076f662d4252a8dee7077ed4cc4a848bb9f83fb467b6d3c120199",
  "channelId": "d4eb1fac020d877c73bb75788e23fc70398d6a891bb773f7860481bdba5af04b",
  "fundingTxId": "6bd3827228f1c078868ddb8b018c550241964a910cff82f566366c15042850b3",
  "fundingTxIndex": 0,
  "blockHeight": 750000
}
```

> Channel ready for payments event

```json
{
  "type": "channel-ready",
  "remoteNodeId": "02d150875194d076f662d4252a8dee7077ed4cc4a848bb9f83fb467b6d3c120199",
  "channelId": "d4eb1fac020d877c73bb75788e23fc70398d6a891bb773f7860481bdba5af04b",
  "fundingTxId": "6bd3827228f1c078868ddb8b018c550241964a910cff82f566366c15042850b3",
  "fundingTxIndex": 0
}
```

> Channel state change event

```json
{
  "type": "channel-state-changed",
  "channelId": "d4eb1fac020d877c73bb75788e23fc70398d6a891bb773f7860481bdba5af04b",
  "remoteNodeId": "02d150875194d076f662d4252a8dee7077ed4cc4a848bb9f83fb467b6d3c120199",
  "previousState": "OFFLINE",
  "currentState": "NORMAL"
}
```

> Channel closed event

```json
{
  "type": "channel-closed",
  "channelId": "d4eb1fac020d877c73bb75788e23fc70398d6a891bb773f7860481bdba5af04b",
  "closingType": "MutualClose",
  "closingTxId": "6bd3827228f1c078868ddb8b018c550241964a910cff82f566366c15042850b3"
}
```

> Onion message received event

```json
{
  "type": "onion-message-received",
  "pathId": "2a254790b136e3f0c0461faf4e02f7c87117a519215512765b7e58e0c4a96098",
  "unknownTlvs": {
    "43": "deadbeef"
  }
}
```

### Response types

Type                     | Description
------------------------ | ------------------------------------------------------------------
payment-received         | A payment has been received  
payment-relayed          | A payment has been successfully relayed
payment-sent             | A payment has been successfully sent
payment-settling-onchain | A payment wasn't fulfilled and its HTLC is being redeemed on-chain
payment-failed           | A payment failed
channel-created          | A channel opening flow has started
channel-funding-created  | A channel funding transaction has been published
channel-confirmed        | A channel funding transaction has been confirmed
channel-ready            | A channel is ready to send and receive payments
channel-state-changed    | A channel state changed (e.g. going from offline to connected)
channel-closed           | A channel has been closed
onion-message-received   | An onion message was received

### HTTP Request

`GET ws://localhost:8080/ws`
