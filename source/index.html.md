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
curl -u :<eclair_api_password> -X POST "http://localhost:8080/getinfo"

# with eclair-cli
eclair-cli getinfo
```

> The above command returns JSON structured like this:

```json
{
  "version": "0.6.2-6817d6f",
  "nodeId": "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
  "alias": "ACINQ",
  "color": "#000102",
  "features": {
    "activated": {
      "gossip_queries_ex": "optional",
      "option_anchor_outputs": "optional",
      "option_data_loss_protect": "optional",
      "var_onion_optin": "mandatory",
      "option_static_remotekey": "optional",
      "option_support_large_channel": "optional",
      "option_anchors_zero_fee_htlc_tx": "optional",
      "payment_secret": "mandatory",
      "option_shutdown_anysegwit": "optional",
      "basic_mpp": "optional",
      "gossip_queries": "optional"
    },
    "unknown": []
  },
  "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
  "network": "regtest",
  "blockHeight": 123456,
  "publicAddresses": [
    "34.239.230.56:9735",
    "of7husrflx7sforh3fw6yqlpwstee3wg5imvvmkp4bz6rbjxtg5nljad.onion:9735"
  ],
  "instanceId": "3d084f1c-6835-496b-949f-9db1ae4a610d"
}
```

Returns information about this instance such as version, features, **nodeId** and current block height as seen by eclair.

### HTTP Request

`POST http://localhost:8080/getinfo`

# Connect

## Connect via URI

