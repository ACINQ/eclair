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
  "version": "0.7.0-a804905",
  "nodeId": "028e2403fbfddb3d787843361f91adbda64c6f622921b19fb48f5766508bcadb29",
  "alias": "ACINQ",
  "color": "#49daaa",
  "features": {
    "activated": {
      "option_onion_messages": "optional",
      "gossip_queries_ex": "optional",
      "option_payment_metadata": "optional",
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
  "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
  "network": "regtest",
  "blockHeight": 2898,
  "publicAddresses": [
    "34.239.230.56:9735",
    "of7husrflx7sforh3fw6yqlpwstee3wg5imvvmkp4bz6rbjxtg5nljad.onion:9735"
  ],
  "instanceId": "155de87c-c996-44bb-99d3-c4f01eebd250"
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
disconnecting
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

Parameter      | Description                                         | Optional | Type
-------------- | --------------------------------------------------- | -------- | ---------------------------
channelId      | The channelId of the channel you want to close      | No       | 32-bytes-HexString (String)
shortChannelId | The shortChannelId of the channel you want to close | Yes      | ShortChannelId (String)
channelIds     | List of channelIds to force-close                   | Yes      | CSV or JSON list of channelId
shortChannelIds| List of shortChannelIds to force-close              | Yes      | CSV or JSON list of shortChannelId

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
    "nodeId": "02ca41676c9dfff08553528b151b1bf82031a26bb1d6f852e0f8075d33fa4ea089",
    "channelId": "79ac97e88aacb9671cef9c75f9d53ef7c534a74c9f7bd65eb95a808bb6845efb",
    "state": "WAIT_FOR_DUAL_FUNDING_CONFIRMED",
    "data": {
      "type": "DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED",
      "commitments": {
        "params": {
          "channelId": "79ac97e88aacb9671cef9c75f9d53ef7c534a74c9f7bd65eb95a808bb6845efb",
          "channelConfig": [
            "funding_pubkey_based_channel_keypath"
          ],
          "channelFeatures": [
            "option_static_remotekey",
            "option_anchors_zero_fee_htlc_tx",
            "option_dual_fund"
          ],
          "localParams": {
            "nodeId": "0270508d93eef07ddc0e19a0da981a273c419b2f7403a708fd0618e9a3f2e97ae2",
            "fundingKeyPath": [
              3351215530,
              3592259429,
              613400752,
              3326162843,
              1298359959,
              1819748777,
              1053912551,
              2174366698,
              2147483649
            ],
            "dustLimit": 546,
            "maxHtlcValueInFlightMsat": 300000000,
            "htlcMinimum": 1,
            "toSelfDelay": 120,
            "maxAcceptedHtlcs": 30,
            "isInitiator": true,
            "initFeatures": {
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
            }
          },
          "remoteParams": {
            "nodeId": "02ca41676c9dfff08553528b151b1bf82031a26bb1d6f852e0f8075d33fa4ea089",
            "dustLimit": 546,
            "maxHtlcValueInFlightMsat": 300000000,
            "htlcMinimum": 1,
            "toSelfDelay": 120,
            "maxAcceptedHtlcs": 30,
            "revocationBasepoint": "0219c9cbea7e1bc1ccdb74ea9f36740b59795532223d2fd3dfee76771bdce28c05",
            "paymentBasepoint": "036b53ff80de6d3f6f46f98aab0027632648a9da321fc8935dff3c1d6babd65ff0",
            "delayedPaymentBasepoint": "026946d392806333bc1c81f78fecbc9b1c62c8f2700f4e63df6f197ffd46f08fe0",
            "htlcBasepoint": "034b3158f2b5d20983bd080ddaf7a42aaba43fb4d993d3b83c6a84935325edaeed",
            "initFeatures": {
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
            }
          },
          "channelFlags": {
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
            "fundingTx": {
              "outPoint": "bc2b8db55b9588d3a18bd06bd0e284f63ee8cc149c63138d51ac8ef81a72fc6f:1",
              "amountSatoshis": 300000
            },
            "localFunding": {
              "status": "unconfirmed",
              "txid": "bc2b8db55b9588d3a18bd06bd0e284f63ee8cc149c63138d51ac8ef81a72fc6f"
            },
            "remoteFunding": {
              "status": "not-locked"
            },
            "localCommit": {
              "index": 0,
              "spec": {
                "htlcs": [],
                "commitTxFeerate": 1000,
                "toLocal": 300000000,
                "toRemote": 0
              },
              "commitTxAndRemoteSig": {
                "commitTx": {
                  "txid": "0a2ae74dc8b4a0237f5bace62ec598b6a8ac54248b8c1b6a2faf2bd4c3475146",
                  "tx": "02000000016ffc721af88eac518d13639c14cce83ef684e2d06bd08ba1d388955bb58d2bbc01000000000d884680024a0100000000000022002040a846ed0af5668f92035af69d9533090fba4ff220fdf763cb33b39c286c8b03e88c040000000000220020aa0e40851424e7770d899b0fd9200115e21ebcbbc07a700dbb8b6287b3d103e6ac09af20"
                },
                "remoteSig": "575a04b657e350d59e6bb3e97c3f43734cb8370056c754723c9ef49a672eb8780b80b716fc4a88166c4c4091f583627fe63f0dbbc6e6a3f44ef1d5f24094068a"
              },
              "htlcTxsAndRemoteSigs": []
            },
            "remoteCommit": {
              "index": 0,
              "spec": {
                "htlcs": [],
                "commitTxFeerate": 1000,
                "toLocal": 0,
                "toRemote": 300000000
              },
              "txid": "3a8c5e919bd8af19ee0211024603cdda754dcf232abb4fe530364e58de2f9182",
              "remotePerCommitmentPoint": "02a65c872392729f53069c05933ba3f6c705d4ab8296c0704b58f96bc29b30e38f"
            }
          }
        ],
        "inactive": [],
        "remoteNextCommitInfo": "03e41546ef474c71fba0a3d1076ed11d7ef37a2dd60d320ac008fa4780ebdf4b34",
        "remotePerCommitmentSecrets": null,
        "originChannels": {}
      },
      "localPushAmount": 0,
      "remotePushAmount": 0,
      "waitingSince": 2937,
      "lastChecked": 2937,
      "rbfStatus": {}
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
  "nodeId": "03165df3ac20288e65d03abd37bf81d6c083a4f0ff31134e687a04902a1199911a",
  "channelId": "c0b52c3ca0cb0afe5df7f65a5a414721d4188cbba6d5e4d0439f2b4705f83348",
  "state": "NORMAL",
  "data": {
    "type": "DATA_NORMAL",
    "commitments": {
      "params": {
        "channelId": "c0b52c3ca0cb0afe5df7f65a5a414721d4188cbba6d5e4d0439f2b4705f83348",
        "channelConfig": [
          "funding_pubkey_based_channel_keypath"
        ],
        "channelFeatures": [
          "option_static_remotekey",
          "option_anchors_zero_fee_htlc_tx",
          "option_dual_fund"
        ],
        "localParams": {
          "nodeId": "0306f3f07f330f46cb3bedb04076d42b70dfd553a7930cd2bded1b7b2a43a311f6",
          "fundingKeyPath": [
            2692191770,
            1757676682,
            841703041,
            3662853700,
            2265691419,
            950074092,
            1082471975,
            1577293046,
            2147483649
          ],
          "dustLimit": 546,
          "maxHtlcValueInFlightMsat": 300000000,
          "htlcMinimum": 1,
          "toSelfDelay": 120,
          "maxAcceptedHtlcs": 30,
          "isInitiator": true,
          "initFeatures": {
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
          }
        },
        "remoteParams": {
          "nodeId": "03165df3ac20288e65d03abd37bf81d6c083a4f0ff31134e687a04902a1199911a",
          "dustLimit": 546,
          "maxHtlcValueInFlightMsat": 300000000,
          "htlcMinimum": 1,
          "toSelfDelay": 120,
          "maxAcceptedHtlcs": 30,
          "revocationBasepoint": "035010c0b4c47b93116c09782da2f769be6494465e248d27a62c0464b99d3418e6",
          "paymentBasepoint": "0285d65d2f38669e62124221e378119aea2921e804c6eb6ebb16e4b75ed8e66994",
          "delayedPaymentBasepoint": "03a230276c1ec93aa07a81e136af13e00337a6bb3bfe1f3a971622231cfa60d723",
          "htlcBasepoint": "0234bd658d5df137772c9ebd6b3facb0ccdfe7a637b806247bde8cd274d6a2acf2",
          "initFeatures": {
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
          }
        },
        "channelFlags": {
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
          "fundingTx": {
            "outPoint": "353a02f74310b337c944a9be2447e8b5039c2c5b8bbd3cdbe4ce31a15ec748de:0",
            "amountSatoshis": 300000
          },
          "localFunding": {
            "status": "confirmed",
            "txid": "353a02f74310b337c944a9be2447e8b5039c2c5b8bbd3cdbe4ce31a15ec748de"
          },
          "remoteFunding": {
            "status": "not-locked"
          },
          "localCommit": {
            "index": 0,
            "spec": {
              "htlcs": [],
              "commitTxFeerate": 1000,
              "toLocal": 300000000,
              "toRemote": 0
            },
            "commitTxAndRemoteSig": {
              "commitTx": {
                "txid": "83d4f64bd3f7708caad602de0c372a94fcdc50f128519c9505169013215f598f",
                "tx": "0200000001de48c75ea131cee4db3cbd8b5b2c9c03b5e84724bea944c937b31043f7023a350000000000c19b5c80024a01000000000000220020f926e1ccf83d80a6177b8e30724327334afc9a40bc629c05b2f0a054b53cc50be88c0400000000002200200c80e4ed598d1881a80cf32617767f3d6dcaa9267a688b9a8be4bc757373047003bc0920"
              },
              "remoteSig": "b2d74aee3a461f14b25fc7b0007de10d307531e5ca5da9a9fac33afa45167d2415095aa63d9586045ee8f2b9e9f8d0c02981323df2941c6b8e4257f1720dc164"
            },
            "htlcTxsAndRemoteSigs": []
          },
          "remoteCommit": {
            "index": 0,
            "spec": {
              "htlcs": [],
              "commitTxFeerate": 1000,
              "toLocal": 0,
              "toRemote": 300000000
            },
            "txid": "d02333c4afa780abd6c4309bbe74bddaebc7adcccaa1c3d0f11e759b06fe0d23",
            "remotePerCommitmentPoint": "027d8c042453d906728fef52a0315673c722aae6e52dd01bc7329a89f98c699c4c"
          }
        }
      ],
      "inactive": [],
      "remoteNextCommitInfo": "0313f94b69957a896e30eef620ed79b380760a854ea2f5e8267c1adfe8c79d8cb2",
      "remotePerCommitmentSecrets": null,
      "originChannels": {}
    },
    "shortIds": {
      "real": {
        "status": "final",
        "realScid": "2958x8x0"
      },
      "localAlias": "0x331e494e9dea08d",
      "remoteAlias": "0xaaddf13a62aeec"
    },
    "channelAnnouncement": {
      "nodeSignature1": "f1b38cb705b84cba86cbd88a86a05e648b598435507a5898bb195ae581b0167c22bb74f9efe68a9d296f6980cda9fc349e94cd84b5fbcd703e4080cdc77b61b5",
      "nodeSignature2": "ef0831962a901f875384d39d0fd0470c9a96a52503f8c9a3d9a04bc8651fa1e93004923f64ca94855bce1779b4ded2b1b4f74fb1c6baf5f233ec23ddddd22e3a",
      "bitcoinSignature1": "0366a7f14694815bace71ea538ff10cdfb66bca979fcce99276e42b711ba7ec8750406bf5ac196dd3c884a07e8dd03fdf3935724cc42ceb96e251b7c01193a38",
      "bitcoinSignature2": "588a47fad2d9be9fe5c5ded60181592e595bae5d02a3403f495c4db45eaf2cf965d43bbed42866be9b3e153a561b9b637260e0b8cb8ddd2986a3d7eabb87231c",
      "features": {
        "activated": {},
        "unknown": []
      },
      "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
      "shortChannelId": "2958x8x0",
      "nodeId1": "0306f3f07f330f46cb3bedb04076d42b70dfd553a7930cd2bded1b7b2a43a311f6",
      "nodeId2": "03165df3ac20288e65d03abd37bf81d6c083a4f0ff31134e687a04902a1199911a",
      "bitcoinKey1": "02719e30ccd9ea88a641216ec247d28b3ee9db9fe4870da59e0ae3b285efb239ab",
      "bitcoinKey2": "03d4ff9a5b97ae3e5e1e8f35084c30c5d2ec32c5f40f444f96408fc1bd3334a8bd",
      "tlvStream": {}
    },
    "channelUpdate": {
      "signature": "89aadc5f063b88b3f0bf44f70e4e3075feda383ed5e327d6fb341048fe89720664b6f6156d06748910b976b23e6fab9a2635e4ee69fab953f61ba8579b39fb57",
      "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
      "shortChannelId": "2958x8x0",
      "timestamp": {
        "iso": "2023-06-16T12:28:48Z",
        "unix": 1686918528
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
      "feeBaseMsat": 1000,
      "feeProportionalMillionths": 200,
      "htlcMaximumMsat": 300000000,
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
    "nodeId": "02ca41676c9dfff08553528b151b1bf82031a26bb1d6f852e0f8075d33fa4ea089",
    "channelId": "79ac97e88aacb9671cef9c75f9d53ef7c534a74c9f7bd65eb95a808bb6845efb",
    "state": "CLOSED",
    "data": {
      "type": "DATA_CLOSING",
      "commitments": {
        "params": {
          "channelId": "79ac97e88aacb9671cef9c75f9d53ef7c534a74c9f7bd65eb95a808bb6845efb",
          "channelConfig": [
            "funding_pubkey_based_channel_keypath"
          ],
          "channelFeatures": [
            "option_static_remotekey",
            "option_anchors_zero_fee_htlc_tx",
            "option_dual_fund"
          ],
          "localParams": {
            "nodeId": "0270508d93eef07ddc0e19a0da981a273c419b2f7403a708fd0618e9a3f2e97ae2",
            "fundingKeyPath": [
              3351215530,
              3592259429,
              613400752,
              3326162843,
              1298359959,
              1819748777,
              1053912551,
              2174366698,
              2147483649
            ],
            "dustLimit": 546,
            "maxHtlcValueInFlightMsat": 300000000,
            "htlcMinimum": 1,
            "toSelfDelay": 120,
            "maxAcceptedHtlcs": 30,
            "isInitiator": true,
            "initFeatures": {
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
            }
          },
          "remoteParams": {
            "nodeId": "02ca41676c9dfff08553528b151b1bf82031a26bb1d6f852e0f8075d33fa4ea089",
            "dustLimit": 546,
            "maxHtlcValueInFlightMsat": 300000000,
            "htlcMinimum": 1,
            "toSelfDelay": 120,
            "maxAcceptedHtlcs": 30,
            "revocationBasepoint": "0219c9cbea7e1bc1ccdb74ea9f36740b59795532223d2fd3dfee76771bdce28c05",
            "paymentBasepoint": "036b53ff80de6d3f6f46f98aab0027632648a9da321fc8935dff3c1d6babd65ff0",
            "delayedPaymentBasepoint": "026946d392806333bc1c81f78fecbc9b1c62c8f2700f4e63df6f197ffd46f08fe0",
            "htlcBasepoint": "034b3158f2b5d20983bd080ddaf7a42aaba43fb4d993d3b83c6a84935325edaeed",
            "initFeatures": {
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
            }
          },
          "channelFlags": {
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
          "localNextHtlcId": 2,
          "remoteNextHtlcId": 0
        },
        "active": [
          {
            "fundingTxIndex": 0,
            "fundingTx": {
              "outPoint": "bc2b8db55b9588d3a18bd06bd0e284f63ee8cc149c63138d51ac8ef81a72fc6f:1",
              "amountSatoshis": 300000
            },
            "localFunding": {
              "status": "confirmed",
              "txid": "bc2b8db55b9588d3a18bd06bd0e284f63ee8cc149c63138d51ac8ef81a72fc6f"
            },
            "remoteFunding": {
              "status": "not-locked"
            },
            "localCommit": {
              "index": 4,
              "spec": {
                "htlcs": [],
                "commitTxFeerate": 1000,
                "toLocal": 240000000,
                "toRemote": 60000000
              },
              "commitTxAndRemoteSig": {
                "commitTx": {
                  "txid": "ef3bbdad7489c01d417ec1ecf77a3ab6d8dd59c6ddb275bd326507695485e766",
                  "tx": "02000000016ffc721af88eac518d13639c14cce83ef684e2d06bd08ba1d388955bb58d2bbc01000000000d884680044a010000000000002200200c7527f8ead2ff0497101ac3d02238b102d4c1a033cbf10facba728b6a1872074a0100000000000022002040a846ed0af5668f92035af69d9533090fba4ff220fdf763cb33b39c286c8b0360ea000000000000220020015c689c9027294248985fc31be6925ce63b42631363aa3d29ecfa9158f25be088a2030000000000220020c5494bec94a822f969fd83ed98ae5af352cc262d9322ad60b89272936b43551aa809af20"
                },
                "remoteSig": "c2db9908c691e2c46d450e2de524a1814987f2299edaa53b1a80865051614b683f06de9210a6bcc40710f142377aebfc84d2be23abc6647790620d413e3ff904"
              },
              "htlcTxsAndRemoteSigs": []
            },
            "remoteCommit": {
              "index": 4,
              "spec": {
                "htlcs": [],
                "commitTxFeerate": 1000,
                "toLocal": 60000000,
                "toRemote": 240000000
              },
              "txid": "0accbc94bcea31b50f3a7c522d19040847f3f1b9531b8824fe0fe3c7e16cf144",
              "remotePerCommitmentPoint": "03234df4757baf2b01a3007a293f4dcc6cd011ef84bd0560394ba8fb4d4d7274a7"
            }
          }
        ],
        "inactive": [],
        "remoteNextCommitInfo": "0316ea351e99a58c2d615cefd11a727b68dd27f671a5498646391125a33f116a28",
        "remotePerCommitmentSecrets": null,
        "originChannels": {}
      },
      "waitingSince": 2947,
      "finalScriptPubKey": "00145c8524cb1f43f202ca190299cee714b04ee1d945",
      "mutualCloseProposed": [
        {
          "txid": "bcc0f355a34bd95114da1b6c24ff922d661b17ebbb2aa6ad9b4c9e3467a2436c",
          "tx": "02000000016ffc721af88eac518d13639c14cce83ef684e2d06bd08ba1d388955bb58d2bbc0100000000ffffffff0260ea00000000000016001429650c6daa955bbdb80cf958d2ff897c05a62bf5dba70300000000001600145c8524cb1f43f202ca190299cee714b04ee1d94500000000",
          "toLocalOutput": {
            "index": 1,
            "amount": 239579,
            "publicKeyScript": "00145c8524cb1f43f202ca190299cee714b04ee1d945"
          }
        }
      ],
      "mutualClosePublished": [
        {
          "txid": "bcc0f355a34bd95114da1b6c24ff922d661b17ebbb2aa6ad9b4c9e3467a2436c",
          "tx": "020000000001016ffc721af88eac518d13639c14cce83ef684e2d06bd08ba1d388955bb58d2bbc0100000000ffffffff0260ea00000000000016001429650c6daa955bbdb80cf958d2ff897c05a62bf5dba70300000000001600145c8524cb1f43f202ca190299cee714b04ee1d945040047304402200d454f917fe81fcc4dcb70e7e043d8cd434a8480a5a5cd5bd55f7ca4b22798cc022057bb5222b76be3757951f7db3ef92862daf993ab27a7202a61090554ae1e16b5014730440220190c0b43f5366bc23f829b6e8a6f92face695060453fa36fd80852c3fe4b7014022005bfb26d025097d2ec3c1d860c892b67e47f3690a2148f2963f1c10e628dc9bf014752210358e87db903725db6c273c8e6efa58e7369e55184fa0635ea19e6525e4193a52d210388c6194aaaadf7a1cb53a7b55c64b56841f11ddd764cb2ca43b3cf78b71fe50252ae00000000",
          "toLocalOutput": {
            "index": 1,
            "amount": 239579,
            "publicKeyScript": "00145c8524cb1f43f202ca190299cee714b04ee1d945"
          }
        }
      ],
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
"lnoxxx"
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
  "lnoxxxxxx1",
  "lnoxxxxxx2"
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

Disable a **BOLT12** offer, which means that future payments to this offer will be rejected.

### HTTP Request

`POST http://localhost:8080/disableoffer`

### Parameters

Parameter          | Description                      | Optional | Type
------------------ | -------------------------------- | -------- | -------
offer              | Offer that should be disabled    | No       | String

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
  "total": 1.90442161,
  "onChain": {
    "confirmed": 1.89997146,
    "unconfirmed": 0
  },
  "offChain": {
    "waitForFundingConfirmed": 0,
    "waitForChannelReady": 0,
    "normal": {
      "toLocal": 0.00445015,
      "htlcs": 0
    },
    "shutdown": {
      "toLocal": 0,
      "htlcs": 0
    },
    "negotiating": 0,
    "closing": {
      "localCloseBalance": {
        "toLocal": {},
        "htlcs": {},
        "htlcsUnpublished": 0
      },
      "remoteCloseBalance": {
        "toLocal": {},
        "htlcs": {},
        "htlcsUnpublished": 0
      },
      "mutualCloseBalance": {
        "toLocal": {}
      },
      "unknownCloseBalance": {
        "toLocal": 0,
        "htlcs": 0
      }
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
  "isInitiator": true,
  "temporaryChannelId": "d4eb1fac020d877c73bb75788e23fc70398d6a891bb773f7860481bdba5af04b",
  "initialFeeratePerKw": 1200,
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
