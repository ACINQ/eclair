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
  "version": "0.13.0-992ec8c",
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
      "var_onion_optin": "mandatory",
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
      "option_payment_metadata": "optional"
    },
    "unknown": []
  },
  "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
  "network": "regtest",
  "blockHeight": 4789,
  "publicAddresses": [
    "34.239.230.56:9735",
    "of7husrflx7sforh3fw6yqlpwstee3wg5imvvmkp4bz6rbjxtg5nljad.onion:9735"
  ],
  "instanceId": "e8ab38f8-b609-452f-acdb-81c44f0f24cf"
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
"ok"
```

Increase the fees of an unconfirmed dual-funded channel to speed up confirmation.
You must specify the target **channelId** and the feerate that should be set for the funding transaction.
A negotiation will start with your channel peer, and if they agree, your node will publish an updated funding transaction paying more fees.

### HTTP Request

`POST http://localhost:8080/rbfopen`

### Parameters

Parameter                | Description                                             | Optional | Type
------------------------ | ------------------------------------------------------- | -------- | ---------------------------
channelId                | The **channelId** of the channel that should be RBF-ed  | No       | 33-bytes-HexString (String)
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
nodeId                    | The **nodeId** of the peer you want to update        | Yes (*)  | 32-bytes-HexString (String)
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
    "nodeId": "0357b329b267777c6aff5746b78766fb7a43df7f273c0365f3280cb6f0dfc589c0",
    "channelId": "ce6d41563a6f22a24609d57ba7151be4043288938c24606415e1fb7f0f7a815e",
    "state": "NORMAL",
    "data": {
      "type": "DATA_NORMAL",
      "commitments": {
        "channelParams": {
          "channelId": "ce6d41563a6f22a24609d57ba7151be4043288938c24606415e1fb7f0f7a815e",
          "channelConfig": [
            "funding_pubkey_based_channel_keypath"
          ],
          "channelFeatures": [
            "option_dual_fund"
          ],
          "localParams": {
            "nodeId": "03ad873be7d8e152f878af1e58f894920a220c291f19ef24d916c5702bc79018b8",
            "fundingKeyPath": [
              485985097,
              2512474026,
              2368020766,
              307411143,
              2583624096,
              45844937,
              3247813012,
              3954324383,
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
                "var_onion_optin": "mandatory",
                "option_static_remotekey": "mandatory",
                "option_scid_alias": "optional",
                "option_onion_messages": "optional",
                "option_support_large_channel": "optional",
                "option_anchors_zero_fee_htlc_tx": "optional",
                "payment_secret": "mandatory",
                "option_shutdown_anysegwit": "optional",
                "option_channel_type": "mandatory",
                "basic_mpp": "optional",
                "gossip_queries": "optional"
              },
              "unknown": []
            }
          },
          "remoteParams": {
            "nodeId": "0357b329b267777c6aff5746b78766fb7a43df7f273c0365f3280cb6f0dfc589c0",
            "revocationBasepoint": "0284344002ba31d467cfce51eb28fcc1f87ebd752c9016096ad65a9b36d721e936",
            "paymentBasepoint": "03fef2ce147f18096390463946b7475857abccf2fa142a08819bd7c6f014002220",
            "delayedPaymentBasepoint": "034b2536d3ddf9420579f6df98688039170b5d9d92834cffdebb81db53fb19364c",
            "htlcBasepoint": "0295958d3c50849b086e94e08bfa1c787a7cdd7b0e5313e45d12dbcf89228c5f11",
            "initFeatures": {
              "activated": {
                "option_simple_close": "optional",
                "option_route_blinding": "optional",
                "option_attribution_data": "optional",
                "option_dual_fund": "optional",
                "splice_prototype": "optional",
                "gossip_queries_ex": "optional",
                "option_quiesce": "optional",
                "option_data_loss_protect": "mandatory",
                "var_onion_optin": "mandatory",
                "option_static_remotekey": "mandatory",
                "option_scid_alias": "optional",
                "option_onion_messages": "optional",
                "option_support_large_channel": "optional",
                "option_anchors_zero_fee_htlc_tx": "optional",
                "payment_secret": "mandatory",
                "option_shutdown_anysegwit": "optional",
                "option_channel_type": "mandatory",
                "basic_mpp": "optional",
                "gossip_queries": "optional"
              },
              "unknown": []
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
          "localNextHtlcId": 0,
          "remoteNextHtlcId": 0
        },
        "active": [
          {
            "fundingTxIndex": 0,
            "fundingInput": "e5a7c83f5fbf2ef9905992769920b14b9f46c69d3a2bdd64059f4f409577dc13:0",
            "fundingAmount": 300000,
            "localFunding": {
              "status": "confirmed",
              "shortChannelId": "4790x1x0"
            },
            "remoteFunding": {
              "status": "locked"
            },
            "commitmentFormat": "anchor_outputs",
            "localCommitParams": {
              "dustLimit": 1000,
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
                "toLocal": 300000000,
                "toRemote": 0
              },
              "txId": "9ad0c40ad6b6a6cc0816c5726320c5fb50baf0c47c510a5db7a4f1122e1b5039",
              "remoteSig": {
                "sig": "5ac29c902a8b4ad2319d1a4029a4d4429522c2c31e3fc61033bf1fcc3575b87909127eb1d5c354b89206b10e6ed56b9b7a2ac2273a692ee99626fe53e8b0b777"
              },
              "htlcRemoteSigs": []
            },
            "remoteCommitParams": {
              "dustLimit": 2000,
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
                "toRemote": 300000000
              },
              "txId": "b3448234468a8b476ccc1122077d3436421da970e0fbfb90d461cd21e234547a",
              "remotePerCommitmentPoint": "02a7545b3bef210c71985075e1f77d69525bb8db6800ad68bff317c0fd11337516"
            }
          }
        ],
        "inactive": [],
        "remoteNextCommitInfo": "02ec08126caa28916c4a6807f4de7b6573e6382aa03ff47b2f03a5b41a3fbb5910",
        "remotePerCommitmentSecrets": null,
        "originChannels": {}
      },
      "aliases": {
        "localAlias": "0x284de5f5753b9d6",
        "remoteAlias": "0xde0692f232970d"
      },
      "lastAnnouncement_opt": {
        "nodeSignature1": "a3d928550224da320622629dbb90e1d0ec1cb121c4b47b1f3c38bbf0a4ae1d607aa04c6b9b078f5d4990eb0739674440ba9475e605e1dd9fe8e4ec2ff67ba96a",
        "nodeSignature2": "4cf5177603c1f6606bcd8ca19257adfd656d83f8b58c03dd2c105e9439728f4272b3d093cf9384098aaca9be566c1d414e0c0bca64234b9c374531abbe7c5c94",
        "bitcoinSignature1": "8586614bb1fe9e0162ac2a84cd237e17a321e13b972854ffa205836c64372aec3720ddb42ea710016dd5b1988b6fcc841afb95738a35295e48b72ed159b154ef",
        "bitcoinSignature2": "7cd366000387806c24402bb5941964eb4d4c9212dcd1ff5047b611837e0dd7d874124aee54c2ef5c14f0e0a9310cd212ed06351ed55c6f291371565b406e3863",
        "features": {
          "activated": {},
          "unknown": []
        },
        "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
        "shortChannelId": "4790x1x0",
        "nodeId1": "0357b329b267777c6aff5746b78766fb7a43df7f273c0365f3280cb6f0dfc589c0",
        "nodeId2": "03ad873be7d8e152f878af1e58f894920a220c291f19ef24d916c5702bc79018b8",
        "bitcoinKey1": "029ef4c8e16fe65204b47462b42294522e17e1762d03e0d551a77254fe8d8ae770",
        "bitcoinKey2": "037a0dfbf6944aa118b4617eb773b115c6e629a918601cf000a2a49ee8700ba167",
        "tlvStream": {}
      },
      "channelUpdate": {
        "signature": "be32e12a763ada557140dd14d65baea98089d95dc6c1cbeed896895e285f1f225d6462011fcbc2e54828522759ccfdcedb3b1652e3f3a8c2387ab5e96b126cbc",
        "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
        "shortChannelId": "4790x1x0",
        "timestamp": {
          "iso": "2025-09-10T15:02:30Z",
          "unix": 1757516550
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
    "nodeId": "0357b329b267777c6aff5746b78766fb7a43df7f273c0365f3280cb6f0dfc589c0",
    "channelId": "1dca5bab220ff38e31540178c05b9e6c72ca52b31cb4a17cbdbc091ccbb6eba4",
    "state": "WAIT_FOR_DUAL_FUNDING_CONFIRMED",
    "data": {
      "type": "DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED",
      "commitments": {
        "channelParams": {
          "channelId": "1dca5bab220ff38e31540178c05b9e6c72ca52b31cb4a17cbdbc091ccbb6eba4",
          "channelConfig": [
            "funding_pubkey_based_channel_keypath"
          ],
          "channelFeatures": [
            "option_dual_fund"
          ],
          "localParams": {
            "nodeId": "03ad873be7d8e152f878af1e58f894920a220c291f19ef24d916c5702bc79018b8",
            "fundingKeyPath": [
              3340380005,
              1335887636,
              2111911869,
              3977394941,
              956438983,
              2067976811,
              1837273456,
              849282389,
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
                "var_onion_optin": "mandatory",
                "option_static_remotekey": "mandatory",
                "option_scid_alias": "optional",
                "option_onion_messages": "optional",
                "option_support_large_channel": "optional",
                "option_anchors_zero_fee_htlc_tx": "optional",
                "payment_secret": "mandatory",
                "option_shutdown_anysegwit": "optional",
                "option_channel_type": "mandatory",
                "basic_mpp": "optional",
                "gossip_queries": "optional"
              },
              "unknown": []
            }
          },
          "remoteParams": {
            "nodeId": "0357b329b267777c6aff5746b78766fb7a43df7f273c0365f3280cb6f0dfc589c0",
            "revocationBasepoint": "027eb5ab22d4a72521afeae7a08f35b375e443845ccce86065876f5dc3b70299e4",
            "paymentBasepoint": "03f5e11098f4236436392f5d64da1ad53ed47280b5f53ec7198863a8593ff43af8",
            "delayedPaymentBasepoint": "02847fa3060a975ec63ba55e589d69fdbc4aab58a1a3935ac013b7cda2a352e160",
            "htlcBasepoint": "02895cad2f59fe0fdd4daf8cc5360f1ebd0aff2938eb30de0b3a223a15cbeeaec3",
            "initFeatures": {
              "activated": {
                "option_simple_close": "optional",
                "option_route_blinding": "optional",
                "option_attribution_data": "optional",
                "option_dual_fund": "optional",
                "splice_prototype": "optional",
                "gossip_queries_ex": "optional",
                "option_quiesce": "optional",
                "option_data_loss_protect": "mandatory",
                "var_onion_optin": "mandatory",
                "option_static_remotekey": "mandatory",
                "option_scid_alias": "optional",
                "option_onion_messages": "optional",
                "option_support_large_channel": "optional",
                "option_anchors_zero_fee_htlc_tx": "optional",
                "payment_secret": "mandatory",
                "option_shutdown_anysegwit": "optional",
                "option_channel_type": "mandatory",
                "basic_mpp": "optional",
                "gossip_queries": "optional"
              },
              "unknown": []
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
          "localNextHtlcId": 0,
          "remoteNextHtlcId": 0
        },
        "active": [
          {
            "fundingTxIndex": 0,
            "fundingInput": "4b32992585b0464ade268316ecf6e2b5e8e3ad93a4c122510e3df77a1d327763:1",
            "fundingAmount": 220000,
            "localFunding": {
              "status": "unconfirmed",
              "txid": "4b32992585b0464ade268316ecf6e2b5e8e3ad93a4c122510e3df77a1d327763"
            },
            "remoteFunding": {
              "status": "not-locked"
            },
            "commitmentFormat": "anchor_outputs",
            "localCommitParams": {
              "dustLimit": 1000,
              "htlcMinimum": 1000,
              "maxHtlcValueInFlight": 66000000,
              "maxAcceptedHtlcs": 10,
              "toSelfDelay": 100
            },
            "localCommit": {
              "index": 0,
              "spec": {
                "htlcs": [],
                "commitTxFeerate": 2500,
                "toLocal": 220000000,
                "toRemote": 0
              },
              "txId": "21701fbaeb11bf6dddcd9bced34f7528e0a4c37f314af3e8273abf565574de59",
              "remoteSig": {
                "sig": "bc8899d8908830655df2c6f06e6ee81f56aa163137bd05e9c11d381f20b8e7b050300fa748aa6374bb9d8400f702f5411d8bbacf7b4d81346ffb5a43ff1d39e9"
              },
              "htlcRemoteSigs": []
            },
            "remoteCommitParams": {
              "dustLimit": 2000,
              "htlcMinimum": 500,
              "maxHtlcValueInFlight": 55000000,
              "maxAcceptedHtlcs": 15,
              "toSelfDelay": 144
            },
            "remoteCommit": {
              "index": 0,
              "spec": {
                "htlcs": [],
                "commitTxFeerate": 2500,
                "toLocal": 0,
                "toRemote": 220000000
              },
              "txId": "ce0a4b5cc03b5c659065b0637fea6aa57f4bdf48de3542d16ef230e7f5a9bce2",
              "remotePerCommitmentPoint": "022c2171ef9463e3e27ad61efbfe5e6d341822f47a43fd3935ed3be551314adf15"
            }
          }
        ],
        "inactive": [],
        "remoteNextCommitInfo": "036ea58190fd1e03bae51bdef153fe7b9f05b1f1290e45adb355fa135e04f85b9d",
        "remotePerCommitmentSecrets": null,
        "originChannels": {}
      },
      "localPushAmount": 0,
      "remotePushAmount": 0,
      "waitingSince": 4799,
      "lastChecked": 4799,
      "status": {}
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
  "nodeId": "0357b329b267777c6aff5746b78766fb7a43df7f273c0365f3280cb6f0dfc589c0",
  "channelId": "ce6d41563a6f22a24609d57ba7151be4043288938c24606415e1fb7f0f7a815e",
  "state": "NORMAL",
  "data": {
    "type": "DATA_NORMAL",
    "commitments": {
      "channelParams": {
        "channelId": "ce6d41563a6f22a24609d57ba7151be4043288938c24606415e1fb7f0f7a815e",
        "channelConfig": [
          "funding_pubkey_based_channel_keypath"
        ],
        "channelFeatures": [
          "option_dual_fund"
        ],
        "localParams": {
          "nodeId": "03ad873be7d8e152f878af1e58f894920a220c291f19ef24d916c5702bc79018b8",
          "fundingKeyPath": [
            485985097,
            2512474026,
            2368020766,
            307411143,
            2583624096,
            45844937,
            3247813012,
            3954324383,
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
              "var_onion_optin": "mandatory",
              "option_static_remotekey": "mandatory",
              "option_scid_alias": "optional",
              "option_onion_messages": "optional",
              "option_support_large_channel": "optional",
              "option_anchors_zero_fee_htlc_tx": "optional",
              "payment_secret": "mandatory",
              "option_shutdown_anysegwit": "optional",
              "option_channel_type": "mandatory",
              "basic_mpp": "optional",
              "gossip_queries": "optional"
            },
            "unknown": []
          }
        },
        "remoteParams": {
          "nodeId": "0357b329b267777c6aff5746b78766fb7a43df7f273c0365f3280cb6f0dfc589c0",
          "revocationBasepoint": "0284344002ba31d467cfce51eb28fcc1f87ebd752c9016096ad65a9b36d721e936",
          "paymentBasepoint": "03fef2ce147f18096390463946b7475857abccf2fa142a08819bd7c6f014002220",
          "delayedPaymentBasepoint": "034b2536d3ddf9420579f6df98688039170b5d9d92834cffdebb81db53fb19364c",
          "htlcBasepoint": "0295958d3c50849b086e94e08bfa1c787a7cdd7b0e5313e45d12dbcf89228c5f11",
          "initFeatures": {
            "activated": {
              "option_simple_close": "optional",
              "option_route_blinding": "optional",
              "option_attribution_data": "optional",
              "option_dual_fund": "optional",
              "splice_prototype": "optional",
              "gossip_queries_ex": "optional",
              "option_quiesce": "optional",
              "option_data_loss_protect": "mandatory",
              "var_onion_optin": "mandatory",
              "option_static_remotekey": "mandatory",
              "option_scid_alias": "optional",
              "option_onion_messages": "optional",
              "option_support_large_channel": "optional",
              "option_anchors_zero_fee_htlc_tx": "optional",
              "payment_secret": "mandatory",
              "option_shutdown_anysegwit": "optional",
              "option_channel_type": "mandatory",
              "basic_mpp": "optional",
              "gossip_queries": "optional"
            },
            "unknown": []
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
        "localNextHtlcId": 0,
        "remoteNextHtlcId": 0
      },
      "active": [
        {
          "fundingTxIndex": 0,
          "fundingInput": "e5a7c83f5fbf2ef9905992769920b14b9f46c69d3a2bdd64059f4f409577dc13:0",
          "fundingAmount": 300000,
          "localFunding": {
            "status": "confirmed",
            "shortChannelId": "4790x1x0"
          },
          "remoteFunding": {
            "status": "locked"
          },
          "commitmentFormat": "anchor_outputs",
          "localCommitParams": {
            "dustLimit": 1000,
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
              "toLocal": 300000000,
              "toRemote": 0
            },
            "txId": "9ad0c40ad6b6a6cc0816c5726320c5fb50baf0c47c510a5db7a4f1122e1b5039",
            "remoteSig": {
              "sig": "5ac29c902a8b4ad2319d1a4029a4d4429522c2c31e3fc61033bf1fcc3575b87909127eb1d5c354b89206b10e6ed56b9b7a2ac2273a692ee99626fe53e8b0b777"
            },
            "htlcRemoteSigs": []
          },
          "remoteCommitParams": {
            "dustLimit": 2000,
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
              "toRemote": 300000000
            },
            "txId": "b3448234468a8b476ccc1122077d3436421da970e0fbfb90d461cd21e234547a",
            "remotePerCommitmentPoint": "02a7545b3bef210c71985075e1f77d69525bb8db6800ad68bff317c0fd11337516"
          }
        }
      ],
      "inactive": [],
      "remoteNextCommitInfo": "02ec08126caa28916c4a6807f4de7b6573e6382aa03ff47b2f03a5b41a3fbb5910",
      "remotePerCommitmentSecrets": null,
      "originChannels": {}
    },
    "aliases": {
      "localAlias": "0x284de5f5753b9d6",
      "remoteAlias": "0xde0692f232970d"
    },
    "lastAnnouncement_opt": {
      "nodeSignature1": "a3d928550224da320622629dbb90e1d0ec1cb121c4b47b1f3c38bbf0a4ae1d607aa04c6b9b078f5d4990eb0739674440ba9475e605e1dd9fe8e4ec2ff67ba96a",
      "nodeSignature2": "4cf5177603c1f6606bcd8ca19257adfd656d83f8b58c03dd2c105e9439728f4272b3d093cf9384098aaca9be566c1d414e0c0bca64234b9c374531abbe7c5c94",
      "bitcoinSignature1": "8586614bb1fe9e0162ac2a84cd237e17a321e13b972854ffa205836c64372aec3720ddb42ea710016dd5b1988b6fcc841afb95738a35295e48b72ed159b154ef",
      "bitcoinSignature2": "7cd366000387806c24402bb5941964eb4d4c9212dcd1ff5047b611837e0dd7d874124aee54c2ef5c14f0e0a9310cd212ed06351ed55c6f291371565b406e3863",
      "features": {
        "activated": {},
        "unknown": []
      },
      "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
      "shortChannelId": "4790x1x0",
      "nodeId1": "0357b329b267777c6aff5746b78766fb7a43df7f273c0365f3280cb6f0dfc589c0",
      "nodeId2": "03ad873be7d8e152f878af1e58f894920a220c291f19ef24d916c5702bc79018b8",
      "bitcoinKey1": "029ef4c8e16fe65204b47462b42294522e17e1762d03e0d551a77254fe8d8ae770",
      "bitcoinKey2": "037a0dfbf6944aa118b4617eb773b115c6e629a918601cf000a2a49ee8700ba167",
      "tlvStream": {}
    },
    "channelUpdate": {
      "signature": "be32e12a763ada557140dd14d65baea98089d95dc6c1cbeed896895e285f1f225d6462011fcbc2e54828522759ccfdcedb3b1652e3f3a8c2387ab5e96b126cbc",
      "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
      "shortChannelId": "4790x1x0",
      "timestamp": {
        "iso": "2025-09-10T15:02:30Z",
        "unix": 1757516550
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
    "nodeId": "0357b329b267777c6aff5746b78766fb7a43df7f273c0365f3280cb6f0dfc589c0",
    "channelId": "1dca5bab220ff38e31540178c05b9e6c72ca52b31cb4a17cbdbc091ccbb6eba4",
    "state": "CLOSED",
    "data": {
      "type": "DATA_CLOSING",
      "commitments": {
        "channelParams": {
          "channelId": "1dca5bab220ff38e31540178c05b9e6c72ca52b31cb4a17cbdbc091ccbb6eba4",
          "channelConfig": [
            "funding_pubkey_based_channel_keypath"
          ],
          "channelFeatures": [
            "option_dual_fund"
          ],
          "localParams": {
            "nodeId": "03ad873be7d8e152f878af1e58f894920a220c291f19ef24d916c5702bc79018b8",
            "fundingKeyPath": [
              3340380005,
              1335887636,
              2111911869,
              3977394941,
              956438983,
              2067976811,
              1837273456,
              849282389,
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
                "var_onion_optin": "mandatory",
                "option_static_remotekey": "mandatory",
                "option_scid_alias": "optional",
                "option_onion_messages": "optional",
                "option_support_large_channel": "optional",
                "option_anchors_zero_fee_htlc_tx": "optional",
                "payment_secret": "mandatory",
                "option_shutdown_anysegwit": "optional",
                "option_channel_type": "mandatory",
                "basic_mpp": "optional",
                "gossip_queries": "optional"
              },
              "unknown": []
            }
          },
          "remoteParams": {
            "nodeId": "0357b329b267777c6aff5746b78766fb7a43df7f273c0365f3280cb6f0dfc589c0",
            "revocationBasepoint": "027eb5ab22d4a72521afeae7a08f35b375e443845ccce86065876f5dc3b70299e4",
            "paymentBasepoint": "03f5e11098f4236436392f5d64da1ad53ed47280b5f53ec7198863a8593ff43af8",
            "delayedPaymentBasepoint": "02847fa3060a975ec63ba55e589d69fdbc4aab58a1a3935ac013b7cda2a352e160",
            "htlcBasepoint": "02895cad2f59fe0fdd4daf8cc5360f1ebd0aff2938eb30de0b3a223a15cbeeaec3",
            "initFeatures": {
              "activated": {
                "option_simple_close": "optional",
                "option_route_blinding": "optional",
                "option_attribution_data": "optional",
                "option_dual_fund": "optional",
                "splice_prototype": "optional",
                "gossip_queries_ex": "optional",
                "option_quiesce": "optional",
                "option_data_loss_protect": "mandatory",
                "var_onion_optin": "mandatory",
                "option_static_remotekey": "mandatory",
                "option_scid_alias": "optional",
                "option_onion_messages": "optional",
                "option_support_large_channel": "optional",
                "option_anchors_zero_fee_htlc_tx": "optional",
                "payment_secret": "mandatory",
                "option_shutdown_anysegwit": "optional",
                "option_channel_type": "mandatory",
                "basic_mpp": "optional",
                "gossip_queries": "optional"
              },
              "unknown": []
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
          "localNextHtlcId": 0,
          "remoteNextHtlcId": 0
        },
        "active": [
          {
            "fundingTxIndex": 0,
            "fundingInput": "4b32992585b0464ade268316ecf6e2b5e8e3ad93a4c122510e3df77a1d327763:1",
            "fundingAmount": 220000,
            "localFunding": {
              "status": "confirmed",
              "shortChannelId": "4800x1x1"
            },
            "remoteFunding": {
              "status": "not-locked"
            },
            "commitmentFormat": "anchor_outputs",
            "localCommitParams": {
              "dustLimit": 1000,
              "htlcMinimum": 1000,
              "maxHtlcValueInFlight": 66000000,
              "maxAcceptedHtlcs": 10,
              "toSelfDelay": 100
            },
            "localCommit": {
              "index": 0,
              "spec": {
                "htlcs": [],
                "commitTxFeerate": 2500,
                "toLocal": 220000000,
                "toRemote": 0
              },
              "txId": "21701fbaeb11bf6dddcd9bced34f7528e0a4c37f314af3e8273abf565574de59",
              "remoteSig": {
                "sig": "bc8899d8908830655df2c6f06e6ee81f56aa163137bd05e9c11d381f20b8e7b050300fa748aa6374bb9d8400f702f5411d8bbacf7b4d81346ffb5a43ff1d39e9"
              },
              "htlcRemoteSigs": []
            },
            "remoteCommitParams": {
              "dustLimit": 2000,
              "htlcMinimum": 500,
              "maxHtlcValueInFlight": 55000000,
              "maxAcceptedHtlcs": 15,
              "toSelfDelay": 144
            },
            "remoteCommit": {
              "index": 0,
              "spec": {
                "htlcs": [],
                "commitTxFeerate": 2500,
                "toLocal": 0,
                "toRemote": 220000000
              },
              "txId": "ce0a4b5cc03b5c659065b0637fea6aa57f4bdf48de3542d16ef230e7f5a9bce2",
              "remotePerCommitmentPoint": "022c2171ef9463e3e27ad61efbfe5e6d341822f47a43fd3935ed3be551314adf15"
            }
          }
        ],
        "inactive": [],
        "remoteNextCommitInfo": "036ea58190fd1e03bae51bdef153fe7b9f05b1f1290e45adb355fa135e04f85b9d",
        "remotePerCommitmentSecrets": null,
        "originChannels": {}
      },
      "waitingSince": 4799,
      "finalScriptPubKey": "0014e6585e13345665137fd957bb02439913ae7744c4",
      "mutualCloseProposed": [],
      "mutualClosePublished": [],
      "localCommitPublished": {
        "commitTx": {
          "txid": "21701fbaeb11bf6dddcd9bced34f7528e0a4c37f314af3e8273abf565574de59",
          "tx": "020000000001016377321d7af73d0e5122c1a493ade3e8b5e2f6ec168326de4a46b0852599324b0100000000b48ded80024a010000000000002200204992ef8d6ae973cd3a8c9ee521e329c852fce36e910ab42c4cf0451472070acbd24d030000000000220020c3fb58772dc68bcd51d8caa360a2a53893e19f2d19f9a777f5ab2e62417b39d2040047304402202917e99edeaec593579150dc0139249b630096cc50bc34e62d7b46b8f23c6b6d022022b712d3890fabbcd57fcdd8df06b4269e627210ada76ee5649a3f044196f51d01483045022100bc8899d8908830655df2c6f06e6ee81f56aa163137bd05e9c11d381f20b8e7b0022050300fa748aa6374bb9d8400f702f5411d8bbacf7b4d81346ffb5a43ff1d39e9014752210275b2cf7f06f38466f7d6a493c6f159f7ec2cf7446a4d42fb117d450ede5d8cd22102c6beb34f973aa87cdec8822c35939453e9d5d0a750e04b1c9197904736bfdd2f52aed7b27f20"
        },
        "localOutput_opt": "21701fbaeb11bf6dddcd9bced34f7528e0a4c37f314af3e8273abf565574de59:1",
        "anchorOutput_opt": "21701fbaeb11bf6dddcd9bced34f7528e0a4c37f314af3e8273abf565574de59:0",
        "incomingHtlcs": {},
        "outgoingHtlcs": {},
        "htlcDelayedOutputs": [],
        "irrevocablySpent": {}
      },
      "remoteCommitPublished": {
        "commitTx": {
          "txid": "ce0a4b5cc03b5c659065b0637fea6aa57f4bdf48de3542d16ef230e7f5a9bce2",
          "tx": "020000000001016377321d7af73d0e5122c1a493ade3e8b5e2f6ec168326de4a46b0852599324b0100000000b48ded80024a010000000000002200204992ef8d6ae973cd3a8c9ee521e329c852fce36e910ab42c4cf0451472070acbd24d03000000000022002030d39d429194ffccff129a0608f86d1f94922136b9dc884fdef3f6d578d4bcaf040047304402204626db3994e4ab96b1f6bb435aebd766b8c6c67264ee589d8ec0693c1c4f390b02207cb726dd0c7862a37e3ce018413615296c0c21d7331dfc7f5ee10a04fecd83070147304402204470f3bd9c657d0f6d2566dcfb65553be49d2e1ac570bf5c3dfd82c877b2eead02201d9b1dd8b3f439365867ea45c8895b9898c7f55a451acf96b81b8c4d15e95b45014752210275b2cf7f06f38466f7d6a493c6f159f7ec2cf7446a4d42fb117d450ede5d8cd22102c6beb34f973aa87cdec8822c35939453e9d5d0a750e04b1c9197904736bfdd2f52aed7b27f20"
        },
        "localOutput_opt": "ce0a4b5cc03b5c659065b0637fea6aa57f4bdf48de3542d16ef230e7f5a9bce2:1",
        "anchorOutput_opt": "ce0a4b5cc03b5c659065b0637fea6aa57f4bdf48de3542d16ef230e7f5a9bce2:0",
        "incomingHtlcs": {},
        "outgoingHtlcs": {},
        "irrevocablySpent": {
          "4b32992585b0464ade268316ecf6e2b5e8e3ad93a4c122510e3df77a1d327763:1": {
            "txid": "ce0a4b5cc03b5c659065b0637fea6aa57f4bdf48de3542d16ef230e7f5a9bce2",
            "tx": "020000000001016377321d7af73d0e5122c1a493ade3e8b5e2f6ec168326de4a46b0852599324b0100000000b48ded80024a010000000000002200204992ef8d6ae973cd3a8c9ee521e329c852fce36e910ab42c4cf0451472070acbd24d03000000000022002030d39d429194ffccff129a0608f86d1f94922136b9dc884fdef3f6d578d4bcaf040047304402204626db3994e4ab96b1f6bb435aebd766b8c6c67264ee589d8ec0693c1c4f390b02207cb726dd0c7862a37e3ce018413615296c0c21d7331dfc7f5ee10a04fecd83070147304402204470f3bd9c657d0f6d2566dcfb65553be49d2e1ac570bf5c3dfd82c877b2eead02201d9b1dd8b3f439365867ea45c8895b9898c7f55a451acf96b81b8c4d15e95b45014752210275b2cf7f06f38466f7d6a493c6f159f7ec2cf7446a4d42fb117d450ede5d8cd22102c6beb34f973aa87cdec8822c35939453e9d5d0a750e04b1c9197904736bfdd2f52aed7b27f20"
          },
          "ce0a4b5cc03b5c659065b0637fea6aa57f4bdf48de3542d16ef230e7f5a9bce2:1": {
            "txid": "48289384ef38092c31d0032f6c1260795db1c5eaa4300a3ea5afc19ce99f5db3",
            "tx": "02000000000101e2bca9f5e730f26ed14235de48df4b7fa56aea7f63b06590655c3bc05c4b0ace010000000001000000018149030000000000160014e6585e13345665137fd957bb02439913ae7744c4024730440220683143a54eba34368fa99dbeb965c71a1734597ba735d42db306939919ad2cdc022074e12094abb48bfc4866a701570d8238202b286b523fd91384a068965cb5804e01252103b9542b335cf1f27ebe55a3fa26436294b72f48262fea27198b7cada330e1d442ad51b200000000"
          }
        }
      },
      "revokedCommitPublished": []
    }
  }
]
```

Returns the list of recently closed local channels.

<aside class="warning">
The closedchannels API is costly if you have a large history of closed channels and don't filter by nodeId.
</aside>

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
    "signature": "c466c08fa16c1810e2971de2a57ef1f9e5e13d36a224544cf0e3d621030b9e617652b88fb2024bfdc60066ca63b4f67504f154e8fee7f13bc39739b76cc4419f",
    "features": {
      "activated": {
        "option_onion_messages": "optional",
        "gossip_queries_ex": "optional",
        "option_data_loss_protect": "optional",
        "var_onion_optin": "mandatory",
        "option_static_remotekey": "optional",
        "option_support_large_channel": "optional",
        "option_anchors_zero_fee_htlc_tx": "optional",
        "payment_secret": "mandatory",
        "option_shutdown_anysegwit": "optional",
        "option_channel_type": "optional",
        "basic_mpp": "optional",
        "gossip_queries": "optional"
      },
      "unknown": []
    },
    "timestamp": {
      "iso": "2022-02-01T12:27:19Z",
      "unix": 1643718439
    },
    "nodeId": "028e2403fbfddb3d787843361f91adbda64c6f622921b19fb48f5766508bcadb29",
    "rgbColor": "#49daaa",
    "alias": "alice",
    "addresses": [
      "138.229.205.237:9735"
    ],
    "tlvStream": {}
  },
  {
    "signature": "f6cce33383fe1291fa60cfa7d9efa4a45c081396e445e9cadc825ab695aab30308a68733d27fc54a5c46b888bdddd467f30f2f5441e95c2920b3b6c54decc3a1",
    "features": {
      "activated": {
        "option_onion_messages": "optional",
        "gossip_queries_ex": "optional",
        "option_data_loss_protect": "optional",
        "var_onion_optin": "mandatory",
        "option_static_remotekey": "optional",
        "option_support_large_channel": "optional",
        "option_anchors_zero_fee_htlc_tx": "optional",
        "payment_secret": "mandatory",
        "option_shutdown_anysegwit": "optional",
        "option_channel_type": "optional",
        "basic_mpp": "optional",
        "gossip_queries": "optional"
      },
      "unknown": []
    },
    "timestamp": {
      "iso": "2022-02-01T12:27:19Z",
      "unix": 1643718439
    },
    "nodeId": "02fe677ac8cd61399d097535a3e8a51a0849e57cdbab9b34796c86f3e33568cbe2",
    "rgbColor": "#49daaa",
    "alias": "bob",
    "addresses": [
      "95.216.16.21:9735",
      "[2a01:4f9:2a:106a:0:0:0:2]:9736"
    ],
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
    "signature": "8256ff47af066e47a6325f91a61a900e616c5cec58ca6c518d644a3d66aed9bd2c3dfdb13c56406aa89ac0bddb0c87ae68dd067c7a5a60eafe27facb0e7caeeb",
    "features": {
      "activated": {
        "option_route_blinding": "optional",
        "option_dual_fund": "optional",
        "gossip_queries_ex": "optional",
        "option_data_loss_protect": "optional",
        "var_onion_optin": "mandatory",
        "option_static_remotekey": "optional",
        "option_scid_alias": "optional",
        "option_onion_messages": "optional",
        "option_support_large_channel": "optional",
        "option_anchors_zero_fee_htlc_tx": "optional",
        "payment_secret": "mandatory",
        "option_shutdown_anysegwit": "optional",
        "option_channel_type": "optional",
        "basic_mpp": "optional",
        "gossip_queries": "optional"
      },
      "unknown": []
    },
    "timestamp": {
      "iso": "2023-06-15T14:30:13Z",
      "unix": 1686839413
    },
    "nodeId": "02ca41676c9dfff08553528b151b1bf82031a26bb1d6f852e0f8075d33fa4ea089",
    "rgbColor": "#49daaa",
    "alias": "bob",
    "addresses": [],
    "tlvStream": {}
  },
  "activeChannels": 1,
  "totalCapacity": 300000
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
    "signature": "02bbe4ee3f128ba044937428680d266c71231fd02d899c446aad498ca095610133f7c2ddb68ed0d8d29961d0962651556dc08b5cb00fb56055d2b98407f4addb",
    "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
    "shortChannelId": "2899x1x1",
    "timestamp": {
      "iso": "2022-02-01T12:27:50Z",
      "unix": 1643718470
    },
    "messageFlags": {
      "dontForward": false
    },
    "channelFlags": {
      "isEnabled": true,
      "isNode1": true
    },
    "cltvExpiryDelta": 48,
    "htlcMinimumMsat": 1,
    "feeBaseMsat": 5,
    "feeProportionalMillionths": 150,
    "htlcMaximumMsat": 450000000,
    "tlvStream": {}
  },
  {
    "signature": "1da0e7094424c0daa64fe8427e191095d14285dd9346f37d014d07d8857b53cc6bed703d22794ddbfc1945cf5bdb7566137441964e01f8facc30c17fd0dffa06",
    "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
    "shortChannelId": "2899x1x1",
    "timestamp": {
      "iso": "2022-02-01T12:27:19Z",
      "unix": 1643718439
    },
    "messageFlags": {
      "dontForward": false
    },
    "channelFlags": {
      "isEnabled": false,
      "isNode1": false
    },
    "cltvExpiryDelta": 48,
    "htlcMinimumMsat": 1,
    "feeBaseMsat": 1000,
    "feeProportionalMillionths": 200,
    "htlcMaximumMsat": 450000000,
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
  "timestamp": 1643718891,
  "nodeId": "028e2403fbfddb3d787843361f91adbda64c6f622921b19fb48f5766508bcadb29",
  "serialized": "lnbcrt500n1pslj28tpp55kxmmddatrnmf42a55mk4wzz4ryq8tv2vwrrarj27e0hhjgpscjqdq0ydex2cmtd3jhxucsp5qu6jq5heq4lcjpj2r8gp0sd65860yzc5yw3xrwde6c4m3mlessxsmqz9gxqrrsscqp79qtzsqqqqqysgqr2fy2yz4655hwql2nwkk3t9saxhj80340cxfzf7fwhweasncv77ym7wcv0p54e4kt7jpmfdavnj5urq84syh9t2t49qdgj4ra8jl40gp6ys45n",
  "description": "#reckless",
  "paymentHash": "a58dbdb5bd58e7b4d55da5376ab842a8c803ad8a63863e8e4af65f7bc9018624",
  "paymentMetadata": "2a",
  "expiry": 3600,
  "minFinalCltvExpiry": 30,
  "amount": 50000,
  "features": {
    "activated": {
      "payment_secret": "mandatory",
      "basic_mpp": "optional",
      "option_payment_metadata": "optional",
      "var_onion_optin": "mandatory"
    },
    "unknown": []
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
  "timestamp": 1643718891,
  "nodeId": "028e2403fbfddb3d787843361f91adbda64c6f622921b19fb48f5766508bcadb29",
  "serialized": "lnbcrt500n1pslj28tpp55kxmmddatrnmf42a55mk4wzz4ryq8tv2vwrrarj27e0hhjgpscjqdq0ydex2cmtd3jhxucsp5qu6jq5heq4lcjpj2r8gp0sd65860yzc5yw3xrwde6c4m3mlessxsmqz9gxqrrsscqp79qtzsqqqqqysgqr2fy2yz4655hwql2nwkk3t9saxhj80340cxfzf7fwhweasncv77ym7wcv0p54e4kt7jpmfdavnj5urq84syh9t2t49qdgj4ra8jl40gp6ys45n",
  "description": "#reckless",
  "paymentHash": "a58dbdb5bd58e7b4d55da5376ab842a8c803ad8a63863e8e4af65f7bc9018624",
  "paymentMetadata": "2a",
  "expiry": 3600,
  "minFinalCltvExpiry": 30,
  "amount": 50000,
  "features": {
    "activated": {
      "payment_secret": "mandatory",
      "basic_mpp": "optional",
      "option_payment_metadata": "optional",
      "var_onion_optin": "mandatory"
    },
    "unknown": []
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
maxFeePct                 | Max percentage to be paid in fees along the payment route (ignored if below `maxFeeFlatSat`)   | Yes      | Integer (between 0 and 100)
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
maxFeePct                 | Max percentage to be paid in fees along the payment route (ignored if below `maxFeeFlatSat`)   | Yes      | Integer (between 0 and 100)
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
maxFeePct                 | Max percentage to be paid in fees along the payment route (ignored if below `maxFeeFlatSat`)   | Yes      | Integer (between 0 and 100)
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
    "id": "c7b83ae7-a8a2-4ac7-9d54-f13826eaaf06",
    "parentId": "e0b98732-4ba5-4992-b1c3-5efb4084bcd3",
    "paymentHash": "e170db22f72678848b90d4d10095e6863c79a39717ccdcfab18106248b93305c",
    "paymentType": "Standard",
    "amount": 2000000,
    "recipientAmount": 5000000,
    "recipientNodeId": "02fe677ac8cd61399d097535a3e8a51a0849e57cdbab9b34796c86f3e33568cbe2",
    "createdAt": {
      "iso": "2022-02-01T12:40:19.309Z",
      "unix": 1643719219
    },
    "invoice": {
      "prefix": "lnbcrt",
      "timestamp": 1643719211,
      "nodeId": "02fe677ac8cd61399d097535a3e8a51a0849e57cdbab9b34796c86f3e33568cbe2",
      "serialized": "lnbcrt50u1pslj23tpp5u9cdkghhyeugfzus6ngsp90xsc78nguhzlxde743syrzfzunxpwqdq809hkcmcsp5tp7xegstgfpyjyg2cqclsthwr330g9g3p0dmn0g6v9t6dn6n9s4smqz9gxqrrsscqp79qtzsqqqqqysgq20s4qnk7xq0dcwjustztkx4ez0mqlmg83s5y6gk4u7ug6qk3cwuxq9ehqn4kyp580gqwp4nxwh598j40pqnlals2m0pem7f0qz0xm8qqe25z82",
      "description": "#reckless",
      "paymentHash": "e170db22f72678848b90d4d10095e6863c79a39717ccdcfab18106248b93305c",
      "paymentMetadata": "2a",
      "expiry": 3600,
      "minFinalCltvExpiry": 30,
      "amount": 5000000,
      "features": {
        "activated": {
          "payment_secret": "mandatory",
          "basic_mpp": "optional",
          "option_payment_metadata": "optional",
          "var_onion_optin": "mandatory"
        },
        "unknown": []
      },
      "routingInfo": []
    },
    "status": {
      "type": "sent",
      "paymentPreimage": "533b360e08d0d7383d0125e3510eaf5d7e36e21b847446cf64a84973800bc48c",
      "feesPaid": 10,
      "route": [
        {
          "nodeId": "03dfefbc942ac877655af00c4a6e9314626438e4aaba141412d825d5f2304bf0bf",
          "nextNodeId": "02f5ce007d2d9ef8a72a03b8e33f63fe9384cea4e71c1de468737611ce3e68ac02",
          "shortChannelId": "538x3x0"
        },
        {
          "nodeId": "02f5ce007d2d9ef8a72a03b8e33f63fe9384cea4e71c1de468737611ce3e68ac02",
          "nextNodeId": "02d150875194d076f662d4252a8dee7077ed4cc4a848bb9f83fb467b6d3c120199",
          "shortChannelId": "538x2x1"
        }
      ],
      "completedAt": {
        "iso": "2022-02-01T12:40:19.438Z",
        "unix": 1643719219
      }
    }
  },
  {
    "id": "83fcc569-917a-4cac-b42d-6f6b186f21eb",
    "parentId": "e0b98732-4ba5-4992-b1c3-5efb4084bcd3",
    "paymentHash": "e170db22f72678848b90d4d10095e6863c79a39717ccdcfab18106248b93305c",
    "paymentType": "Standard",
    "amount": 3000000,
    "recipientAmount": 5000000,
    "recipientNodeId": "02fe677ac8cd61399d097535a3e8a51a0849e57cdbab9b34796c86f3e33568cbe2",
    "createdAt": {
      "iso": "2022-02-01T12:40:19.309Z",
      "unix": 1643719219
    },
    "invoice": {
      "prefix": "lnbcrt",
      "timestamp": 1643719211,
      "nodeId": "02fe677ac8cd61399d097535a3e8a51a0849e57cdbab9b34796c86f3e33568cbe2",
      "serialized": "lnbcrt50u1pslj23tpp5u9cdkghhyeugfzus6ngsp90xsc78nguhzlxde743syrzfzunxpwqdq809hkcmcsp5tp7xegstgfpyjyg2cqclsthwr330g9g3p0dmn0g6v9t6dn6n9s4smqz9gxqrrsscqp79qtzsqqqqqysgq20s4qnk7xq0dcwjustztkx4ez0mqlmg83s5y6gk4u7ug6qk3cwuxq9ehqn4kyp580gqwp4nxwh598j40pqnlals2m0pem7f0qz0xm8qqe25z82",
      "description": "#reckless",
      "paymentHash": "e170db22f72678848b90d4d10095e6863c79a39717ccdcfab18106248b93305c",
      "paymentMetadata": "2a",
      "expiry": 3600,
      "minFinalCltvExpiry": 30,
      "amount": 5000000,
      "features": {
        "activated": {
          "payment_secret": "mandatory",
          "basic_mpp": "optional",
          "option_payment_metadata": "optional",
          "var_onion_optin": "mandatory"
        },
        "unknown": []
      },
      "routingInfo": []
    },
    "status": {
      "type": "sent",
      "paymentPreimage": "533b360e08d0d7383d0125e3510eaf5d7e36e21b847446cf64a84973800bc48c",
      "feesPaid": 15,
      "route": [
        {
          "nodeId": "03dfefbc942ac877655af00c4a6e9314626438e4aaba141412d825d5f2304bf0bf",
          "nextNodeId": "02f5ce007d2d9ef8a72a03b8e33f63fe9384cea4e71c1de468737611ce3e68ac02",
          "shortChannelId": "538x4x1"
        },
        {
          "nodeId": "02f5ce007d2d9ef8a72a03b8e33f63fe9384cea4e71c1de468737611ce3e68ac02",
          "nextNodeId": "02d150875194d076f662d4252a8dee7077ed4cc4a848bb9f83fb467b6d3c120199",
          "shortChannelId": "538x2x1"
        }
      ],
      "completedAt": {
        "iso": "2022-02-01T12:40:19.438Z",
        "unix": 1643719219
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
    "timestamp": 1643719211,
    "nodeId": "02fe677ac8cd61399d097535a3e8a51a0849e57cdbab9b34796c86f3e33568cbe2",
    "serialized": "lnbcrt50u1pslj23tpp5u9cdkghhyeugfzus6ngsp90xsc78nguhzlxde743syrzfzunxpwqdq809hkcmcsp5tp7xegstgfpyjyg2cqclsthwr330g9g3p0dmn0g6v9t6dn6n9s4smqz9gxqrrsscqp79qtzsqqqqqysgq20s4qnk7xq0dcwjustztkx4ez0mqlmg83s5y6gk4u7ug6qk3cwuxq9ehqn4kyp580gqwp4nxwh598j40pqnlals2m0pem7f0qz0xm8qqe25z82",
    "description": "#reckless",
    "paymentHash": "e170db22f72678848b90d4d10095e6863c79a39717ccdcfab18106248b93305c",
    "paymentMetadata": "2a",
    "expiry": 3600,
    "minFinalCltvExpiry": 30,
    "amount": 5000000,
    "features": {
      "activated": {
        "payment_secret": "mandatory",
        "basic_mpp": "optional",
        "option_payment_metadata": "optional",
        "var_onion_optin": "mandatory"
      },
      "unknown": []
    },
    "routingInfo": []
  },
  "paymentPreimage": "533b360e08d0d7383d0125e3510eaf5d7e36e21b847446cf64a84973800bc48c",
  "paymentType": "Standard",
  "createdAt": {
    "iso": "2022-02-01T12:40:11Z",
    "unix": 1643719211
  },
  "status": {
    "type": "received",
    "amount": 5000000,
    "receivedAt": {
      "iso": "2022-02-01T12:40:19.423Z",
      "unix": 1643719219
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
      "timestamp": 1686839336,
      "nodeId": "02ca41676c9dfff08553528b151b1bf82031a26bb1d6f852e0f8075d33fa4ea089",
      "serialized": "lnbcrt500u1pjgkgpgpp5qw2c953gwadm9zevstlq04ffl870tzmjdvg7c03x840xtcdkavgsdq809hkcmcsp5rvgyuy7hmtuc5ljmr645yfntrrwgyumnsp43w60xdrw9gnjprauqmqz9gxqrrsscqp79q7sqqqqqqqqqqqqqqqqqqqsqqqqqysgq7pwcsn9nfrvf46nkmctqnwuj3rvt7erx4494k4sa8uawajyz39sx8jd8t8l3kq4z3w653xu9uqvsjekyu478egx7ftwwzl2m8n7nqagqm7nqs9",
      "description": "yolo",
      "paymentHash": "039582d228775bb28b2c82fe07d529f9fcf58b726b11ec3e263d5e65e1b6eb11",
      "paymentMetadata": "2a",
      "expiry": 3600,
      "minFinalCltvExpiry": 30,
      "amount": 50000000,
      "features": {
        "activated": {
          "trampoline_payment_prototype": "optional",
          "payment_secret": "mandatory",
          "basic_mpp": "optional",
          "option_payment_metadata": "optional",
          "var_onion_optin": "mandatory"
        },
        "unknown": []
      },
      "routingInfo": []
    },
    "paymentPreimage": "f3fcdefcd38481666f624ba68ca17ad620ca8c98bbec5f0616ba11ff11d6096e",
    "paymentType": "Standard",
    "createdAt": {
      "iso": "2023-06-15T14:28:56Z",
      "unix": 1686839336
    },
    "status": {
      "type": "received",
      "amount": 50000000,
      "receivedAt": {
        "iso": "2023-06-15T14:30:32.564Z",
        "unix": 1686839432
      }
    }
  },
  {
    "invoice": {
      "prefix": "lnbcrt",
      "timestamp": 1686839569,
      "nodeId": "02ca41676c9dfff08553528b151b1bf82031a26bb1d6f852e0f8075d33fa4ea089",
      "serialized": "lnbcrt100u1pjgkgg3pp529amjg068drrefp02mxz8907gdnea3jqn6fss7y4zw07uswr2w6sdq809hkcmcsp5njg0whsxvzuqtp5wpzsma0jhphch7r9z45yzjjpg500dpcdqw3csmqz9gxqrrsscqp79q7sqqqqqqqqqqqqqqqqqqqsqqqqqysgq629ldsmfufcerxkc562mh7dz5sr5x68zyhpxhg0qv6qfvhvv7w6yw0gtxqlyu4fw8kzrcd5etu24gy34dv276m3wmf8jfa069m3c4tqqx4dxns",
      "description": "yolo",
      "paymentHash": "517bb921fa3b463ca42f56cc2395fe43679ec6409e93087895139fee41c353b5",
      "paymentMetadata": "2a",
      "expiry": 3600,
      "minFinalCltvExpiry": 30,
      "amount": 10000000,
      "features": {
        "activated": {
          "trampoline_payment_prototype": "optional",
          "payment_secret": "mandatory",
          "basic_mpp": "optional",
          "option_payment_metadata": "optional",
          "var_onion_optin": "mandatory"
        },
        "unknown": []
      },
      "routingInfo": []
    },
    "paymentPreimage": "8c13992095e5ad50ed6675b5f5e87e786fed3cd39209f8ced2e67fadf6567c7b",
    "paymentType": "Standard",
    "createdAt": {
      "iso": "2023-06-15T14:32:49Z",
      "unix": 1686839569
    },
    "status": {
      "type": "received",
      "amount": 10000000,
      "receivedAt": {
        "iso": "2023-06-15T14:32:57.631Z",
        "unix": 1686839577
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
  "timestamp": 1643718891,
  "nodeId": "028e2403fbfddb3d787843361f91adbda64c6f622921b19fb48f5766508bcadb29",
  "serialized": "lnbcrt500n1pslj28tpp55kxmmddatrnmf42a55mk4wzz4ryq8tv2vwrrarj27e0hhjgpscjqdq0ydex2cmtd3jhxucsp5qu6jq5heq4lcjpj2r8gp0sd65860yzc5yw3xrwde6c4m3mlessxsmqz9gxqrrsscqp79qtzsqqqqqysgqr2fy2yz4655hwql2nwkk3t9saxhj80340cxfzf7fwhweasncv77ym7wcv0p54e4kt7jpmfdavnj5urq84syh9t2t49qdgj4ra8jl40gp6ys45n",
  "description": "#reckless",
  "paymentHash": "a58dbdb5bd58e7b4d55da5376ab842a8c803ad8a63863e8e4af65f7bc9018624",
  "paymentMetadata": "2a",
  "expiry": 3600,
  "minFinalCltvExpiry": 30,
  "amount": 50000,
  "features": {
    "activated": {
      "payment_secret": "mandatory",
      "basic_mpp": "optional",
      "option_payment_metadata": "optional",
      "var_onion_optin": "mandatory"
    },
    "unknown": []
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
    "timestamp": 1643719798,
    "nodeId": "028e2403fbfddb3d787843361f91adbda64c6f622921b19fb48f5766508bcadb29",
    "serialized": "lnbcrt1psljtrkpp5gqus3ys83p9cry4nj43ykjyvkuuhrcc5y45a6l569zwuc8pn2xxsdq0ydex2cmtd3jhxucsp543t76xycc9kpx4estwm6tjlpsht3m7d5jxe09tqnyjjux970y9lsmqz9gxqrrsscqp79qtzsqqqqqysgqxwh55ncvj3hv0cypm8vafku83gayzg7qa3zlu3lua76lk53t2m3rgt4d5qa04cfdd0f407p328c9el9xvy3r6z9um90m5pjaxrrazysqfkxfa7",
    "description": "#reckless",
    "paymentHash": "4039089207884b8192b395624b488cb73971e3142569dd7e9a289dcc1c33518d",
    "paymentMetadata": "2a",
    "expiry": 3600,
    "minFinalCltvExpiry": 30,
    "features": {
      "activated": {
        "payment_secret": "mandatory",
        "basic_mpp": "optional",
        "option_payment_metadata": "optional",
        "var_onion_optin": "mandatory"
      },
      "unknown": []
    },
    "routingInfo": []
  },
  {
    "prefix": "lnbcrt",
    "timestamp": 1643719828,
    "nodeId": "028e2403fbfddb3d787843361f91adbda64c6f622921b19fb48f5766508bcadb29",
    "serialized": "lnbcrt1psljty5pp5z2247c5w8cl30err7s9qx8rkejltq49mk8z6l5eqar7l43pehapsdq0ydex2cmtd3jhxucsp5hrcu5s0jftrmje4yavu580tlrq4mdmdevye8aevn6dsae4x5kejqmqz9gxqrrsscqp79qtzsqqqqqysgqwus3au5085tp02cwvpjexc5rq6qjezwxwr3yecdxr525qprv5zjxa98r69kx87cegavjw9u9299yfdhnes7mp4dztttyduchudvq64cq4pyx28",
    "description": "#reckless",
    "paymentHash": "12955f628e3e3f17e463f40a031c76ccbeb054bbb1c5afd320e8fdfac439bf43",
    "paymentMetadata": "2a",
    "expiry": 3600,
    "minFinalCltvExpiry": 30,
    "features": {
      "activated": {
        "payment_secret": "mandatory",
        "basic_mpp": "optional",
        "option_payment_metadata": "optional",
        "var_onion_optin": "mandatory"
      },
      "unknown": []
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
    "timestamp": 1643719798,
    "nodeId": "028e2403fbfddb3d787843361f91adbda64c6f622921b19fb48f5766508bcadb29",
    "serialized": "lnbcrt1psljtrkpp5gqus3ys83p9cry4nj43ykjyvkuuhrcc5y45a6l569zwuc8pn2xxsdq0ydex2cmtd3jhxucsp543t76xycc9kpx4estwm6tjlpsht3m7d5jxe09tqnyjjux970y9lsmqz9gxqrrsscqp79qtzsqqqqqysgqxwh55ncvj3hv0cypm8vafku83gayzg7qa3zlu3lua76lk53t2m3rgt4d5qa04cfdd0f407p328c9el9xvy3r6z9um90m5pjaxrrazysqfkxfa7",
    "description": "#reckless",
    "paymentHash": "4039089207884b8192b395624b488cb73971e3142569dd7e9a289dcc1c33518d",
    "paymentMetadata": "2a",
    "expiry": 3600,
    "minFinalCltvExpiry": 30,
    "features": {
      "activated": {
        "payment_secret": "mandatory",
        "basic_mpp": "optional",
        "option_payment_metadata": "optional",
        "var_onion_optin": "mandatory"
      },
      "unknown": []
    },
    "routingInfo": []
  },
  {
    "prefix": "lnbcrt",
    "timestamp": 1643719828,
    "nodeId": "028e2403fbfddb3d787843361f91adbda64c6f622921b19fb48f5766508bcadb29",
    "serialized": "lnbcrt1psljty5pp5z2247c5w8cl30err7s9qx8rkejltq49mk8z6l5eqar7l43pehapsdq0ydex2cmtd3jhxucsp5hrcu5s0jftrmje4yavu580tlrq4mdmdevye8aevn6dsae4x5kejqmqz9gxqrrsscqp79qtzsqqqqqysgqwus3au5085tp02cwvpjexc5rq6qjezwxwr3yecdxr525qprv5zjxa98r69kx87cegavjw9u9299yfdhnes7mp4dztttyduchudvq64cq4pyx28",
    "description": "#reckless",
    "paymentHash": "12955f628e3e3f17e463f40a031c76ccbeb054bbb1c5afd320e8fdfac439bf43",
    "paymentMetadata": "2a",
    "expiry": 3600,
    "minFinalCltvExpiry": 30,
    "features": {
      "activated": {
        "payment_secret": "mandatory",
        "basic_mpp": "optional",
        "option_payment_metadata": "optional",
        "var_onion_optin": "mandatory"
      },
      "unknown": []
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
skip      | Number of transactions to skip   | No       | Integer

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
      "id": "562c2be9-6d46-4684-bc74-e4a99a77f4fe",
      "paymentHash": "6130a990b87b745474ced86a68c162a57016a406419257c7d7362ab90e2925ec",
      "paymentPreimage": "84192ee8858166740158ff321ebf5325c3097a17e7753876bd59a5a531ce276b",
      "recipientAmount": 111000000,
      "recipientNodeId": "02d150875194d076f662d4252a8dee7077ed4cc4a848bb9f83fb467b6d3c120199",
      "parts": [
        {
          "id": "562c2be9-6d46-4684-bc74-e4a99a77f4fe",
          "amount": 111000000,
          "feesPaid": 12100,
          "toChannelId": "d4eb1fac020d877c73bb75788e23fc70398d6a891bb773f7860481bdba5af04b",
          "timestamp": {
            "iso": "2022-02-01T12:40:19.438Z",
            "unix": 1643719219
          }
        }
      ]
    },
    {
      "type": "payment-sent",
      "id": "cd5666d5-7678-4458-b50b-21b363b34f5e",
      "paymentHash": "931ee191eb98176b401222a17dc9269181714a6a940d057cc0b54fed101fc3cc",
      "paymentPreimage": "a10d43f61016e052dfe946f24b550a37f538d033cc29999d8df10438d2618943",
      "recipientAmount": 90000000,
      "recipientNodeId": "02d150875194d076f662d4252a8dee7077ed4cc4a848bb9f83fb467b6d3c120199",
      "parts": [
        {
          "id": "83fcc569-917a-4cac-b42d-6f6b186f21eb",
          "amount": 4827118,
          "feesPaid": 1482,
          "toChannelId": "67a548c2677702c19533ea9644a89fa54162866a95079a768dd76a182538f53f",
          "timestamp": {
            "iso": "2022-02-01T12:40:19.438Z",
            "unix": 1643719219
          }
        },
        {
          "id": "84c89f34-389f-4d0a-a48a-0eed52e8bcf5",
          "amount": 85172882,
          "feesPaid": 9517,
          "toChannelId": "d4eb1fac020d877c73bb75788e23fc70398d6a891bb773f7860481bdba5af04b",
          "timestamp": {
            "iso": "2022-02-01T12:40:19.438Z",
            "unix": 1643719219
          }
        }
      ]
    }
  ],
  "received": [
    {
      "type": "payment-received",
      "paymentHash": "931ee191eb98176b401222a17dc9269181714a6a940d057cc0b54fed101fc3cc",
      "parts": [
        {
          "amount": 4827118,
          "fromChannelId": "10eeb6d8cfd8c3f6a93d22e2cd8adf5e36bdb43c53405ddc4fc17a0f7608162a",
          "timestamp": {
            "iso": "2022-02-01T12:40:19.438Z",
            "unix": 1643719219
          }
        },
        {
          "amount": 85172882,
          "fromChannelId": "1b14940e98238a84b7a9f0429571ba8ca1a4da3ba5699a3f0082a16761f9bd6f",
          "timestamp": {
            "iso": "2022-02-01T12:40:19.438Z",
            "unix": 1643719219
          }
        }
      ]
    }
  ],
  "relayed": [
    {
      "type": "payment-relayed",
      "amountIn": 111012100,
      "amountOut": 111000000,
      "paymentHash": "6130a990b87b745474ced86a68c162a57016a406419257c7d7362ab90e2925ec",
      "fromChannelId": "d4eb1fac020d877c73bb75788e23fc70398d6a891bb773f7860481bdba5af04b",
      "toChannelId": "10eeb6d8cfd8c3f6a93d22e2cd8adf5e36bdb43c53405ddc4fc17a0f7608162a",
      "timestamp": {
        "iso": "2022-02-01T12:40:19.438Z",
        "unix": 1643719219
      }
    },
    {
      "type": "payment-relayed",
      "amountIn": 45005500,
      "amountOut": 45000000,
      "paymentHash": "c5bd76b696d75b2e548a00f14e30cd694cfb3790e095ab62303abe48f2b3a263",
      "fromChannelId": "d4eb1fac020d877c73bb75788e23fc70398d6a891bb773f7860481bdba5af04b",
      "toChannelId": "10eeb6d8cfd8c3f6a93d22e2cd8adf5e36bdb43c53405ddc4fc17a0f7608162a",
      "timestamp": {
        "iso": "2022-02-01T12:40:19.438Z",
        "unix": 1643719219
      }
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

## NetworkFees

```shell
curl -s -u :<eclair_api_password> -X POST "http://localhost:8080/networkfees"

# with eclair-cli
eclair-cli networkfees
```

> The above command returns:

```json
[
  {
    "remoteNodeId": "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
    "channelId": "57d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e",
    "txId": "0e7d63ce98dbaccd9c3061509e93b45adbeaf10997c4708213804da0edd6d757",
    "fee": 3382,
    "txType": "funding",
    "timestamp": {
      "iso": "2022-02-01T12:27:18.932Z",
      "unix": 1643718438
    }
  }
]
```

Retrieves information about on-chain fees paid during channel operations (currency values are in satoshis).

### HTTP Request

`POST http://localhost:8080/networkfees`

### Parameters

Parameter | Description                                           | Optional | Type
--------- | ----------------------------------------------------- | -------- | -----------------------------------
from      | Filters elements no older than this unix-timestamp    | Yes      | Unix timestamp in seconds (Integer)
to        | Filters elements no younger than this unix-timestamp  | Yes      | Unix timestamp in seconds (Integer)

## ChannelStats

```shell
curl -s -u :<eclair_api_password> -X POST "http://localhost:8080/channelstats"

# with eclair-cli
eclair-cli channelstats
```

> The above command returns:

```json
[
  {
    "channelId": "1b14940e98238a84b7a9f0429571ba8ca1a4da3ba5699a3f0082a16761f9bd6f",
    "direction": "IN",
    "avgPaymentAmount": 0,
    "paymentCount": 0,
    "relayFee": 0,
    "networkFee": 26400
  },
  {
    "channelId": "1b14940e98238a84b7a9f0429571ba8ca1a4da3ba5699a3f0082a16761f9bd6f",
    "direction": "OUT",
    "avgPaymentAmount": 85172,
    "paymentCount": 1,
    "relayFee": 9,
    "networkFee": 26400
  },
  {
    "channelId": "10eeb6d8cfd8c3f6a93d22e2cd8adf5e36bdb43c53405ddc4fc17a0f7608162a",
    "direction": "IN",
    "avgPaymentAmount": 0,
    "paymentCount": 0,
    "relayFee": 0,
    "networkFee": 26400
  },
  {
    "channelId": "10eeb6d8cfd8c3f6a93d22e2cd8adf5e36bdb43c53405ddc4fc17a0f7608162a",
    "direction": "OUT",
    "avgPaymentAmount": 53609,
    "paymentCount": 3,
    "relayFee": 19,
    "networkFee": 26400
  }
]
```

Retrieves information about local channels. The information is then aggregated in order to display
statistics about the routing activity of the channels. Values are in Satoshis.

### HTTP Request

`POST http://localhost:8080/channelstats`

### Parameters

Parameter | Description                                           | Optional | Type
--------- | ----------------------------------------------------- | -------- | -----------------------------------
from      | Filters elements no older than this unix-timestamp    | Yes      | Unix timestamp in seconds (Integer)
to        | Filters elements no younger than this unix-timestamp  | Yes      | Unix timestamp in seconds (Integer)
count     | Limits the number of results returned                 | Yes      | Integer
skip      | Skip some number of results                           | Yes      | Integer

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
    "shortIds": {
      "real": {
        "status": "final",
        "realScid": "562890x809x0"
      },
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
    "shortIds": {
      "real": {
        "status": "final",
        "realScid": "562890x809x0"
      },
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
    "shortIds": {
      "real": {
        "status": "final",
        "realScid": "562890x809x1"
      },
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
  "amountIn": 21,
  "amountOut": 20,
  "paymentHash": "6130a990b87b745474ced86a68c162a57016a406419257c7d7362ab90e2925ec",
  "fromChannelId": "d4eb1fac020d877c73bb75788e23fc70398d6a891bb773f7860481bdba5af04b",
  "toChannelId": "10eeb6d8cfd8c3f6a93d22e2cd8adf5e36bdb43c53405ddc4fc17a0f7608162a",
  "timestamp": {
    "iso": "2022-02-01T12:40:19.438Z",
    "unix": 1643719219
  }
}
```

> Payment received event

```json
{
  "type": "payment-received",
  "paymentHash": "931ee191eb98176b401222a17dc9269181714a6a940d057cc0b54fed101fc3cc",
  "parts": [
    {
      "amount": 21,
      "fromChannelId": "10eeb6d8cfd8c3f6a93d22e2cd8adf5e36bdb43c53405ddc4fc17a0f7608162a",
      "timestamp": {
            "iso": "2022-02-01T12:40:19.438Z",
            "unix": 1643719219
      }
    },
    {
      "amount": 24,
      "fromChannelId": "1b14940e98238a84b7a9f0429571ba8ca1a4da3ba5699a3f0082a16761f9bd6f",
      "timestamp": {
        "iso": "2022-02-01T12:40:19.438Z",
        "unix": 1643719219
      }
    }
  ]
}
```

> Payment failed event

```json
{
   "type": "payment-failed",
   "id": "487da196-a4dc-4b1e-92b4-3e5e905e9f3f",
   "paymentHash": "0000000000000000000000000000000000000000000000000000000000000000",
   "failures": [
     {
       "failureType": "Local",
       "failureMessage": "balance too low",
       "failedRoute": []
     }
   ],
   "timestamp": {
     "iso": "2022-02-01T12:40:19.438Z",
     "unix": 1643719219
   }
}
```

> Payment sent event

```json
{
  "type": "payment-sent",
  "id": "562c2be9-6d46-4684-bc74-e4a99a77f4fe",
  "paymentHash": "6130a990b87b745474ced86a68c162a57016a406419257c7d7362ab90e2925ec",
  "paymentPreimage": "84192ee8858166740158ff321ebf5325c3097a17e7753876bd59a5a531ce276b",
  "recipientAmount": 45,
  "recipientNodeId": "02d150875194d076f662d4252a8dee7077ed4cc4a848bb9f83fb467b6d3c120199",
  "parts": [
    {
      "id": "b8799834-8db9-460b-b754-2942f20e3500",
      "amount": 21,
      "feesPaid": 1,
      "toChannelId": "d4eb1fac020d877c73bb75788e23fc70398d6a891bb773f7860481bdba5af04b",
      "timestamp": {
        "iso": "2022-02-01T12:40:19.438Z",
        "unix": 1643719219
      }
    },
    {
      "id": "ab348eb7-b0ed-46ff-9274-28cfdbdaae8d",
      "amount": 24,
      "feesPaid": 3,
      "toChannelId": "d4eb1fac020d877c73bb75788e23fc70398d6a891bb773f7860481bdba5af04b",
      "timestamp": {
        "iso": "2022-02-01T12:40:19.438Z",
        "unix": 1643719219
      }
    }
  ]
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

> Channel opened event

```json
{
  "type": "channel-opened",
  "remoteNodeId": "02d150875194d076f662d4252a8dee7077ed4cc4a848bb9f83fb467b6d3c120199",
  "channelId": "d4eb1fac020d877c73bb75788e23fc70398d6a891bb773f7860481bdba5af04b"
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
  "closingType": "MutualClose"
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
channel-opened           | A channel opening flow has completed
channel-state-changed    | A channel state changed (e.g. going from offline to connected)
channel-closed           | A channel has been closed
onion-message-received   | An onion message was received

### HTTP Request

`GET ws://localhost:8080/ws`