```shell
curl -u :<eclair_api_password> -X POST -F uri=<target_uri>  "http://localhost:8080/connect"

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
curl -u :<eclair_api_password> -X POST -F nodeId=<node_id> -F host=<host> "http://localhost:8080/connect"

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
curl -u :<eclair_api_password> -X POST -F nodeId=<nodeId>  "http://localhost:8080/connect"

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
curl -u :<eclair_api_password> -X POST -F nodeId=<nodeId>  "http://localhost:8080/disconnect"

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
curl -X POST -F nodeId=<node_id> -F fundingSatoshis=<funding_satoshis> "http://localhost:8080/open" -u :<eclair_api_password>

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
channelFlags          | Flags for the new channel: 0 = private, 1 = public                         | Yes      | Integer
openTimeoutSeconds    | Timeout for the operation to complete                                      | Yes      | Seconds (Integer)

# Close

## Close

```shell
curl -u :<eclair_api_password> -X POST -F channelId=<channel> "http://localhost:8080/close"

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
curl -u :<eclair_api_password> -X POST -F channelId=<channel> "http://localhost:8080/forceclose"

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
curl -u :<eclair_api_password> -X POST -F nodeId=<node_id> \
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
  "<channelId>": {
    "feeBase": <feebase>,
    "feeProportionalMillionths": <feeProportionalMillionths>
  }
}
```

Returned `feeBase` is in msat.

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
curl -u :<eclair_api_password> -X POST "http://localhost:8080/peers"

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
curl -u :<eclair_api_password> -X POST "http://localhost:8080/channels"

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
    "nodeId": "02d5aba2f18d31d72e79f99eb49261c2e39cc35b2809f6589e942dcf0922934f24",
    "channelId": "4881ea3050bd0d82a571db79984a4c1c432eb77a7349b1afac6198b92d740cca",
    "state": "NORMAL",
    "data": {
      "type": "DATA_NORMAL",
      "commitments": {
        "channelId": "4881ea3050bd0d82a571db79984a4c1c432eb77a7349b1afac6198b92d740cca",
        "channelConfig": [
          "funding_pubkey_based_channel_keypath"
        ],
        "channelFeatures": [
          "option_static_remotekey",
          "option_anchors_zero_fee_htlc_tx",
          "option_support_large_channel"
        ],
        "localParams": {
          "nodeId": "024b722d985a1919e08786116d4797e8765f989acedaae1146943b114c0166aeb1",
          "fundingKeyPath": {
            "path": [
              2376346446,
              2845139278,
              845530815,
              116517134,
              3208648948,
              1623539631,
              2381994817,
              1990484890,
              2147483649
            ]
          },
          "dustLimit": 546,
          "maxHtlcValueInFlightMsat": 5000000000,
          "channelReserve": 3000,
          "htlcMinimum": 1,
          "toSelfDelay": 720,
          "maxAcceptedHtlcs": 30,
          "isFunder": true,
          "defaultFinalScriptPubKey": "0014b6490f48e48551a84d33fafad1eee38798ef86b0",
          "initFeatures": {
            "activated": {
              "gossip_queries_ex": "optional",
              "option_anchor_outputs": "optional",
              "option_data_loss_protect": "optional",
              "var_onion_optin": "mandatory",
              "option_static_remotekey": "optional",
              "option_support_large_channel": "optional",
              "option_anchors_zero_fee_htlc_tx": "optional",
              "payment_secret": "mandatory",
              "option_shutdown_anysegwit": "optional",
              "basic_mpp": "optional",
              "gossip_queries": "optional"
            },
            "unknown": []
          }
        },
        "remoteParams": {
          "nodeId": "02d5aba2f18d31d72e79f99eb49261c2e39cc35b2809f6589e942dcf0922934f24",
          "dustLimit": 546,
          "maxHtlcValueInFlightMsat": 5000000000,
          "channelReserve": 3000,
          "htlcMinimum": 1,
          "toSelfDelay": 720,
          "maxAcceptedHtlcs": 30,
          "fundingPubKey": "03586aa3567bcb1f174d133c5cbe1e965feb8d26acd555f77737e3a5196bb5e242",
          "revocationBasepoint": "03c96420f568447d7497a5cf69da0708b2ab9dfcded395d3628901c9460fc48f57",
          "paymentBasepoint": "035f27cc965ad7f18087325bc6c48d64181a07ccadf818cd056ea09e300e3f1fbe",
          "delayedPaymentBasepoint": "033e835b582c476a0d8c4dddd446485ce7ee1b60854c6b2fa263bc955160b61a10",
          "htlcBasepoint": "0363ee0e9873e19a2457f1fe2c79e464e5a40976ebbe724b29f805383fa65b9c3e",
          "initFeatures": {
            "activated": {
              "gossip_queries_ex": "optional",
              "option_data_loss_protect": "optional",
              "var_onion_optin": "mandatory",
              "option_static_remotekey": "optional",
              "option_support_large_channel": "optional",
              "option_anchors_zero_fee_htlc_tx": "optional",
              "payment_secret": "mandatory",
              "option_shutdown_anysegwit": "optional",
              "basic_mpp": "optional",
              "gossip_queries": "optional"
            },
            "unknown": []
          }
        },
        "channelFlags": 1,
        "localCommit": {
          "index": 0,
          "spec": {
            "htlcs": [],
            "commitTxFeerate": 2500,
            "toLocal": 300000000,
            "toRemote": 0
          },
          "commitTxAndRemoteSig": {
            "commitTx": {
              "txid": "149b712c7bef33da2798f9f281d4d908efc778231ee67fb3916101c38a76e3af",
              "tx": "02000000014881ea3050bd0d82a571db79984a4c1c432eb77a7349b1afac6198b92d740cca00000000008cea7880024a0100000000000022002005b67505e57667e240c564934ace60c29afb023850c838734e6ef7855eb2a0b95286040000000000220020587f5a79017b2bde0446d9d808d4c4f4225fb336c4544640be551f67a6e88f597edeed20"
            },
            "remoteSig": "a49a71225fbee3a919b3fb73906c67d2b64ac9f77eb4a1bf3e9f2b3cfb05a9f1652d17b5c56192f07514af49ea898fe2d0680feedf284317ee591b85b216e927"
          },
          "htlcTxsAndRemoteSigs": []
        },
        "remoteCommit": {
          "index": 0,
          "spec": {
            "htlcs": [],
            "commitTxFeerate": 2500,
            "toLocal": 0,
            "toRemote": 300000000
          },
          "txid": "5e2526d7b83dfa6cf0878b381148fb1f2dd54cdd880c48f562b2fa4c41b169ba",
          "remotePerCommitmentPoint": "031dcfb98ab373aee0bb8a45bf57049627afb7a9c3380742bda6b5e0719aef66e9"
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
        "remoteNextCommitInfo": "03c7ac97c8ba8bcd9a2d430a0324a761e06588691e828a12ddfdebfa07ad298d32",
        "commitInput": {
          "outPoint": "ca0c742db99861acafb149737ab72e431c4c4a9879db71a5820dbd5030ea8148:0",
          "amountSatoshis": 300000
        },
        "remotePerCommitmentSecrets": null
      },
      "shortChannelId": "11203x1x0",
      "buried": true,
      "channelAnnouncement": {
        "nodeSignature1": "7a10da73a7f25c613333588fb0c79b18338af8cc6ac1dd7a5e17616b8fc69b0f22db572888c466e06cc2e780fefe9951c0afbe9b05a560d165688e6e10d194a4",
        "nodeSignature2": "eb9559577317b562adb8ad0ca77d18c73c76cc857f682adfc41c1c456de79daa31e416597847cc5145dee321883b4d207c948bbeffdfd6cf21ac1fd46cda9d6f",
        "bitcoinSignature1": "ecf6df2e51a4bbb28a80ba61ff9ce045cc6d4a177175f3755072e5aef50e7c8a5b8074049f13f1e98b624419eea88975e92dbe36c7eba215a7d800160ff1f690",
        "bitcoinSignature2": "36c2dc2f147e667da0c94e4ec48f1cc0b159cf77305eda8932ac547281b4fcab02daa3573242d625788270ae253a5bcfb7ec09eecbcbdd36e111753880a4a500",
        "features": {
          "activated": {},
          "unknown": []
        },
        "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
        "shortChannelId": "11203x1x0",
        "nodeId1": "024b722d985a1919e08786116d4797e8765f989acedaae1146943b114c0166aeb1",
        "nodeId2": "02d5aba2f18d31d72e79f99eb49261c2e39cc35b2809f6589e942dcf0922934f24",
        "bitcoinKey1": "035f7babd88d257505ed41d95b5fb8878371aca36e19cfb9603f4c0c890d8f806e",
        "bitcoinKey2": "03586aa3567bcb1f174d133c5cbe1e965feb8d26acd555f77737e3a5196bb5e242",
        "tlvStream": {
          "records": [],
          "unknown": []
        }
      },
      "channelUpdate": {
        "signature": "1358ac8f30d2b29ecde575b17ea3b968af10028f1380f0f3b09ab55a983cf0a267eea80fd9e7b7945e7e579736684f68e8a5bb88e075065171030795256edb94",
        "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
        "shortChannelId": "11203x1x0",
        "timestamp": 1633686766,
        "channelFlags": {
          "isEnabled": true,
          "isNode1": true
        },
        "cltvExpiryDelta": 144,
        "htlcMinimumMsat": 1,
        "feeBaseMsat": 10,
        "feeProportionalMillionths": 150,
        "htlcMaximumMsat": 300000000,
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
curl -u :<eclair_api_password> -X POST -F channelId=<channel>  "http://localhost:8080/channel"

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
    "nodeId": "02d5aba2f18d31d72e79f99eb49261c2e39cc35b2809f6589e942dcf0922934f24",
    "channelId": "4881ea3050bd0d82a571db79984a4c1c432eb77a7349b1afac6198b92d740cca",
    "state": "NORMAL",
    "data": {
      "type": "DATA_NORMAL",
      "commitments": {
        "channelId": "4881ea3050bd0d82a571db79984a4c1c432eb77a7349b1afac6198b92d740cca",
        "channelConfig": [
          "funding_pubkey_based_channel_keypath"
        ],
        "channelFeatures": [
          "option_static_remotekey",
          "option_anchors_zero_fee_htlc_tx",
          "option_support_large_channel"
        ],
        "localParams": {
          "nodeId": "024b722d985a1919e08786116d4797e8765f989acedaae1146943b114c0166aeb1",
          "fundingKeyPath": {
            "path": [
              2376346446,
              2845139278,
              845530815,
              116517134,
              3208648948,
              1623539631,
              2381994817,
              1990484890,
              2147483649
            ]
          },
          "dustLimit": 546,
          "maxHtlcValueInFlightMsat": 5000000000,
          "channelReserve": 3000,
          "htlcMinimum": 1,
          "toSelfDelay": 720,
          "maxAcceptedHtlcs": 30,
          "isFunder": true,
          "defaultFinalScriptPubKey": "0014b6490f48e48551a84d33fafad1eee38798ef86b0",
          "initFeatures": {
            "activated": {
              "gossip_queries_ex": "optional",
              "option_anchor_outputs": "optional",
              "option_data_loss_protect": "optional",
              "var_onion_optin": "mandatory",
              "option_static_remotekey": "optional",
              "option_support_large_channel": "optional",
              "option_anchors_zero_fee_htlc_tx": "optional",
              "payment_secret": "mandatory",
              "option_shutdown_anysegwit": "optional",
              "basic_mpp": "optional",
              "gossip_queries": "optional"
            },
            "unknown": []
          }
        },
        "remoteParams": {
          "nodeId": "02d5aba2f18d31d72e79f99eb49261c2e39cc35b2809f6589e942dcf0922934f24",
          "dustLimit": 546,
          "maxHtlcValueInFlightMsat": 5000000000,
          "channelReserve": 3000,
          "htlcMinimum": 1,
          "toSelfDelay": 720,
          "maxAcceptedHtlcs": 30,
          "fundingPubKey": "03586aa3567bcb1f174d133c5cbe1e965feb8d26acd555f77737e3a5196bb5e242",
          "revocationBasepoint": "03c96420f568447d7497a5cf69da0708b2ab9dfcded395d3628901c9460fc48f57",
          "paymentBasepoint": "035f27cc965ad7f18087325bc6c48d64181a07ccadf818cd056ea09e300e3f1fbe",
          "delayedPaymentBasepoint": "033e835b582c476a0d8c4dddd446485ce7ee1b60854c6b2fa263bc955160b61a10",
          "htlcBasepoint": "0363ee0e9873e19a2457f1fe2c79e464e5a40976ebbe724b29f805383fa65b9c3e",
          "initFeatures": {
            "activated": {
              "gossip_queries_ex": "optional",
              "option_data_loss_protect": "optional",
              "var_onion_optin": "mandatory",
              "option_static_remotekey": "optional",
              "option_support_large_channel": "optional",
              "option_anchors_zero_fee_htlc_tx": "optional",
              "payment_secret": "mandatory",
              "option_shutdown_anysegwit": "optional",
              "basic_mpp": "optional",
              "gossip_queries": "optional"
            },
            "unknown": []
          }
        },
        "channelFlags": 1,
        "localCommit": {
          "index": 0,
          "spec": {
            "htlcs": [],
            "commitTxFeerate": 2500,
            "toLocal": 300000000,
            "toRemote": 0
          },
          "commitTxAndRemoteSig": {
            "commitTx": {
              "txid": "149b712c7bef33da2798f9f281d4d908efc778231ee67fb3916101c38a76e3af",
              "tx": "02000000014881ea3050bd0d82a571db79984a4c1c432eb77a7349b1afac6198b92d740cca00000000008cea7880024a0100000000000022002005b67505e57667e240c564934ace60c29afb023850c838734e6ef7855eb2a0b95286040000000000220020587f5a79017b2bde0446d9d808d4c4f4225fb336c4544640be551f67a6e88f597edeed20"
            },
            "remoteSig": "a49a71225fbee3a919b3fb73906c67d2b64ac9f77eb4a1bf3e9f2b3cfb05a9f1652d17b5c56192f07514af49ea898fe2d0680feedf284317ee591b85b216e927"
          },
          "htlcTxsAndRemoteSigs": []
        },
        "remoteCommit": {
          "index": 0,
          "spec": {
            "htlcs": [],
            "commitTxFeerate": 2500,
            "toLocal": 0,
            "toRemote": 300000000
          },
          "txid": "5e2526d7b83dfa6cf0878b381148fb1f2dd54cdd880c48f562b2fa4c41b169ba",
          "remotePerCommitmentPoint": "031dcfb98ab373aee0bb8a45bf57049627afb7a9c3380742bda6b5e0719aef66e9"
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
        "remoteNextCommitInfo": "03c7ac97c8ba8bcd9a2d430a0324a761e06588691e828a12ddfdebfa07ad298d32",
        "commitInput": {
          "outPoint": "ca0c742db99861acafb149737ab72e431c4c4a9879db71a5820dbd5030ea8148:0",
          "amountSatoshis": 300000
        },
        "remotePerCommitmentSecrets": null
      },
      "shortChannelId": "11203x1x0",
      "buried": true,
      "channelAnnouncement": {
        "nodeSignature1": "7a10da73a7f25c613333588fb0c79b18338af8cc6ac1dd7a5e17616b8fc69b0f22db572888c466e06cc2e780fefe9951c0afbe9b05a560d165688e6e10d194a4",
        "nodeSignature2": "eb9559577317b562adb8ad0ca77d18c73c76cc857f682adfc41c1c456de79daa31e416597847cc5145dee321883b4d207c948bbeffdfd6cf21ac1fd46cda9d6f",
        "bitcoinSignature1": "ecf6df2e51a4bbb28a80ba61ff9ce045cc6d4a177175f3755072e5aef50e7c8a5b8074049f13f1e98b624419eea88975e92dbe36c7eba215a7d800160ff1f690",
        "bitcoinSignature2": "36c2dc2f147e667da0c94e4ec48f1cc0b159cf77305eda8932ac547281b4fcab02daa3573242d625788270ae253a5bcfb7ec09eecbcbdd36e111753880a4a500",
        "features": {
          "activated": {},
          "unknown": []
        },
        "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
        "shortChannelId": "11203x1x0",
        "nodeId1": "024b722d985a1919e08786116d4797e8765f989acedaae1146943b114c0166aeb1",
        "nodeId2": "02d5aba2f18d31d72e79f99eb49261c2e39cc35b2809f6589e942dcf0922934f24",
        "bitcoinKey1": "035f7babd88d257505ed41d95b5fb8878371aca36e19cfb9603f4c0c890d8f806e",
        "bitcoinKey2": "03586aa3567bcb1f174d133c5cbe1e965feb8d26acd555f77737e3a5196bb5e242",
        "tlvStream": {
          "records": [],
          "unknown": []
        }
      },
      "channelUpdate": {
        "signature": "1358ac8f30d2b29ecde575b17ea3b968af10028f1380f0f3b09ab55a983cf0a267eea80fd9e7b7945e7e579736684f68e8a5bb88e075065171030795256edb94",
        "chainHash": "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
        "shortChannelId": "11203x1x0",
        "timestamp": 1633686766,
        "channelFlags": {
          "isEnabled": true,
          "isNode1": true
        },
        "cltvExpiryDelta": 144,
        "htlcMinimumMsat": 1,
        "feeBaseMsat": 10,
        "feeProportionalMillionths": 150,
        "htlcMaximumMsat": 300000000,
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
curl -u :<eclair_api_password> -X POST "http://localhost:8080/nodes"

# with eclair-cli
eclair-cli nodes
```

