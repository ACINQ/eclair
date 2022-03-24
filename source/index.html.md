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
curl -s -u :<eclair_api_password> -X POST -F nodeId=<node_id> -F fundingSatoshis=<funding_satoshis> "http://localhost:8080/open"

# with eclair-cli
eclair-cli open --nodeId=<node_id> --fundingSatoshis=<funding_satoshis>
```

> The above command returns the channelId of the newly created channel:

```shell
created channel e872f515dc5d8a3d61ccbd2127f33141eaa115807271dcc5c5c727f3eca914d3
```

Open a channel to another lightning node. You must specify the target **nodeId** and the funding satoshis for the new channel. Optionally
you can send to the remote a _pushMsat_ value and you can specify whether this should be a public or private channel (default is set in the config).

If you already have another channel to the same node, the routing fees that will be used for this new channel will be the same as your existing channel.
Otherwise the values from `eclair.conf` will be used (see `eclair.relay.fees` in your `eclair.conf`).

If you want to override the routing fees that will be used, you must use the `updaterelayfee` API before opening the channel.

### HTTP Request

`POST http://localhost:8080/open`

### Parameters

Parameter             | Description                                                                | Optional | Type
--------------------- | -------------------------------------------------------------------------- | -------- | ---------------------------
nodeId                | The **nodeId** of the node you want to open a channel with                 | No       | 33-bytes-HexString (String)
fundingSatoshis       | Amount of satoshis to spend in the funding of the channel                  | No       | Satoshis (Integer)
channelType           | Channel type (standard, static_remotekey, anchor_outputs_zero_fee_htlc_tx) | Yes      | String
pushMsat              | Amount of millisatoshi to unilaterally push to the counterparty            | Yes      | Millisatoshis (Integer)
fundingFeerateSatByte | Feerate in sat/byte to apply to the funding transaction                    | Yes      | Satoshis (Integer)
announceChannel       | True for public channels, false otherwise                                  | Yes      | Boolean
openTimeoutSeconds    | Timeout for the operation to complete                                      | Yes      | Seconds (Integer)

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
    "nodeId": "02fe677ac8cd61399d097535a3e8a51a0849e57cdbab9b34796c86f3e33568cbe2",
    "channelId": "c7234b117a9186642acce93f544b96d59f2efd5990207dfe90b53a5d32523e33",
    "state": "NORMAL",
    "data": {
      "type": "DATA_NORMAL",
      "commitments": {
        "channelId": "c7234b117a9186642acce93f544b96d59f2efd5990207dfe90b53a5d32523e33",
        "channelConfig": [
          "funding_pubkey_based_channel_keypath"
        ],
        "channelFeatures": [
          "option_static_remotekey",
          "option_anchors_zero_fee_htlc_tx",
          "option_support_large_channel"
        ],
        "localParams": {
          "nodeId": "028e2403fbfddb3d787843361f91adbda64c6f622921b19fb48f5766508bcadb29",
          "fundingKeyPath": {
            "path": [
              1227691183,
              3473391500,
              3678278473,
              1667538943,
              1421651800,
              2779071154,
              3419774729,
              1449096471,
              2147483649
            ]
          },
          "dustLimit": 546,
          "maxHtlcValueInFlightMsat": 5000000000,
          "channelReserve": 4500,
          "htlcMinimum": 1,
          "toSelfDelay": 120,
          "maxAcceptedHtlcs": 30,
          "isFunder": true,
          "defaultFinalScriptPubKey": "00144f5f4c215f8143d5a1d4c122256b17ffde12ae31",
          "initFeatures": {
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
          }
        },
        "remoteParams": {
          "nodeId": "02fe677ac8cd61399d097535a3e8a51a0849e57cdbab9b34796c86f3e33568cbe2",
          "dustLimit": 546,
          "maxHtlcValueInFlightMsat": 5000000000,
          "channelReserve": 4500,
          "htlcMinimum": 1,
          "toSelfDelay": 120,
          "maxAcceptedHtlcs": 30,
          "fundingPubKey": "0362061b316eacd60453158ee93a5d7d6b89b91986766203703c6417a0aae779e1",
          "revocationBasepoint": "02aebb868790a2e828480baf47eaa0c1c1cc8d23d1a5b0b0e97e54ae07760f946e",
          "paymentBasepoint": "038def9c5eb51875cf85a51bd32ed0b887556a1ec5f1c1022542bd93c0b23d9bd1",
          "delayedPaymentBasepoint": "0270443229941bb3dc0ae1b91a436081552ba37a34d7d742d2a67294607e1bc406",
          "htlcBasepoint": "02eb8e172e4ab45f3ec3c8171a704d6eb4d662d1cdcc81417812b14c4abb99b3c0",
          "initFeatures": {
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
          }
        },
        "channelFlags": {
          "announceChannel": true
        },
        "localCommit": {
          "index": 0,
          "spec": {
            "htlcs": [],
            "commitTxFeerate": 1875,
            "toLocal": 450000000,
            "toRemote": 0
          },
          "commitTxAndRemoteSig": {
            "commitTx": {
              "txid": "3d9411672ea52a527ac951b3b1d19b4828cce679a7030ce09ee18568cc4eab8f",
              "tx": "0200000001c7234b117a9186642acce93f544b96d59f2efd5990207dfe90b53a5d32523e32010000000091a97580024a010000000000002200204aa60e3e77527760f4f69b9da09ad3722a51638ceaccfe63f20a33c5297d1c4c01d306000000000022002080acc9c29cfe9b1ad983e34ce4dacabeb30a1d6fc1c66e138ccc90ca9498cb8a85013820"
            },
            "remoteSig": "cef5baed455c622389b597681f5c8e097039172be508142685727db4db7453783e741dcf8386683580ebb3bccc0f74e0cd005f983c96c73ab9573a1a0b0bb1f5"
          },
          "htlcTxsAndRemoteSigs": []
        },
        "remoteCommit": {
          "index": 0,
          "spec": {
            "htlcs": [],
            "commitTxFeerate": 1875,
            "toLocal": 0,
            "toRemote": 450000000
          },
          "txid": "6fdbfb177b9f25441092624d6e4858e6edf9c0557e384a4894ca029af7f7a6a0",
          "remotePerCommitmentPoint": "034cb421e5ce8db456625c81d205ccbde31be4e1f4b03f4a663bca7e6eb217014e"
        },
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
        "remoteNextHtlcId": 0,
        "originChannels": {},
        "remoteNextCommitInfo": "036b57f667ecfd1202f127bc4da9563bba3ae16ccd4b8f033b0bb435c7115b9dcc",
        "commitInput": {
          "outPoint": "323e52325d3ab590fe7d209059fd2e9fd5964b543fe9cc2a6486917a114b23c7:1",
          "amountSatoshis": 450000
        },
        "remotePerCommitmentSecrets": null
      },
      "shortChannelId": "2899x1x1",
      "buried": true,
      "channelAnnouncement": {
        "nodeSignature1": "982f9a7f9d6ff5a4e5732f0382e79f4fb5e20121d0d10ff6d03f264d0d11cc0f04c702fa4d0e52e05d34f465847654f59b199f2f16d01de52e39782a5884cb3f",
        "nodeSignature2": "5ef70341c149ae65006ac38d7b0352bf2b0500e7275ff396797495abd41fd2ca336bbc3c51123e1cdb74b8d9f4c85d8535f01ca1ec257e6fb445668c531887b7",
        "bitcoinSignature1": "280c595a5fe3ad7abc8e922e0c644ad6f0969aa0721a30adf60a17364ad9b6fb13f4219838e801d894694b728fff8e80900dbd24bd72b458377af835dfb30fb4",
        "bitcoinSignature2": "cfdf8cd46f929e118cba47d4ea183bd4ccf483c8b5b700f40b365d2276bb7c4d37ff582b11d8413efa8eb90f715dd73d20a076765bcff463fbccc96227a95fb0",
        "features": {
          "activated": {},
          "unknown": []
        },
        "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
        "shortChannelId": "2899x1x1",
        "nodeId1": "028e2403fbfddb3d787843361f91adbda64c6f622921b19fb48f5766508bcadb29",
        "nodeId2": "02fe677ac8cd61399d097535a3e8a51a0849e57cdbab9b34796c86f3e33568cbe2",
        "bitcoinKey1": "03939f4a2b8fcd9d8c8a1f080b35b06c72f9070a0a619e7d1ac22afda08048d26a",
        "bitcoinKey2": "0362061b316eacd60453158ee93a5d7d6b89b91986766203703c6417a0aae779e1",
        "tlvStream": {
          "records": [],
          "unknown": []
        }
      },
      "channelUpdate": {
        "signature": "02bbe4ee3f128ba044937428680d266c71231fd02d899c446aad498ca095610133f7c2ddb68ed0d8d29961d0962651556dc08b5cb00fb56055d2b98407f4addb",
        "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
        "shortChannelId": "2899x1x1",
        "timestamp": {
          "iso": "2022-02-01T12:27:50Z",
          "unix": 1643718470
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
        "tlvStream": {
          "records": [],
          "unknown": []
        }
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
  "nodeId": "02fe677ac8cd61399d097535a3e8a51a0849e57cdbab9b34796c86f3e33568cbe2",
  "channelId": "c7234b117a9186642acce93f544b96d59f2efd5990207dfe90b53a5d32523e33",
  "state": "NORMAL",
  "data": {
    "type": "DATA_NORMAL",
    "commitments": {
      "channelId": "c7234b117a9186642acce93f544b96d59f2efd5990207dfe90b53a5d32523e33",
      "channelConfig": [
        "funding_pubkey_based_channel_keypath"
      ],
      "channelFeatures": [
        "option_static_remotekey",
        "option_anchors_zero_fee_htlc_tx",
        "option_support_large_channel"
      ],
      "localParams": {
        "nodeId": "028e2403fbfddb3d787843361f91adbda64c6f622921b19fb48f5766508bcadb29",
        "fundingKeyPath": {
          "path": [
            1227691183,
            3473391500,
            3678278473,
            1667538943,
            1421651800,
            2779071154,
            3419774729,
            1449096471,
            2147483649
          ]
        },
        "dustLimit": 546,
        "maxHtlcValueInFlightMsat": 5000000000,
        "channelReserve": 4500,
        "htlcMinimum": 1,
        "toSelfDelay": 120,
        "maxAcceptedHtlcs": 30,
        "isFunder": true,
        "defaultFinalScriptPubKey": "00144f5f4c215f8143d5a1d4c122256b17ffde12ae31",
        "initFeatures": {
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
        }
      },
      "remoteParams": {
        "nodeId": "02fe677ac8cd61399d097535a3e8a51a0849e57cdbab9b34796c86f3e33568cbe2",
        "dustLimit": 546,
        "maxHtlcValueInFlightMsat": 5000000000,
        "channelReserve": 4500,
        "htlcMinimum": 1,
        "toSelfDelay": 120,
        "maxAcceptedHtlcs": 30,
        "fundingPubKey": "0362061b316eacd60453158ee93a5d7d6b89b91986766203703c6417a0aae779e1",
        "revocationBasepoint": "02aebb868790a2e828480baf47eaa0c1c1cc8d23d1a5b0b0e97e54ae07760f946e",
        "paymentBasepoint": "038def9c5eb51875cf85a51bd32ed0b887556a1ec5f1c1022542bd93c0b23d9bd1",
        "delayedPaymentBasepoint": "0270443229941bb3dc0ae1b91a436081552ba37a34d7d742d2a67294607e1bc406",
        "htlcBasepoint": "02eb8e172e4ab45f3ec3c8171a704d6eb4d662d1cdcc81417812b14c4abb99b3c0",
        "initFeatures": {
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
        }
      },
      "channelFlags": {
        "announceChannel": true
      },
      "localCommit": {
        "index": 0,
        "spec": {
          "htlcs": [],
          "commitTxFeerate": 1875,
          "toLocal": 450000000,
          "toRemote": 0
        },
        "commitTxAndRemoteSig": {
          "commitTx": {
            "txid": "3d9411672ea52a527ac951b3b1d19b4828cce679a7030ce09ee18568cc4eab8f",
            "tx": "0200000001c7234b117a9186642acce93f544b96d59f2efd5990207dfe90b53a5d32523e32010000000091a97580024a010000000000002200204aa60e3e77527760f4f69b9da09ad3722a51638ceaccfe63f20a33c5297d1c4c01d306000000000022002080acc9c29cfe9b1ad983e34ce4dacabeb30a1d6fc1c66e138ccc90ca9498cb8a85013820"
          },
          "remoteSig": "cef5baed455c622389b597681f5c8e097039172be508142685727db4db7453783e741dcf8386683580ebb3bccc0f74e0cd005f983c96c73ab9573a1a0b0bb1f5"
        },
        "htlcTxsAndRemoteSigs": []
      },
      "remoteCommit": {
        "index": 0,
        "spec": {
          "htlcs": [],
          "commitTxFeerate": 1875,
          "toLocal": 0,
          "toRemote": 450000000
        },
        "txid": "6fdbfb177b9f25441092624d6e4858e6edf9c0557e384a4894ca029af7f7a6a0",
        "remotePerCommitmentPoint": "034cb421e5ce8db456625c81d205ccbde31be4e1f4b03f4a663bca7e6eb217014e"
      },
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
      "remoteNextHtlcId": 0,
      "originChannels": {},
      "remoteNextCommitInfo": "036b57f667ecfd1202f127bc4da9563bba3ae16ccd4b8f033b0bb435c7115b9dcc",
      "commitInput": {
        "outPoint": "323e52325d3ab590fe7d209059fd2e9fd5964b543fe9cc2a6486917a114b23c7:1",
        "amountSatoshis": 450000
      },
      "remotePerCommitmentSecrets": null
    },
    "shortChannelId": "2899x1x1",
    "buried": true,
    "channelAnnouncement": {
      "nodeSignature1": "982f9a7f9d6ff5a4e5732f0382e79f4fb5e20121d0d10ff6d03f264d0d11cc0f04c702fa4d0e52e05d34f465847654f59b199f2f16d01de52e39782a5884cb3f",
      "nodeSignature2": "5ef70341c149ae65006ac38d7b0352bf2b0500e7275ff396797495abd41fd2ca336bbc3c51123e1cdb74b8d9f4c85d8535f01ca1ec257e6fb445668c531887b7",
      "bitcoinSignature1": "280c595a5fe3ad7abc8e922e0c644ad6f0969aa0721a30adf60a17364ad9b6fb13f4219838e801d894694b728fff8e80900dbd24bd72b458377af835dfb30fb4",
      "bitcoinSignature2": "cfdf8cd46f929e118cba47d4ea183bd4ccf483c8b5b700f40b365d2276bb7c4d37ff582b11d8413efa8eb90f715dd73d20a076765bcff463fbccc96227a95fb0",
      "features": {
        "activated": {},
        "unknown": []
      },
      "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
      "shortChannelId": "2899x1x1",
      "nodeId1": "028e2403fbfddb3d787843361f91adbda64c6f622921b19fb48f5766508bcadb29",
      "nodeId2": "02fe677ac8cd61399d097535a3e8a51a0849e57cdbab9b34796c86f3e33568cbe2",
      "bitcoinKey1": "03939f4a2b8fcd9d8c8a1f080b35b06c72f9070a0a619e7d1ac22afda08048d26a",
      "bitcoinKey2": "0362061b316eacd60453158ee93a5d7d6b89b91986766203703c6417a0aae779e1",
      "tlvStream": {
        "records": [],
        "unknown": []
      }
    },
    "channelUpdate": {
      "signature": "02bbe4ee3f128ba044937428680d266c71231fd02d899c446aad498ca095610133f7c2ddb68ed0d8d29961d0962651556dc08b5cb00fb56055d2b98407f4addb",
      "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
      "shortChannelId": "2899x1x1",
      "timestamp": {
        "iso": "2022-02-01T12:27:50Z",
        "unix": 1643718470
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
      "tlvStream": {
        "records": [],
        "unknown": []
      }
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
    "tlvStream": {
      "records": [],
      "unknown": []
    }
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
    "tlvStream": {
      "records": [],
      "unknown": []
    }
  }
]
```

Returns information about public nodes on the lightning network; this information is taken from the _node_announcement_ network message.

### HTTP Request

`POST http://localhost:8080/nodes`

### Parameters

Parameter | Description                            | Optional | Type
--------- | -------------------------------------- | -------- | -----------------------------------------------
nodeIds   | The **nodeIds** of the nodes to return | Yes      | CSV or JSON list of 33-bytes-HexString (String)

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
    "channelFlags": {
      "isEnabled": true,
      "isNode1": true
    },
    "cltvExpiryDelta": 48,
    "htlcMinimumMsat": 1,
    "feeBaseMsat": 5,
    "feeProportionalMillionths": 150,
    "htlcMaximumMsat": 450000000,
    "tlvStream": {
      "records": [],
      "unknown": []
    }
  },
  {
    "signature": "1da0e7094424c0daa64fe8427e191095d14285dd9346f37d014d07d8857b53cc6bed703d22794ddbfc1945cf5bdb7566137441964e01f8facc30c17fd0dffa06",
    "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
    "shortChannelId": "2899x1x1",
    "timestamp": {
      "iso": "2022-02-01T12:27:19Z",
      "unix": 1643718439
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
    "tlvStream": {
      "records": [],
      "unknown": []
    }
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

Parameter       | Description                                                | Optional | Type
--------------- | ---------------------------------------------------------- | -------- | ---------------------------
description     | A description for the invoice                              | Yes (*)  | String
descriptionHash | Hash of the description for the invoice                    | Yes (*)  | 32-bytes-HexString (String)
amountMsat      | Amount in millisatoshi for this invoice                    | Yes      | Millisatoshi (Integer)
expireIn        | Number of seconds that the invoice will be valid           | Yes      | Seconds (Integer)
fallbackAddress | An on-chain fallback address to receive the payment        | Yes      | Bitcoin address (String)
paymentPreimage | A user defined input for the generation of the paymentHash | Yes      | 32-bytes-HexString (String)

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
    "paymentRequest": {
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
    "paymentRequest": {
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
  "paymentRequest": {
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
            "channelFlags": {
              "isEnabled": true,
              "isNode1": false
            },
            "cltvExpiryDelta": 48,
            "htlcMinimumMsat": 1,
            "feeBaseMsat": 1000,
            "feeProportionalMillionths": 200,
            "htlcMaximumMsat": 450000000,
            "tlvStream": {
              "records": [],
              "unknown": []
            }
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
confirmationTarget | The confirmation target (blocks)     | No       | Satoshi (Integer)

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
recipientNode         | NodeId of the recipient, if known.                         | Yes (*)  | 33-bytes-HexString (String)
recipientBlindedRoute | Blinded route provided by the recipient (encoded as a tlv) | Yes (*)  | HexString (String)
intermediateNodes     | Intermediates nodes to insert before the recipient         | Yes      | CSV or JSON list of 33-bytes-HexString (String)
replyPath             | Reply path that must be used if a response is expected     | Yes      | CSV or JSON list of 33-bytes-HexString (String)

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
    "shortChannelId": "562890x809x0",
    "canSend": 131219000,
    "canReceive": 466000,
    "isPublic": true
  }
]
```

Retrieves information about the available balance of local channels.

### HTTP Request

`POST http://localhost:8080/usablebalances`

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
    "waitForFundingLocked": 0,
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

> Channel opened event

```json
{
  "type": "channel-opened",
  "remoteNodeId": "02d150875194d076f662d4252a8dee7077ed4cc4a848bb9f83fb467b6d3c120199",
  "isFunder": true,
  "temporaryChannelId": "d4eb1fac020d877c73bb75788e23fc70398d6a891bb773f7860481bdba5af04b",
  "initialFeeratePerKw": 1200,
  "fundingTxFeeratePerKw": 2000
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
channel-opened           | A channel opening flow has started
channel-state-changed    | A channel state changed (e.g. going from offline to connected)
channel-closed           | A channel has been closed
onion-message-received   | An onion message was received

### HTTP Request

`GET ws://localhost:8080/ws`
