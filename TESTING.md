# Testing eclair and lightningd

## Configure bitcoind to run in regtest mode
Important: you need a segwit version of bitcoin core for this test (see https://github.com/sipa/bitcoin/tree/segwit-master).
Make sure that bitcoin-cli is on the path and edit ~/.bitcoin/bitcoin.conf and add:
```shell
server=1
regtest=1
rpcuser=***
rpcpassword=***
```

To check that segwit is enabled run:
```shell
bitcoin-cli getblockchaininfo
```
and check bip9_softforks:

```
...
"bip9_softforks": {
    "csv": {
      "status": "active",
      "startTime": 0,
      "timeout": 999999999999
    },
    "witness": {
      "status": "active",
      "startTime": 0,
      "timeout": 999999999999
    }
  }
```

## Start bitcoind
Mine enough blocks to activate segwit blocks:
```shell
bitcoin-cli generate 500
```
##
Start lightningd (here we’ll use port 46000)
```shell
lightningd --port 46000
```
##
Start eclair:
```shell
mvn exec:java -Dexec.mainClass=fr.acinq.eclair.Boot
```
## Tell eclair to connect to lightningd

```shell
curl -X POST -H "Content-Type: application/json" -d '{
     "method": "connect",
     "params" : [ "localhost", 46000, 3000000 ]
 }' http://localhost:8080
```
Since eclair is funder, it will create and publish the anchor tx

Mine a few blocks to confirm the anchor tx:
```shell
bitcoin-cli generate 10
```
eclair and lightningd are now both in NORMAL state.
You can check this by running:
```shell
lightning-cli getpeers
```
or
```shell
curl -X POST -H "Content-Type: application/json" -d '{
     "method": "list",
     "params" : [ ]
 }' http://localhost:8080
 ```


## Tell eclair to send a htlc
We’ll use the following values for R and H:
```
R = 0102030405060708010203040506070801020304050607080102030405060708
H = 8cf3e5f40cf025a984d8e00b307bbab2b520c91b2bde6fa86958f8f4e7d8a609
```

You’ll need a unix timestamp that is not too far into the future. Now + 100000 is fine:
```shell
curl -X POST -H "Content-Type: application/json" -d "{
    \"method\": \"addhtlc\",
    \"params\" : [ 70000000, \"8cf3e5f40cf025a984d8e00b307bbab2b520c91b2bde6fa86958f8f4e7d8a609\", $((`date +%s` + 100000)), \"021acf75c92318d3723098294d2a6a4b08d9abba2ebb5f2df2b4a8e9153e96a5f4\"  ]
}" http://localhost:8080
```

## Tell eclair to commit its changes
```shell
curl -X POST -H "Content-Type: application/json" -d "{
    \"method\": \"sign\",
    \"params\" : [ \"d3f056a084e266ad06ea1ca28a1e080ca07c6b61fac7ce116e48a5c31d688eee\" ]
}" http://localhost:8080
```
## Tell lightningd to fulfill the HTLC:
```shell
./lightning-cli fulfillhtlc 03befb4f8ad1d87d4c41acbb316791fe157f305caf2123c848f448975aaf85c1bb 0102030405060708010203040506070801020304050607080102030405060708
```
Check balances on both eclair and lightningd

## Close the channel
```shell
./lightning-cli close 03befb4f8ad1d87d4c41acbb316791fe157f305caf2123c848f448975aaf85c1bb
```
Mine a few blocks to bury the closing tx
```shell
bitcoin-cli generate 10
```
The channel is now in CLOSED state