> The above command returns:

```json
[
  {
    "signature": "3074823d709a7bf0d22abca9d5b260be49adc5ceacf1dcc67410c5c88d0e03373b8b7c000d23f1ec7abf84ab0ecb57e1026d10c5b0c39bfe6d3bcca98fec36cd",
    "features": {
      "activated": {
        "basic_mpp": "optional",
        "initial_routing_sync": "optional",
        "option_data_loss_protect": "optional",
        "gossip_queries_ex": "optional",
        "var_onion_optin": "optional",
        "payment_secret": "optional",
        "gossip_queries": "optional"
      },
      "unknown": []
    },
    "timestamp": 1593698420,
    "nodeId": "03a8334aba5660e241468e2f0deb2526bfd50d0e3fe808d882913e39094dc1a028",
    "rgbColor": "#33cccc",
    "alias": "cosmicApotheosis",
    "addresses": [
      "138.229.205.237:9735"
    ],
    "tlvStream": {
      "records": [],
      "unknown": []
    }
  },
  {
    "signature": "3074823d709a7bf0d22abca9d5b260be49adc5ceacf1dcc67410c5c88d0e03373b8b7c000d23f1ec7abf84ab0ecb57e1026d10c5b0c39bfe6d3bcca98fec36cd",
    "features": {
      "activated": {
        "initial_routing_sync": "optional",
        "option_data_loss_protect": "optional",
        "var_onion_optin": "optional",
        "payment_secret": "optional"
      },
      "unknown": []
    },
    "timestamp": 1593698420,
    "nodeId": "036a54f02d2186de192e4bcec3f7b47adb43b1fa965793387cd2471990ce1d236b",
    "rgbColor": "#1d236b",
    "alias": "capacity.network",
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
curl -u :<eclair_api_password> -X POST "http://localhost:8080/allchannels"

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
curl -u :<eclair_api_password> -X POST "http://localhost:8080/allupdates"

# with eclair-cli
eclair-cli allupdates
```

