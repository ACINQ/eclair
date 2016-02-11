# Testing eclair and lightnind

## Configure bitcoind to run in regtest mode
edit ~/.bitcoin/bitcoin.conf and add:
```shell
server=1
regtest=1
rpcuser=***
rpcpassword=***
```

make sure that bitcoin-cli is on the path

## Start bitcoind
Mine a few blocks:
```shell
bitcoin-cli generate 101
```
##
Start lighnind (here we’ll use port 50000)
```shell
lightningd --port 50000
```
##
Start eclair:
```shell
mvn exec:java -Dexec.mainClass=fr.acinq.eclair.Boot
```
## Tell eclair to connect to lightnind:

```shell
curl -X POST -H "Content-Type: application/json" -d '{
>     "id": 12345,
>     "method": "connect",
>     "params" : [ "localhost", 50000, 1000000 ]
> }' "http://localhost:8080"
```
Since eclair is funder, it will create and publish the anchor tx

Mine a few blocks to confirm the anchor tx:
```shell
bitcoin-cli generate 10
```
eclair and lightnind are now both in NORMAL state (high priority for eclair, low priority for lightnind)

## Tell eclair to send a htlc
We’ll use the following values for R and H:
```
R = 0102030405060708010203040506070801020304050607080102030405060708
H = 8cf3e5f40cf025a984d8e00b307bbab2b520c91b2bde6fa86958f8f4e7d8a609
```

You’ll need a unix timestamp that is not too far into the future. Now + 100000 is fine:
```shell
echo `date +%s` + 100000 | bc
```

```shell
curl -X POST -H "Content-Type: application/json" -d '{
    "id": 12345,
    "method": "addhtlc",
    "params" : [ "akka://default/user/register/handler-1/channel", 100000, "8cf3e5f40cf025a984d8e00b307bbab2b520c91b2bde6fa86958f8f4e7d8a609", YOURTIMESTAMP  ]
}' "http://localhost:8080"
```

## Tell lighningd to fulfill the HTLC:
```shell
./lightning-cli fulfillhtlc 0277863c1e40a2d4934ccf18e6679ea949d36bb0d1333fb098e99180df60d0195a 0102030405060708010203040506070801020304050607080102030405060708
```
Check balances on both eclair and lightningd

## Close the channel
```shell
./lightning-cli close 0277863c1e40a2d4934ccf18e6679ea949d36bb0d1333fb098e99180df60d0195a
```






