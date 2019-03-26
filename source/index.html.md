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
Feel free to suggest improvements and fixes to this documentation by submitting a pull request to the [repo](https://github.com/ACINQ/eclair). The API
uses [HTTP form data](https://en.wikipedia.org/wiki/POST_(HTTP)#Use_for_submitting_web_forms) and returns JSON encoded object or simple strings if no object
is being returned, all errors are handled with a JSON response more info [here](#errors)

# Authentication

Eclair uses HTTP Basic authentication and expects to receive the correct header with every request.
To set an API password use the [configuration](https://github.com/ACINQ/eclair/blob/master/eclair-core/src/main/resources/reference.conf).
The rest of this document will use '21satoshi' as password which encoded as _base64_ results in `OjIxc2F0b3NoaQ==`

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
   "nodeId":"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
   "alias":"ACINQ",
   "chainHash":"06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
   "blockHeight":123456,
   "publicAddresses":[
      "34.239.230.56:9735",
      "of7husrflx7sforh3fw6yqlpwstee3wg5imvvmkp4bz6rbjxtg5nljad.onion:9735"
   ]
}
```

Returns information about this instance such as **nodeId** and current block height as seen by eclair.

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

```
connected
```

Connect to another lightning node, this will perform a connection but no channel will be opened.


### HTTP Request

`POST http://localhost:8080/connect`

### Parameters

Parameter | Description | Optional | Type
--------- | ----------- | --------- | ---------
uri | The URI in format 'nodeId@host:port' | No | String

## Connect manually

```shell
curl -u :<eclair_api_password> -X POST -F nodeId=<node_id> \ 
	-F host=<host> -F port=<port> "http://localhost:8080/connect"

# with eclair-cli
eclair-cli connect --nodeId=<node_id> --host=<host> --port=<port>
```

> The above command returns:

```
connected
```

Connect to another lightning node, this will perform a connection but no channel will be opened.

### HTTP Request

`POST http://localhost:8080/connect`

### Parameters

Parameter | Description | Optional | Type
--------- | ----------- | --------- | ---------
nodeId | The **nodeId** of the node you want to connect to | No | 32bytes-HexString (String)
host | The IPv4 host address of the node | No | String
port | The port of the node | No | Integer

# Open

## Open

```shell
curl -X POST -F nodeId=<node_id> -F fundingSatoshis=<funding_satoshis> \
	"http://localhost:8080/open" -u :<eclair_api_password>

#with eclair-cli
eclair-cli open --nodeId=<node_id> --fundingSatoshis=<funding_satoshis>
```

> The above command returns the channelId of the newly created channel:

```
e872f515dc5d8a3d61ccbd2127f33141eaa115807271dcc5c5c727f3eca914d3
```

Open a channel to another lightning node, you must specify the target nodeId and the funding satoshis for the new channel. Optionally
you can send to the remote a _pushMsat_ value and you can specify wether this should be a public or private channel (default is set in the config).

### HTTP Request

`POST http://localhost:8080/open`

### Parameters

Parameter | Description | Optional | Type
--------- | ----------- | --------- | ---------
nodeId | The nodeId of the node you want to connect to | No | 32bytes-HexString (String)
fundingSatoshis | Amount of satoshis to spend in the funding of the channel | No | Satoshis (Integer)
pushMsat | Amount of millisatoshi to unilaterally push to the counterparty | Yes | Millisatoshis (Integer)
fundingFeerateSatByte | Feerate in sat/byte to apply to the funding transaction | Yes | Satoshis (Integer)
channelFlags | Flags for the new channel: 0 = private, 1 = public | Yes | Integer

# Close

## Close

```shell
curl -u :<eclair_api_password> -X POST -F channelId=<channel> "http://localhost:8080/close"

#with eclair-cli
eclair-cli close --channelId=<channel>
```

> The above command returns:

```
ok
```

Initiates a cooperative close for a give channel that belongs to this eclair node, the API returns once the _funding_signed_ message has been negotiated.
If you specified a scriptPubKey then the closing transaction will spend to that address. Note that you must specify at least a _channelId_ **or** _shortChannelId_.

### HTTP Request

`POST http://localhost:8080/close`

### Parameters

Parameter | Description | Optional | Type
--------- | ----------- | --------- | ---------
channelId | The channelId of the channel you want to close | No | 32bytes-HexString (String)
shortChannelId | The shortChannelId of the channel you want to close | Yes | ShortChannelId (String)
scriptPubKey | A serialized scriptPubKey that you want to use to close the channel | Yes | HexString (String)

## Force Close

```shell
curl -u :<eclair_api_password> -X POST -F channelId=<channel> "http://localhost:8080/forceclose"

#with eclair-cli
eclair-cli forceclose --channelId=<channel>
```

> The above command returns:

```
e872f515dc5d8a3d61ccbd2127f33141eaa115807271dcc5c5c727f3eca914d3
```

Initiates an unilateral close for a give channel that belongs to this eclair node, once the commitment has been broadcasted the API returns its
transaction id. Note that you must specify at least a _channelId_ **or** _shortChannelId_.

### HTTP Request

`POST http://localhost:8080/forceclose`

### Parameters

Parameter | Description | Optional | Type
--------- | ----------- | --------- | ---------
channelId | The channelId of the channel you want to close | No | 32bytes-HexString (String)
shortChannelId | The shortChannelId of the channel you want to close | Yes | ShortChannelId (String)

# UpdateRelayFee

## updaterelayfee

```shell
curl -u :<eclair_api_password> -X POST -F channelId=<channel> \
     -F feeBaseMsat=<feebase> -F feeProportionalMillionths=<feeproportional> \
     "http://localhost:8080/updaterelayfee"

#eclair-cli
eclair-cli updaterelayfee \
	--channelId=<channel> \
	--feeBaseMsat=<feebase> \
	--feeProportionalMillionths=<feeproportional>
```

> The above command returns:

```
ok
```

Updates the fee policy for the specified _channelId_, a new update for this channel will be broadcasted to the network.

### HTTP Request

`POST http://localhost:8080/updaterelayfee`

### Parameters

Parameter | Description | Optional | Type
--------- | ----------- | --------- | ---------
channelId | The channelId of the channel you want to update | No | 32bytes-HexString (String)
feeBaseMsat | The new base fee to use  | No | Millisatoshi (Integer)
feeProportionalMillionths | The new proportional fee to use | No | Integer

# Peers

## peers

```shell
curl -u :<eclair_api_password> -X POST "http://localhost:8080/peers"

#with eclair-cli
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

## channels

```shell
curl -u :<eclair_api_password> -X POST "http://localhost:8080/channels"

#with eclair-cli
eclair-cli channels
```

> The above command returns:

```json
[
  {
    "nodeId": "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
    "channelId": "56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e",
    "state": "NORMAL",
    "data": {
      "commitments": {
        "localParams": {
          "nodeId": "036d65409c41ab7380a43448f257809e7496b52bf92057c09c4f300cbd61c50d96",
          "channelKeyPath": {
            "path": [
              698411009,
              4289979314,
              8627192,
              3309856639
            ]
          },
          "dustLimitSatoshis": 546,
          "maxHtlcValueInFlightMsat": 5000000000,
          "channelReserveSatoshis": 2300,
          "htlcMinimumMsat": 1,
          "toSelfDelay": 144,
          "maxAcceptedHtlcs": 30,
          "isFunder": true,
          "defaultFinalScriptPubKey": "a9148852d917c2f8cdf3eacea8015c35bfe57e98eede87",
          "globalFeatures": "",
          "localFeatures": "82"
        },
        "remoteParams": {
          "nodeId": "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
          "dustLimitSatoshis": 546,
          "maxHtlcValueInFlightMsat": 5000000000,
          "channelReserveSatoshis": 2300,
          "htlcMinimumMsat": 1,
          "toSelfDelay": 144,
          "maxAcceptedHtlcs": 30,
          "fundingPubKey": "030110991e6e23961f4c013fa70f76317bc75cf38df33ff7b448b510d1c7b09c94",
          "revocationBasepoint": "02a5a32c4dc63d0bb98a8fac8a57fd9d494b1ac843b87db3863d76b7c4bebd9026",
          "paymentBasepoint": "0304db8d9e0a46788e14f19a0be20e33eb198dc7e652f50de53e835a7d82f9a0f7",
          "delayedPaymentBasepoint": "03a5c1e85d9c21f21e8e0d15a44bc7240b7d3ec4469b042e4724a9a36293792351",
          "htlcBasepoint": "037bb6e277a1ab4d242a6e7969bad81055999946f2e7f87daac90c309680c14104",
          "globalFeatures": "",
          "localFeatures": "8a"
        },
        "channelFlags": 1,
        "localCommit": {
          "index": 181,
          "spec": {
            "htlcs": [],
            "feeratePerKw": 2382,
            "toLocalMsat": 227599428,
            "toRemoteMsat": 2400572
          },
          "publishableTxs": {
            "commitTx": "0200000000010156d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e0000000000ea5719800260090000000000001600148109e081865b9bc47082ebfc52fe17de92ec4fe25372030000000000220020b3e40418334e76872523d3a5cc98ae9a50399408bfe111890be710652c51937b04004830450221008894cb338bf8c7064d5bb8c08099deae980d204c4c888af2c85d6c35e3db6010022057de8f44ea1fec571461ef6517baa25d649edd583e675b2a9a80478eff635b0e01483045022100eecf61753bffeb1ba617c3085fb34fb15e9130c2b8eaf030c40d8bf9e4e7f36e0220197aebae5873b0a64a03c36d00c5108761d4f0b07f730803b3981978c672ee2d01475221030110991e6e23961f4c013fa70f76317bc75cf38df33ff7b448b510d1a7b09c942103647d13a308e012100c9e4a9512065f2c3048f8f0160c665952a8f4f077798a5d52ae2a261420",
            "htlcTxsAndSigs": []
          }
        },
        "remoteCommit": {
          "index": 181,
          "spec": {
            "htlcs": [],
            "feeratePerKw": 2382,
            "toLocalMsat": 2400572,
            "toRemoteMsat": 227599428
          },
          "txid": "b11f1947175ce1fc05a1f60378f5cec6345d9acb04763d526c762a2c18892bf8",
          "remotePerCommitmentPoint": "03128d3be2764bf853e19732135f6f2c8b348b1317051c8ea704b25468bbf373db"
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
        "localNextHtlcId": 82,
        "remoteNextHtlcId": 1,
        "originChannels": {},
        "remoteNextCommitInfo": "03a8ce1a067e9f0467f8d34f7d6ac25f90b31518bd3ce724f0a8448f2b8a745c92",
        "commitInput": {
          "outPoint": "0e7d63ce98dbaccd9c3061509e93b45adbeaf10997c4708213804da0edd6d756:0",
          "amountSatoshis": 230000
        },
        "remotePerCommitmentSecrets": null,
        "channelId": "56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e"
      },
      "shortChannelId": "565779x2711x0",
      "buried": true,
      "channelAnnouncement": {
        "nodeSignature1": "304502210085c10f513c26cfe195ff52bce9e309b6fc3f351194a3de64efc25e299bbca1b802207fcba062c4e14f5b49889a5a220a1a189958a484cf2ee51445fea9359e25e44401",
        "nodeSignature2": "30440220791753edd31a391806fdfa02c45560a54aa548fd1d3d8f3b05ca04420fe19dce0220109be60b94346dfb52d9f52187f890e9f04ef701a232ccdbcd99d4c05dabb90b01",
        "bitcoinSignature1": "3045022100a27439d43ba6907cd135a3349ce48dd864f5189b3d42937911fe282fafd703cb0220614388e3e06a7f228c25452e9425d4357bd0363fde9838bed575711bf5e8f4fe01",
        "bitcoinSignature2": "3045022100c8ec9d01fed71d91dc5161b06b4913b79c628c5d19d0c44cecf463a67d3fc0a5022051bd86094a17de9de0cf925ab62d58f39322d0363cb9be0aa7a28fd33a0854a301",
        "features": "",
        "chainHash": "6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000",
        "shortChannelId": "565779x2711x0",
        "nodeId1": "036d65409c41ab7380a43448f257809e7496b52bf92057c09c4f300cbd61c50d96",
        "nodeId2": "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
        "bitcoinKey1": "03647d13a308e012100c9e4a9512065f2c3048f8f0160c665952a8f4f077798a5d",
        "bitcoinKey2": "030110991e6e23961f4c013fa70f76317bc75cf38df33ff7b448b510d1c7b09c94"
      },
      "channelUpdate": {
        "signature": "3045022100eef406f8282b1115d4122f0e18c3b280378ef5fe1b827dd50fee627deeed986e0220629839a7425185d053d958037ee3817ba67fe6c74ead3ddc4ddb6fb3b5934f1001",
        "chainHash": "6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000",
        "shortChannelId": "565779x2711x0",
        "timestamp": 1553521796,
        "messageFlags": 1,
        "channelFlags": 0,
        "cltvExpiryDelta": 144,
        "htlcMinimumMsat": 1,
        "feeBaseMsat": 1000,
        "feeProportionalMillionths": 100,
        "htlcMaximumMsat": 230000000
      }
    }
  }
]
```

Returns the list of local channels, optionally filtered by remote node.

### HTTP Request

`POST http://localhost:8080/channels`

### Parameters

Parameter | Description | Optional | Type
--------- | ----------- | --------- | ---------
toRemoteNodeId | The remote node id to be used as filter for the channels | Yes | 32bytes-HexString (String)

## channel

```shell
curl -u :<eclair_api_password> -X POST -F channelId=<channel>  "http://localhost:8080/channel"

#with eclair-cli
eclair-cli channel --channelId=<channel>
```

> The above command returns:

```json
  {
    "nodeId": "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
    "channelId": "56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e",
    "state": "NORMAL",
    "data": {
      "commitments": {
        "localParams": {
          "nodeId": "036d65409c41ab7380a43448f257809e7496b52bf92057c09c4f300cbd61c50d96",
          "channelKeyPath": {
            "path": [
              698411009,
              4289979314,
              8627192,
              3309856639
            ]
          },
          "dustLimitSatoshis": 546,
          "maxHtlcValueInFlightMsat": 5000000000,
          "channelReserveSatoshis": 2300,
          "htlcMinimumMsat": 1,
          "toSelfDelay": 144,
          "maxAcceptedHtlcs": 30,
          "isFunder": true,
          "defaultFinalScriptPubKey": "a9148852d917c2f8cdf3eacea8015c35bfe57e98eede87",
          "globalFeatures": "",
          "localFeatures": "82"
        },
        "remoteParams": {
          "nodeId": "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
          "dustLimitSatoshis": 546,
          "maxHtlcValueInFlightMsat": 5000000000,
          "channelReserveSatoshis": 2300,
          "htlcMinimumMsat": 1,
          "toSelfDelay": 144,
          "maxAcceptedHtlcs": 30,
          "fundingPubKey": "030110991e6e23961f4c013fa70f76317bc75cf38df33ff7b448b510d1c7b09c94",
          "revocationBasepoint": "02a5a32c4dc63d0bb98a8fac8a57fd9d494b1ac843b87db3863d76b7c4bebd9026",
          "paymentBasepoint": "0304db8d9e0a46788e14f19a0be20e33eb198dc7e652f50de53e835a7d82f9a0f7",
          "delayedPaymentBasepoint": "03a5c1e85d9c21f21e8e0d15a44bc7240b7d3ec4469b042e4724a9a36293792351",
          "htlcBasepoint": "037bb6e277a1ab4d242a6e7969bad81055999946f2e7f87daac90c309680c14104",
          "globalFeatures": "",
          "localFeatures": "8a"
        },
        "channelFlags": 1,
        "localCommit": {
          "index": 181,
          "spec": {
            "htlcs": [],
            "feeratePerKw": 2382,
            "toLocalMsat": 227599428,
            "toRemoteMsat": 2400572
          },
          "publishableTxs": {
            "commitTx": "0200000000010156d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e0000000000ea5719800260090000000000001600148109e081865b9bc47082ebfc52fe17de92ec4fe25372030000000000220020b3e40418334e76872523d3a5cc98ae9a50399408bfe111890be710652c51937b04004830450221008894cb338bf8c7064d5bb8c08099deae980d204c4c888af2c85d6c35e3db6010022057de8f44ea1fec571461ef6517baa25d649edd583e675b2a9a80478eff635b0e01483045022100eecf61753bffeb1ba617c3085fb34fb15e9130c2b8eaf030c40d8bf9e4e7f36e0220197aebae5873b0a64a03c36d00c5108761d4f0b07f730803b3981978c672ee2d01475221030110991e6e23961f4c013fa70f76317bc75cf38df33ff7b448b510d1a7b09c942103647d13a308e012100c9e4a9512065f2c3048f8f0160c665952a8f4f077798a5d52ae2a261420",
            "htlcTxsAndSigs": []
          }
        },
        "remoteCommit": {
          "index": 181,
          "spec": {
            "htlcs": [],
            "feeratePerKw": 2382,
            "toLocalMsat": 2400572,
            "toRemoteMsat": 227599428
          },
          "txid": "b11f1947175ce1fc05a1f60378f5cec6345d9acb04763d526c762a2c18892bf8",
          "remotePerCommitmentPoint": "03128d3be2764bf853e19732135f6f2c8b348b1317051c8ea704b25468bbf373db"
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
        "localNextHtlcId": 82,
        "remoteNextHtlcId": 1,
        "originChannels": {},
        "remoteNextCommitInfo": "03a8ce1a067e9f0467f8d34f7d6ac25f90b31518bd3ce724f0a8448f2b8a745c92",
        "commitInput": {
          "outPoint": "0e7d63ce98dbaccd9c3061509e93b45adbeaf10997c4708213804da0edd6d756:0",
          "amountSatoshis": 230000
        },
        "remotePerCommitmentSecrets": null,
        "channelId": "56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e"
      },
      "shortChannelId": "565779x2711x0",
      "buried": true,
      "channelAnnouncement": {
        "nodeSignature1": "304502210085c10f513c26cfe195ff52bce9e309b6fc3f351194a3de64efc25e299bbca1b802207fcba062c4e14f5b49889a5a220a1a189958a484cf2ee51445fea9359e25e44401",
        "nodeSignature2": "30440220791753edd31a391806fdfa02c45560a54aa548fd1d3d8f3b05ca04420fe19dce0220109be60b94346dfb52d9f52187f890e9f04ef701a232ccdbcd99d4c05dabb90b01",
        "bitcoinSignature1": "3045022100a27439d43ba6907cd135a3349ce48dd864f5189b3d42937911fe282fafd703cb0220614388e3e06a7f228c25452e9425d4357bd0363fde9838bed575711bf5e8f4fe01",
        "bitcoinSignature2": "3045022100c8ec9d01fed71d91dc5161b06b4913b79c628c5d19d0c44cecf463a67d3fc0a5022051bd86094a17de9de0cf925ab62d58f39322d0363cb9be0aa7a28fd33a0854a301",
        "features": "",
        "chainHash": "6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000",
        "shortChannelId": "565779x2711x0",
        "nodeId1": "036d65409c41ab7380a43448f257809e7496b52bf92057c09c4f300cbd61c50d96",
        "nodeId2": "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
        "bitcoinKey1": "03647d13a308e012100c9e4a9512065f2c3048f8f0160c665952a8f4f077798a5d",
        "bitcoinKey2": "030110991e6e23961f4c013fa70f76317bc75cf38df33ff7b448b510d1c7b09c94"
      },
      "channelUpdate": {
        "signature": "3045022100eef406f8282b1115d4122f0e18c3b280378ef5fe1b827dd50fee627deeed986e0220629839a7425185d053d958037ee3817ba67fe6c74ead3ddc4ddb6fb3b5934f1001",
        "chainHash": "6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000",
        "shortChannelId": "565779x2711x0",
        "timestamp": 1553521796,
        "messageFlags": 1,
        "channelFlags": 0,
        "cltvExpiryDelta": 144,
        "htlcMinimumMsat": 1,
        "feeBaseMsat": 1000,
        "feeProportionalMillionths": 100,
        "htlcMaximumMsat": 230000000
      }
    }
  }

```

Returns detailed information about a local channel.

### HTTP Request

`POST http://localhost:8080/channel`

### Parameters

Parameter | Description | Optional | Type
--------- | ----------- | --------- | ---------
channelId | The channel id of the requested channel | No | 32bytes-HexString (String)

# Network

A set of API to query the network view of eclair.

## allnodes

```shell
curl -u :<eclair_api_password> -X POST "http://localhost:8080/allnodes"

#with eclair-cli
eclair-cli allnodes
```

> The above command returns:

```json
[
  {
    "signature": "3044022072537adb1a10dab3a4630b578e678f0b5b7f2916af65b5e2a1f71e751b8dddc802200903b8a33fc154b4542acee481446dd674238256d354249d7d10408c413201f201",
    "features": "",
    "timestamp": 1553000829,
    "nodeId": "03a8334aba5660e241468e2f0deb2526bfd50d0e3fe808d882913e39094dc1a028",
    "rgbColor": "#33cccc",
    "alias": "cosmicApotheosis",
    "addresses": [
      "138.229.205.237:9735"
    ]
  },
  {
    "signature": "304502210080e1836a98f69133873a35bea4b9b9d5f5abdad376d526fb2f6ee46aaa77f62b022026ba53b630d76ae9d6c1beec134244a79669a31eb5e6a7cc2038aaefff84382b01",
    "features": "",
    "timestamp": 1553008703,
    "nodeId": "036a54f02d2186de192e4bcec3f7b47adb43b1fa965793387cd2471990ce1d236b",
    "rgbColor": "#1d236b",
    "alias": "capacity.network",
    "addresses": [
      "95.216.16.21:9735",
      "[2a01:4f9:2a:106a:0:0:0:2]:9736"
    ]
  }
]
```

Returns information about all public nodes on the lightning network, this information is taken from the _node_announcement_ network message.

## allchannels

```shell
curl -u :<eclair_api_password> -X POST "http://localhost:8080/allchannels"

#with eclair-cli
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

Returns non detailed information about all public channels in the network.

## allupdates

```shell
curl -u :<eclair_api_password> -X POST "http://localhost:8080/allupdates"

#with eclair-cli
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
    "messageFlags": 1,
    "channelFlags": 1,
    "cltvExpiryDelta": 144,
    "htlcMinimumMsat": 1000,
    "feeBaseMsat": 1000,
    "feeProportionalMillionths": 1,
    "htlcMaximumMsat": 2970000000
  },
  {
    "signature": "304402201848be0aff000ec279e2d043d1bde8b2c76a9277dab72b9d1523468961c5d78e0220541e233977f2288684dab6ec168e43dc3459d093e901dd6f2b5238c2b888845a01",
    "chainHash": "6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000",
    "shortChannelId": "562890x809x0",
    "timestamp": 1552993875,
    "messageFlags": 1,
    "channelFlags": 1,
    "cltvExpiryDelta": 144,
    "htlcMinimumMsat": 1000,
    "feeBaseMsat": 1000,
    "feeProportionalMillionths": 2500,
    "htlcMaximumMsat": 3960000000
  }
]
```

Returns detailed information about all public channels in the network, the information is mostly taken from the _channel_update_ network messages.

<aside class="warning">
The allupdates API is CPU intensive for eclair and might slow down the application.
</aside>

### HTTP Request

`POST http://localhost:8080/allupdates`

### Parameters

Parameter | Description | Optional | Type
--------- | ----------- | --------- | --------
nodeId | The node id of the node to be used as filter for the updates | Yes | 32bytes-HexString (String)


# Payments

Interfaces for sending and receiving payments through eclair.

## receive

```shell
curl -u :<eclair_api_password> -X POST -F description=<some_description> \
     -F amountMsat=<some_amount> "http://localhost:8080/receive"

#with eclair-cli
eclair-cli receive --description=<some_description> --amountMsat=<some_amount>
```

> The above command returns:

```
lnbc4200n1pwf36wlpp5dhysplnjqrqzsvlhct07csechwrz7usr5u69e68v5759m4qz46eqdzz2pshjmt9de6zqen0wgsrgv3sypcxj7r9d3ejqct5ypekzar0wd5xjuewwpkxzcm99cxqzjccqp2rzjqwe3ukal9nd7z9d5sk3tfq88pg0089g6phrd7jcjxtsw2meaecvdvzyu2sqq94gqqyqqqqlgqqqqqzsqpcptp3ys9qgxcfnazf7cqluus56anmur8jy2yzj4wcscpw08u8hl5hjwv0uhm7dv3vvqh623289chcen0a35ynjkk8kd6tz38syntfg2gp6nz0wa
```

Create a **BOLT11** payment invoice.

### HTTP Request

`POST http://localhost:8080/receive`

### Parameters

Parameter | Description | Optional | Type
--------- | ----------- | --------- | ---------
description | A description for the invoice | No | String
amountMsat | Amount in millisatoshi for this invoice | Yes | Millisatoshi (integer)
expireIn | Number of seconds that the invoice will be valid | Yes | Seconds (integer)

## send

```shell
curl -u :<eclair_api_password> -X POST -F invoice=<some_invoice> "http://localhost:8080/send"

#with eclair-cli
eclair-cli send --invoice=<some_invoice>
```
> The above command returns:

```json
{
  "amountMsat": 151015,
  "paymentHash": "427309c52a46f8c005ad840c106fcdc9c4c60f95769525bc91c4a742133e4fe3",
  "paymentPreimage": "14b0c3443226f8a570332737501e0945910e44778ad1740b6f036f8016fb9982",
  "route": [
    {
      "nodeId": "036d65409c41ab7380a43448f257809e7496b52bf92057c09c4f300cbd61c50d96",
      "nextNodeId": "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
      "lastUpdate": {
        "signature": "3045022100be4eafab56a122bdfa740f7a35d3ffc6080f08882d9231f80d802ac15192a82a022006ac2679d5ca3b93738b6dd970855c6e3eb264f006c0bd2dc6fb14df5ba634c801",
        "chainHash": "6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000",
        "shortChannelId": "565779x2711x0",
        "timestamp": 1553527352,
        "messageFlags": 1,
        "channelFlags": 0,
        "cltvExpiryDelta": 144,
        "htlcMinimumMsat": 1,
        "feeBaseMsat": 1000,
        "feeProportionalMillionths": 100,
        "htlcMaximumMsat": 230000000
      }
    },
    {
      "nodeId": "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
      "nextNodeId": "03d06758583bb5154774a6eb221b1276c9e82d65bbaceca806d90e20c108f4b1c7",
      "lastUpdate": {
        "signature": "3045022100aaddab2207ece615e5e6546de4b1d1535be6926ba24c9e35596f7eb869d0d48302202c5a28721962e5e3e2b1b08a0fd2e739b77fd9d5a9f7cdbb7f8b2542aa3b6d7001",
        "chainHash": "6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000",
        "shortChannelId": "568496x2864x1",
        "timestamp": 1553522210,
        "messageFlags": 1,
        "channelFlags": 0,
        "cltvExpiryDelta": 144,
        "htlcMinimumMsat": 1000,
        "feeBaseMsat": 1000,
        "feeProportionalMillionths": 100,
        "htlcMaximumMsat": 16777215000
      }
    }
  ]
}
```

Pays a **BOLT11** invoice sending the money to the recipient.

### HTTP Request

`POST http://localhost:8080/send`

### Parameters

Parameter | Description | Optional | Type
--------- | ----------- | --------- | ---------
invoice | The invoice you want to pay | No | String
amountMsat | Amount in to pay if the invoice does not have one | Yes | Millisatoshi (integer)

## sendToNode

```shell
curl -u :<eclair_api_password> -X POST -F nodeId=<some_node> \
	-F amountMsat=<amount> -F paymentHash=<some_hash> "http://localhost:8080/sendtonode"

#with eclair-cli
eclair-cli sendtonode --nodeId=<some_node> --amountMsat=<amount> --paymentHash=<some_hash>
```
> The above command returns:

```json
{
  "amountMsat": 151015,
  "paymentHash": "427309c52a46f8c005ad840c106fcdc9c4c60f95769525bc91c4a742133e4fe3",
  "paymentPreimage": "14b0c3443226f8a570332737501e0945910e44778ad1740b6f036f8016fb9982",
  "route": [
    {
      "nodeId": "036d65409c41ab7380a43448f257809e7496b52bf92057c09c4f300cbd61c50d96",
      "nextNodeId": "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
      "lastUpdate": {
        "signature": "3045022100be4eafab56a122bdfa740f7a35d3ffc6080f08882d9231f80d802ac15192a82a022006ac2679d5ca3b93738b6dd970855c6e3eb264f006c0bd2dc6fb14df5ba634c801",
        "chainHash": "6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000",
        "shortChannelId": "565779x2711x0",
        "timestamp": 1553527352,
        "messageFlags": 1,
        "channelFlags": 0,
        "cltvExpiryDelta": 144,
        "htlcMinimumMsat": 1,
        "feeBaseMsat": 1000,
        "feeProportionalMillionths": 100,
        "htlcMaximumMsat": 230000000
      }
    },
    {
      "nodeId": "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
      "nextNodeId": "03d06758583bb5154774a6eb221b1276c9e82d65bbaceca806d90e20c108f4b1c7",
      "lastUpdate": {
        "signature": "3045022100aaddab2207ece615e5e6546de4b1d1535be6926ba24c9e35596f7eb869d0d48302202c5a28721962e5e3e2b1b08a0fd2e739b77fd9d5a9f7cdbb7f8b2542aa3b6d7001",
        "chainHash": "6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000",
        "shortChannelId": "568496x2864x1",
        "timestamp": 1553522210,
        "messageFlags": 1,
        "channelFlags": 0,
        "cltvExpiryDelta": 144,
        "htlcMinimumMsat": 1000,
        "feeBaseMsat": 1000,
        "feeProportionalMillionths": 100,
        "htlcMaximumMsat": 16777215000
      }
    }
  ]
}
```

Pays a **BOLT11** invoice sending the money to the recipient.

### HTTP Request

`POST http://localhost:8080/sendtonode`

### Parameters

Parameter | Description | Optional | Type
--------- | ----------- | --------- | ---------
nodeId | The recipient of this payment | No | 32bytes-HexString (String)
amountMsat | Amount in to pay | No | Millisatoshi (integer)
paymentHash | The payment hash for this payment | No | 32bytes-HexString (String)

## checkpayment

```shell
curl -u :<eclair_api_password> -X POST -F paymentHash=<some_hash> "http://localhost:8080/checkpayment"

#with eclair-cli
eclair-cli checkpayment --paymentHash=<some_hash>
```
> The above command returns:

```
true
```

Check whether the given _payment_hash_ has been paid.

### HTTP Request

`POST http://localhost:8080/checkpayment`

### Parameters

Parameter | Description | Optional | Type
--------- | ----------- | --------- | ---------
paymentHash | The payment hash you want to check | No | 32bytes-HexString (String)

## parseinvoice

```shell
curl -u :<eclair_api_password> -X POST -F invoice=<some_bolt11invoice> "http://localhost:8080/parseinvoice"

#with eclair-cli
eclair-cli parseinvoice --invoice=<some_bolt11invoice>
```
> The above command returns:

```json
{
  "prefix": "lntb",
  "amount": 123,
  "timestamp": 1545150074,
  "nodeId": "0217e8131150248362318e06afc5adc29fc61ff0e6a34c390d8907e13576e70fe0",
  "description": "wassa wassa",
  "paymentHash": "bb9cf74c2c1bb6cb169fa60f0a291867fb91f29efb2691bfb7fa0e492d59ec80",
  "expiry": 2333,
  "minFinalCltvExpiry": null
}
```

Returns detailed information about the given invoice.

### HTTP Request

`POST http://localhost:8080/parseinvoice`

### Parameters

Parameter | Description | Optional | Type
--------- | ----------- | --------- | ---------
invoice | The invoice you want to decode | No | String

# Route

## findroute

```shell
curl -u :<eclair_api_password> -X POST -F invoice=<some_bolt11invoice> "http://localhost:8080/findroute"

#with eclair-cli
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

### HTTP Request

`POST http://localhost:8080/findroute`

### Parameters

Parameter | Description | Optional | Type
--------- | ----------- | --------- | ---------
invoice | The invoice containing the destination | No | String
amountMsat | The amount that should go through the route | Yes | Millisatoshi (Integer)

## findrouteToNode

```shell
curl -u :<eclair_api_password> -X POST -F nodeId=<some_node> \
     -F amountMsat=<some_amount> "http://localhost:8080/findroutetonode"

#with eclair-cli
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

Finds a route to the node.

### HTTP Request

`POST http://localhost:8080/findroutetonode`

### Parameters

Parameter | Description | Optional | Type
--------- | ----------- | --------- | ---------
nodeId | The destination of the route | No | 32bytes-HexString (String)
amountMsat | The amount that should go through the route | No | Millisatoshi (Integer)

# Miscellaneous

## audit

```shell
curl -u :<eclair_api_password> -X POST "http://localhost:8080/audit"

#with eclair-cli
eclair-cli audit
```
> The above command returns:

```json
{
   "sent":[
      {
         "amount":150000,
         "feesPaid":1015,
         "paymentHash":"427309c52a46f8c005ad840c106fcdc9c4c60f95769525bc91c4a742133e4fe3",
         "paymentPreimage":"14b0c3443226f8a570332737501e0945910e44778ad1740b6f036f8016fb9982",
         "toChannelId":"56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e",
         "timestamp":1553527391064
      }
   ],
   "received":[
      {
         "amount":150000,
         "paymentHash":"427309c52a46f8c005ad840c106fcdc9c4c60f95769525bc91c4a742133e4fe3",
         "fromChannelId":"56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e",
         "timestamp":1553527391064
      }
   ],
   "relayed":[
      {
         "amountIn":150001,
         "amountOut":150000,
         "paymentHash":"427309c52a46f8c005ad840c106fcdc9c4c60f95769525bc91c4a742133e4fe3",
         "fromChannelId":"56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e",
         "toChannelId":"56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e",
         "timestamp":1553527391064
      }
   ]
}
```

Retrieves information about payments handled by this node such as: sent, received and relayed payments.

### HTTP Request

`POST http://localhost:8080/audit`

### Parameters

Parameter | Description | Optional | Type
--------- | ----------- | --------- | ---------
from | Filters elements no older than this unix-timestamp  | Yes | Unix timestamp (Integer)
to | Filters elements no younger than this unix-timestamp  | Yes | Unix timestamp (Integer)

## networkfees

```shell
curl -u :<eclair_api_password> -X POST "http://localhost:8080/networkfees"

#with eclair-cli
eclair-cli networkfees
```
> The above command returns:

```json
[
  {
    "remoteNodeId": "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
    "channelId": "57d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e",
    "txId": "0e7d63ce98dbaccd9c3061509e93b45adbeaf10997c4708213804da0edd6d757",
    "feeSat": 3382,
    "txType": "funding",
    "timestamp": 1551798422110
  }
]
```

Retrieves information about on-chain fees paid during channel operations.

### HTTP Request

`POST http://localhost:8080/networkfees`

### Parameters

Parameter | Description | Optional | Type
--------- | ----------- | --------- | ---------
from | Filters elements no older than this unix-timestamp  | Yes | Unix timestamp (Integer)
to | Filters elements no younger than this unix-timestamp  | Yes | Unix timestamp (Integer)

## channelstats

```shell
curl -u :<eclair_api_password> -X POST "http://localhost:8080/channelstats"

#with eclair-cli
eclair-cli channelstats
```
> The above command returns:

```json
[
  {
    "channelId": "57d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e",
    "avgPaymentAmountSatoshi": 123,
    "paymentCount": 55,
    "relayFeeSatoshi": 3,
    "networkFeeSatoshi": 3382
  }
]
```

Retrieves information about local channels, the information is then aggregated in order to display
statistics about the routing activity of the channels.

### HTTP Request

`POST http://localhost:8080/channelstats`

# Websocket

## ws

This is a simple [websocket](https://tools.ietf.org/html/rfc6455) that will output the _payment_hash_ of incoming payments as soon as they are received.

### HTTP Request

`POST http://localhost:8080/ws`