> The above command returns:

```json
[
  {
    "signature": "3045022100d24aeacc7214b78ad7ac2287c53f505ae5a83b149baac914f881ce8be2c2b28f0220132ed855fdd831e0e973f38d28624b9f7dee25e6181755e1572c8a3145dd765f01",
    "chainHash": "6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000",
    "shortChannelId": "566780x1734x0",
    "timestamp": 1552908891,
    "channelFlags": {
      "isEnabled": true,
      "isNode1": true
    },
    "cltvExpiryDelta": 144,
    "htlcMinimumMsat": 1000,
    "feeBaseMsat": 1000,
    "feeProportionalMillionths": 1,
    "htlcMaximumMsat": 2970000000,
    "tlvStream": {
      "records": [],
      "unknown": []
    }
  },
  {
    "signature": "304402201848be0aff000ec279e2d043d1bde8b2c76a9277dab72b9d1523468961c5d78e0220541e233977f2288684dab6ec168e43dc3459d093e901dd6f2b5238c2b888845a01",
    "chainHash": "6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000",
    "shortChannelId": "562890x809x0",
    "timestamp": 1552993875,
    "channelFlags": {
      "isEnabled": true,
      "isNode1": false
    },
    "cltvExpiryDelta": 144,
    "htlcMinimumMsat": 1000,
    "feeBaseMsat": 1000,
    "feeProportionalMillionths": 2500,
    "htlcMaximumMsat": 3960000000,
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

## NetworkStats

```shell
curl -u :<eclair_api_password> -X POST "http://localhost:8080/networkstats"

# with eclair-cli
eclair-cli networkstats
```

> The above command returns:

```json
{
   "channels":1,
   "nodes":2,
   "capacity":{
      "median":30,
      "percentile5":12,
      "percentile10":14,
      "percentile25":20,
      "percentile75":40,
      "percentile90":46,
      "percentile95":48
   },
   "cltvExpiryDelta":{
      "median":32,
      "percentile5":11,
      "percentile10":13,
      "percentile25":22,
      "percentile75":42,
      "percentile90":51,
      "percentile95":53
   },
   "feeBase":{
      "median":32,
      "percentile5":11,
      "percentile10":13,
      "percentile25":22,
      "percentile75":42,
      "percentile90":51,
      "percentile95":53
   },
   "feeProportional":{
      "median":32,
      "percentile5":11,
      "percentile10":13,
      "percentile25":22,
      "percentile75":42,
      "percentile90":51,
      "percentile95":53
   }
}
```

Returns the median and percentiles statistics about the network graph such as fees, cltvExpiry and capacity.

### HTTP Request

`POST http://localhost:8080/networkstats`

# Payments

Interfaces for sending and receiving payments through eclair.

## CreateInvoice

```shell
curl -u :<eclair_api_password> -X POST -F description=<some_description> \
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
  "timestamp": 1633688171,
  "nodeId": "024b722d985a1919e08786116d4797e8765f989acedaae1146943b114c0166aeb1",
  "serialized": "lnbcrt500n1pskqxntpp5mcwhqp37al2jxmtyzkydtprctcewsc6anfz4038v3u4nnu4h5wtsdq0ydex2cmtd3jhxucsp5vu0z6tty8gmlytm82u2p4t66lav86c9seq66kxygp4k4kaa8sj8sxqrrsscqp79qtzqqqqqqysgq6k3g0r44gaz2lugjm932q3vzne3uwp9xgx309968w2v5qrfcdrk9gn9l2agl38zxkdm8wduqshsu98w5g7lg8z6mlghd9rccnprcd0gp83ug5u",
  "description": "#reckless",
  "paymentHash": "de1d70063eefd5236d641588d584785e32e8635d9a4557c4ec8f2b39f2b7a397",
  "expiry": 3600,
  "minFinalCltvExpiry": 30,
  "amount": 50000,
  "features": {
    "activated": {
      "var_onion_optin": "mandatory",
      "payment_secret": "mandatory",
      "basic_mpp": "optional"
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

## ParseInvoice

```shell
curl -u :<eclair_api_password> -X POST -F invoice=<some_bolt11invoice> "http://localhost:8080/parseinvoice"

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
  "timestamp": 1633688171,
  "nodeId": "024b722d985a1919e08786116d4797e8765f989acedaae1146943b114c0166aeb1",
  "serialized": "lnbcrt500n1pskqxntpp5mcwhqp37al2jxmtyzkydtprctcewsc6anfz4038v3u4nnu4h5wtsdq0ydex2cmtd3jhxucsp5vu0z6tty8gmlytm82u2p4t66lav86c9seq66kxygp4k4kaa8sj8sxqrrsscqp79qtzqqqqqqysgq6k3g0r44gaz2lugjm932q3vzne3uwp9xgx309968w2v5qrfcdrk9gn9l2agl38zxkdm8wduqshsu98w5g7lg8z6mlghd9rccnprcd0gp83ug5u",
  "description": "#reckless",
  "paymentHash": "de1d70063eefd5236d641588d584785e32e8635d9a4557c4ec8f2b39f2b7a397",
  "expiry": 3600,
  "minFinalCltvExpiry": 30,
  "amount": 50000,
  "features": {
    "activated": {
      "var_onion_optin": "mandatory",
      "payment_secret": "mandatory",
      "basic_mpp": "optional"
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
curl -u :<eclair_api_password> -X POST -F invoice=<some_invoice> "http://localhost:8080/payinvoice"

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
It will return either the payment preimage (if the payment succeeded) or failure details.

### HTTP Request

`POST http://localhost:8080/payinvoice`

### Parameters

Parameter                 | Description                                                                                    | Optional | Type
------------------------- | ---------------------------------------------------------------------------------------------- | -------- | ----------------------
invoice                   | The invoice you want to pay                                                                    | No       | String
amountMsat                | Amount to pay if the invoice does not have one                                                 | Yes      | Millisatoshi (Integer)
maxAttempts               | Max number of retries                                                                          | Yes      | Integer
feeThresholdSat           | Fee threshold to be paid along the payment route                                               | Yes      | Satoshi (Integer)
maxFeePct                 | Max percentage to be paid in fees along the payment route (ignored if below `feeThresholdSat`) | Yes      | Integer (between 0 and 100)
externalId                | Extra payment identifier specified by the caller                                               | Yes      | String
pathFindingExperimentName | Name of the path-finding configuration that should be used                                     | Yes      | String
blocking                  | Block until the payment completes                                                              | Yes      | Boolean

## SendToNode

```shell
curl -u :<eclair_api_password> -X POST -F nodeId=<some_node> \
  -F amountMsat=<amount> -F paymentHash=<some_hash> "http://localhost:8080/sendtonode"

# with eclair-cli
eclair-cli sendtonode --nodeId=<some_node> --amountMsat=<amount> --paymentHash=<some_hash>
```

> The above command returns:

```json
"e4227601-38b3-404e-9aa0-75a829e9bec0"
```

Sends money to a node using `keysend` (spontaneous payment without a Bolt11 invoice).
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
feeThresholdSat           | Fee threshold to be paid along the payment route                                               | Yes      | Satoshi (Integer)
maxFeePct                 | Max percentage to be paid in fees along the payment route (ignored if below `feeThresholdSat`) | Yes      | Integer (between 0 and 100)
externalId                | Extra payment identifier specified by the caller                                               | Yes      | String
pathFindingExperimentName | Name of the path-finding configuration that should be used                                     | Yes      | String

## SendToRoute

```shell
curl -u :<eclair_api_password> -X POST -F nodeIds=node1,node2 \
  -F amountMsat=<amount> \
  -F paymentHash=<some_hash> \
  -F finalCltvExpiry=<some_value> \
  -F invoice=<some_invoice> \
  "http://localhost:8080/sendtoroute"

curl -u :<eclair_api_password> -X POST -F shortChannelIds=42x1x0,56x7x3 \
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
curl -u :<eclair_api_password> -X POST -F paymentHash=<some_hash> "http://localhost:8080/getsentinfo"

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
    "id": "83fcc569-917a-4cac-b42d-6f6b186f21eb",
    "parentId": "cd5666d5-7678-4458-b50b-21b363b34f5e",
    "paymentHash": "931ee191eb98176b401222a17dc9269181714a6a940d057cc0b54fed101fc3cc",
    "paymentType": "Standard",
    "amount": 4827118,
    "recipientAmount": 90000000,
    "recipientNodeId": "02d150875194d076f662d4252a8dee7077ed4cc4a848bb9f83fb467b6d3c120199",
    "createdAt": 1593698975810,
    "paymentRequest": {
      "prefix": "lnbcrt",
      "timestamp": 1593698964,
      "nodeId": "02d150875194d076f662d4252a8dee7077ed4cc4a848bb9f83fb467b6d3c120199",
      "serialized": "lnbcrt900u1p00m655pp5jv0wry0tnqtkksqjy2shmjfxjxqhzjn2jsxs2lxqk4876yqlc0xqdqjwpex2urpwfjjqn2s2qxqrrss9qtzqqqqqq9qsqsp5wv8k8przrn54tj3wlz03s5z6xp82e959ujfgw05lacnukrpss4ss72jdwx9x9rvx0szts8ewfkhatez3kjujnu77msg5mp3t3vumsexht994k8f6wdrelflr4c5kghl6z02acawr6e9ppcrsex467zhahjcqax4658",
      "description": "prepare MPP",
      "paymentHash": "931ee191eb98176b401222a17dc9269181714a6a940d057cc0b54fed101fc3cc",
      "expiry": 3600,
      "minFinalCltvExpiry": 30,
      "amount": 90000000,
      "features": {
        "activated": {
          "var_onion_optin": "optional",
          "payment_secret": "optional",
          "basic_mpp": "optional"
        },
        "unknown": []
      }
    },
    "status": {
      "type": "sent",
      "paymentPreimage": "a10d43f61016e052dfe946f24b550a37f538d033cc29999d8df10438d2618943",
      "feesPaid": 1482,
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
      "completedAt": 1593698976205
    }
  },
  {
    "id": "84c89f34-389f-4d0a-a48a-0eed52e8bcf5",
    "parentId": "cd5666d5-7678-4458-b50b-21b363b34f5e",
    "paymentHash": "931ee191eb98176b401222a17dc9269181714a6a940d057cc0b54fed101fc3cc",
    "paymentType": "Standard",
    "amount": 85172882,
    "recipientAmount": 90000000,
    "recipientNodeId": "02d150875194d076f662d4252a8dee7077ed4cc4a848bb9f83fb467b6d3c120199",
    "createdAt": 1593698975810,
    "paymentRequest": {
      "prefix": "lnbcrt",
      "timestamp": 1593698964,
      "nodeId": "02d150875194d076f662d4252a8dee7077ed4cc4a848bb9f83fb467b6d3c120199",
      "serialized": "lnbcrt900u1p00m655pp5jv0wry0tnqtkksqjy2shmjfxjxqhzjn2jsxs2lxqk4876yqlc0xqdqjwpex2urpwfjjqn2s2qxqrrss9qtzqqqqqq9qsqsp5wv8k8przrn54tj3wlz03s5z6xp82e959ujfgw05lacnukrpss4ss72jdwx9x9rvx0szts8ewfkhatez3kjujnu77msg5mp3t3vumsexht994k8f6wdrelflr4c5kghl6z02acawr6e9ppcrsex467zhahjcqax4658",
      "description": "prepare MPP",
      "paymentHash": "931ee191eb98176b401222a17dc9269181714a6a940d057cc0b54fed101fc3cc",
      "expiry": 3600,
      "minFinalCltvExpiry": 30,
      "amount": 90000000,
      "features": {
        "activated": {
          "var_onion_optin": "optional",
          "payment_secret": "optional",
          "basic_mpp": "optional"
        },
        "unknown": []
      }
    },
    "status": {
      "type": "sent",
      "paymentPreimage": "a10d43f61016e052dfe946f24b550a37f538d033cc29999d8df10438d2618943",
      "feesPaid": 9517,
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
      "completedAt": 1593698976218
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
curl -u :<eclair_api_password> -X POST -F paymentHash=<some_hash> "http://localhost:8080/getreceivedinfo"

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
    "timestamp": 1593698964,
    "nodeId": "02d150875194d076f662d4252a8dee7077ed4cc4a848bb9f83fb467b6d3c120199",
    "serialized": "lnbcrt900u1p00m655pp5jv0wry0tnqtkksqjy2shmjfxjxqhzjn2jsxs2lxqk4876yqlc0xqdqjwpex2urpwfjjqn2s2qxqrrss9qtzqqqqqq9qsqsp5wv8k8przrn54tj3wlz03s5z6xp82e959ujfgw05lacnukrpss4ss72jdwx9x9rvx0szts8ewfkhatez3kjujnu77msg5mp3t3vumsexht994k8f6wdrelflr4c5kghl6z02acawr6e9ppcrsex467zhahjcqax4658",
    "description": "prepare MPP",
    "paymentHash": "931ee191eb98176b401222a17dc9269181714a6a940d057cc0b54fed101fc3cc",
    "expiry": 3600,
    "minFinalCltvExpiry": 30,
    "amount": 90000000,
    "features": {
      "activated": {
        "var_onion_optin": "optional",
        "payment_secret": "optional",
        "basic_mpp": "optional"
      },
      "unknown": []
    }
  },
  "paymentPreimage": "a10d43f61016e052dfe946f24b550a37f538d033cc29999d8df10438d2618943",
  "paymentType": "Standard",
  "createdAt": 1593698964000,
  "status": {
    "type": "received",
    "amount": 90000000,
    "receivedAt": 1593698976184
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
curl -u :<eclair_api_password> -X POST -F paymentHash=<some_hash> "http://localhost:8080/getinvoice"

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
  "timestamp": 1593699654,
  "nodeId": "03dfefbc942ac877655af00c4a6e9314626438e4aaba141412d825d5f2304bf0bf",
  "serialized": "lnbcrt500n1p00mm2xpp55satck8wvh0fgfpcaf2fq5c3y7hkznr2acz7mjua5kprn7mg6g7qdq809hkcmcxqrrss9qtzqqqqqq9qsqsp5uzkn3kn99ujlevns05ltc93u6qt000f7q6prd58e373fye0errrqqvz9m0ey2afk7g5y5pa3cy79de0fc4xq4akd57ugrfhn58sa897965vy6ajfsdz9mqwnxr9z6ddwfth0p379fcclm9j4y850whggxcgp2muvjq",
  "description": "#reckless",
  "paymentHash": "a43abc58ee65de942438ea5490531127af614c6aee05edcb9da58239fb68d23c",
  "expiry": 3600,
  "minFinalCltvExpiry": 30,
  "amount": 50000,
  "features": {
    "activated": {
      "var_onion_optin": "optional",
      "payment_secret": "optional",
      "basic_mpp": "optional"
    },
    "unknown": []
  }
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
curl -u :<eclair_api_password> -X POST "http://localhost:8080/listinvoices"

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
    "timestamp": 1593699654,
    "nodeId": "03dfefbc942ac877655af00c4a6e9314626438e4aaba141412d825d5f2304bf0bf",
    "serialized":   "lnbcrt500n1p00mm2xpp55satck8wvh0fgfpcaf2fq5c3y7hkznr2acz7mjua5kprn7mg6g7qdq809hkcmcxqrrss9qtzqqqqqq9qsqsp5uzkn3kn99ujlevns05ltc93u6qt000f7q6prd58e 373fye0errrqqvz9m0ey2afk7g5y5pa3cy79de0fc4xq4akd57ugrfhn58sa897965vy6ajfsdz9mqwnxr9z6ddwfth0p379fcclm9j4y850whggxcgp2muvjq",
    "description": "#reckless",
    "paymentHash": "a43abc58ee65de942438ea5490531127af614c6aee05edcb9da58239fb68d23c",
    "expiry": 3600,
    "minFinalCltvExpiry": 30,
    "amount": 50000,
    "features": {
      "activated": {
        "var_onion_optin": "optional",
        "payment_secret": "optional",
        "basic_mpp": "optional"
      },
      "unknown": []
    }
  },
  {
    "prefix": "lnbcrt",
    "timestamp": 1593699654,
    "nodeId": "03dfefbc942ac877655af00c4a6e9314626438e4aaba141412d825d5f2304bf0bf",
    "serialized":   "lnbcrt500n1p00mm2xpp55satck8wvh0fgfpcaf2fq5c3y7hkznr2acz7mjua5kprn7mg6g7qdq809hkcmcxqrrss9qtzqqqqqq9qsqsp5uzkn3kn99ujlevns05ltc93u6qt000f7q6prd58e 373fye0errrqqvz9m0ey2afk7g5y5pa3cy79de0fc4xq4akd57ugrfhn58sa897965vy6ajfsdz9mqwnxr9z6ddwfth0p379fcclm9j4y850whggxcgp2muvjq",
    "description": "#reckless",
    "paymentHash": "b123bc58de65de942438ea5490531127af614c6aee05edcb9da58239fb68d23c",
    "expiry": 3600,
    "minFinalCltvExpiry": 30,
    "amount": 25000,
    "features": {
      "activated": {
        "var_onion_optin": "optional"
      },
      "unknown": []
    }
  },
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
curl -u :<eclair_api_password> -X POST "http://localhost:8080/listpendinginvoices"

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
    "timestamp": 1593699654,
    "nodeId": "03dfefbc942ac877655af00c4a6e9314626438e4aaba141412d825d5f2304bf0bf",
    "serialized":   "lnbcrt500n1p00mm2xpp55satck8wvh0fgfpcaf2fq5c3y7hkznr2acz7mjua5kprn7mg6g7qdq809hkcmcxqrrss9qtzqqqqqq9qsqsp5uzkn3kn99ujlevns05ltc93u6qt000f7q6prd58e 373fye0errrqqvz9m0ey2afk7g5y5pa3cy79de0fc4xq4akd57ugrfhn58sa897965vy6ajfsdz9mqwnxr9z6ddwfth0p379fcclm9j4y850whggxcgp2muvjq",
    "description": "#reckless",
    "paymentHash": "a43abc58ee65de942438ea5490531127af614c6aee05edcb9da58239fb68d23c",
    "expiry": 3600,
    "minFinalCltvExpiry": 30,
    "amount": 50000,
    "features": {
      "activated": {
        "var_onion_optin": "optional",
        "payment_secret": "optional",
        "basic_mpp": "optional"
      },
      "unknown": []
    }
  },
  {
    "prefix": "lnbcrt",
    "timestamp": 1593699654,
    "nodeId": "03dfefbc942ac877655af00c4a6e9314626438e4aaba141412d825d5f2304bf0bf",
    "serialized":   "lnbcrt500n1p00mm2xpp55satck8wvh0fgfpcaf2fq5c3y7hkznr2acz7mjua5kprn7mg6g7qdq809hkcmcxqrrss9qtzqqqqqq9qsqsp5uzkn3kn99ujlevns05ltc93u6qt000f7q6prd58e 373fye0errrqqvz9m0ey2afk7g5y5pa3cy79de0fc4xq4akd57ugrfhn58sa897965vy6ajfsdz9mqwnxr9z6ddwfth0p379fcclm9j4y850whggxcgp2muvjq",
    "description": "#reckless",
    "paymentHash": "b123bc58de65de942438ea5490531127af614c6aee05edcb9da58239fb68d23c",
    "expiry": 3600,
    "minFinalCltvExpiry": 30,
    "amount": 25000,
    "features": {
      "activated": {
        "var_onion_optin": "optional"
      },
      "unknown": []
    }
  },
]
```

Returns all non-paid, non-expired **BOLT11** invoices stored.
The invoices can be filtered by date and are outputted in descending order.

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
curl -u :<eclair_api_password> -X POST -F invoice=<some_bolt11invoice> "http://localhost:8080/findroute"

# with eclair-cli
eclair-cli findroute --invoice=<some_bolt11invoice>
```

> The above command returns:

```json
[
  "036d65409c41ab7380a43448f257809e7496b52bf92057c09c4f300cbd61c50d96",
  "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
  "03d06758583bb5154774a6eb221b1276c9e82d65bbaceca806d90e20c108f4b1c7"
]
```

Finds a route to the node specified by the invoice.
If the invoice does not specify an amount, you must do so via the `amountMsat` parameter.

You can specify various formats for the route returned with the `format` parameter.
When using `format=shortChannelId`, the above command would return:

```json
[
  "11203x1x0",
  "11203x7x5",
  "11205x3x3"
]
```

The formats currently supported are `nodeId` and `shortChannelId`.

### HTTP Request

`POST http://localhost:8080/findroute`

### Parameters

Parameter                 | Description                                                | Optional | Type
------------------------- | ---------------------------------------------------------- | -------- | ----------------------
invoice                   | The invoice containing the destination                     | No       | String
amountMsat                | The amount that should go through the route                | Yes      | Millisatoshi (Integer)
format                    | Format that will be used for the resulting route           | Yes      | String
includeLocalChannelCost   | If true, the relay fees of local channels will be counted  | Yes      | Boolean
pathFindingExperimentName | Name of the path-finding configuration that should be used | Yes      | String

## FindRouteToNode

```shell
curl -u :<eclair_api_password> -X POST -F nodeId=<some_node> \
     -F amountMsat=<some_amount> "http://localhost:8080/findroutetonode"

# with eclair-cli
eclair-cli --nodeId=<some_node> --amountMsat=<some_amount>
```

> The above command returns:

```json
[
  "036d65409c41ab7380a43448f257809e7496b52bf92057c09c4f300cbd61c50d96",
  "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
  "03d06758583bb5154774a6eb221b1276c9e82d65bbaceca806d90e20c108f4b1c7"
]
```

Finds a route to the given node.

### HTTP Request

`POST http://localhost:8080/findroutetonode`

### Parameters

Parameter                 | Description                                                | Optional | Type
------------------------- | ---------------------------------------------------------- | -------- | ---------------------------
nodeId                    | The destination of the route                               | No       | 33-bytes-HexString (String)
amountMsat                | The amount that should go through the route                | No       | Millisatoshi (Integer)
format                    | Format that will be used for the resulting route           | Yes      | String
includeLocalChannelCost   | If true, the relay fees of local channels will be counted  | Yes      | Boolean
pathFindingExperimentName | Name of the path-finding configuration that should be used | Yes      | String

## FindRouteBetweenNodes

```shell
curl -u :<eclair_api_password> -X POST -F sourceNodeId=<some_node> -F targetNodeId=<some_node> \
     -F amountMsat=<some_amount> "http://localhost:8080/findroutebetweennodes"

# with eclair-cli
eclair-cli --sourceNodeId=<some_node> --targetNodeId=<some_node> --amountMsat=<some_amount>
```

> The above command returns:

```json
[
  "036d65409c41ab7380a43448f257809e7496b52bf92057c09c4f300cbd61c50d96",
  "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
  "03d06758583bb5154774a6eb221b1276c9e82d65bbaceca806d90e20c108f4b1c7"
]
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
format                    | Format that will be used for the resulting route           | Yes      | String
includeLocalChannelCost   | If true, the relay fees of local channels will be counted  | Yes      | Boolean
pathFindingExperimentName | Name of the path-finding configuration that should be used | Yes      | String

# On-Chain

## GetNewAddress

```shell
curl -u :<eclair_api_password> -X POST "http://localhost:8080/getnewaddress"

# with eclair-cli
eclair-cli getnewaddress
```

> The above command returns:

```json
"2MsRZ1asG6k94m6GYUufDGaZJMoJ4EV5JKs"
```

Get a new on-chain address from the wallet. This can be used to deposit funds that will later be used
to fund channels. The API is only available with the bitcoin-core watcher type, and the resulting addresses
depend on the configured address-type in `bitcoin.conf`.

### HTTP Request

`POST http://localhost:8080/getnewaddress`

## SendOnChain

```shell
curl -u :<eclair_api_password> -X POST -F address=<bitcoin_address> \
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
curl -u :<eclair_api_password> -X POST "http://localhost:8080/onchainbalance"

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

Retrieves information about the available on-chain Bitcoin balance. Amounts are in Satoshis.
Unconfirmed balance refers to incoming transactions seen in the mempool.

## OnChainTransactions

```shell
curl -u :<eclair_api_password> -X -F count=<number_of_results> -F skip=<skipped_results> POST "http://localhost:8080/onchaintransactions"

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

## SignMessage

```shell
curl -u :<eclair_api_password> -X POST -F msg=aGVsbG8gd29ybGQ= "http://localhost:8080/signmessage"

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
curl -u :<eclair_api_password> -X POST -F msg=aGVsbG8gd29ybGQ= \
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
curl -u :<eclair_api_password> -X POST "http://localhost:8080/audit"

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
          "timestamp": 1593698280576
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
          "timestamp": 1593698976205
        },
        {
          "id": "84c89f34-389f-4d0a-a48a-0eed52e8bcf5",
          "amount": 85172882,
          "feesPaid": 9517,
          "toChannelId": "d4eb1fac020d877c73bb75788e23fc70398d6a891bb773f7860481bdba5af04b",
          "timestamp": 1593698976218
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
          "timestamp": 1593698976184
        },
        {
          "amount": 85172882,
          "fromChannelId": "1b14940e98238a84b7a9f0429571ba8ca1a4da3ba5699a3f0082a16761f9bd6f",
          "timestamp": 1593698976184
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
      "timestamp": 1593698280565
    },
    {
      "type": "payment-relayed",
      "amountIn": 45005500,
      "amountOut": 45000000,
      "paymentHash": "c5bd76b696d75b2e548a00f14e30cd694cfb3790e095ab62303abe48f2b3a263",
      "fromChannelId": "d4eb1fac020d877c73bb75788e23fc70398d6a891bb773f7860481bdba5af04b",
      "toChannelId": "10eeb6d8cfd8c3f6a93d22e2cd8adf5e36bdb43c53405ddc4fc17a0f7608162a",
      "timestamp": 1593698492275
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
curl -u :<eclair_api_password> -X POST "http://localhost:8080/networkfees"

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
    "timestamp": 1551798422110
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
curl -u :<eclair_api_password> -X POST "http://localhost:8080/channelstats"

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
curl -u :<eclair_api_password> -X POST "http://localhost:8080/usablebalances"

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
  "timestamp": 1593698280565
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
      "timestamp": 1593698976184
    },
    {
      "amount": 24,
      "fromChannelId": "1b14940e98238a84b7a9f0429571ba8ca1a4da3ba5699a3f0082a16761f9bd6f",
      "timestamp": 1593698976184
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
   "failures": [],
   "timestamp": 1553784963659
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
      "timestamp": 1593698280576
    },
    {
      "id": "ab348eb7-b0ed-46ff-9274-28cfdbdaae8d",
      "amount": 24,
      "feesPaid": 3,
      "toChannelId": "d4eb1fac020d877c73bb75788e23fc70398d6a891bb773f7860481bdba5af04b",
      "timestamp": 1593698280576
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
   "timestamp": 1553785442676
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

### HTTP Request

`GET ws://localhost:8080/ws`
