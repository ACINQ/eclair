#set -x

# VARs

# location of data dirs
TEMPDIR=/tmp/eclair

#bitcoind
temp=`ls -rtd ../eclair-core/target/bitcoin-* | tail -1`
BTCD=$temp/bin/bitcoind
BTCCLI=$temp/bin/bitcoin-cli
temp=`ls -rt ../eclair-node/target/eclair-* | tail -1`
ECLAIRD=$temp
temp=`ls -rt ../eclair-node-gui/target/eclair-* | tail -1`
ECLAIRGUI=$temp
ECLAIRCLI=../eclair-core/eclair-cli

# dependancies
which curl
if (( $? != 0 )); then
  echo "could not find curl on PATH please install"
  exit 1
fi

which jq
if (( $? != 0 )); then
  echo "could not find jq on PATH please install"
  exit 1
fi

which java
if (( $? != 0 )); then
  echo "could not find java on PATH please install"
  exit 1
fi

javaversion=`java --version`

echo "=========================================================="
echo "Bitcoin    : $BTCD"
echo "BitcoinCli : $BTCCLI"
echo "Eclaird    : $ECLAIRD"
echo "EclairGUI  : $ECLAIRGUI"
echo "EclairCLI  : $ECLAIRCLI"
echo " "
echo "using temp directory: $TEMPDIR"
echo "this will be deleted every time this script is run"
echo "java verion: $javaversion"
echo "=========================================================="

if [ ! -f $BTCD ]; then
 echo "could not find bitcoind at $BTCD"
 echo "run mvn install to download"
 exit 1
fi
if [ ! -f $BTCCLI ]; then
 echo "could not find bitcoin-cli at $BTCCLI"
 echo "run mvn install to download"
 exit 1
fi
if [ ! -f $ECLAIRD ]; then
 echo "could not find Eclair JAR at $ECLAIRD"
 echo "run mvn install to create"
 exit 1
fi
if [ ! -f $ECLAIRGUI ]; then
 echo "could not find eclair GUI Jar at $ECLAIRGUI"
 echo "run mvn install to create"
 exit 1
fi
if [ ! -f $ECLAIRCLI ]; then
 echo "could not find eclair-cli at $ECLAIRCLI"
 exit 1
fi

if [ -d $TEMPDIR ]; then
 echo "remove $TEMPDIR"
 rm -rf $TEMPDIR
fi

echo "create $TEMPDIR"
mkdir $TEMPDIR

for i in 1 2 3 4 5
do
  mkdir $TEMPDIR/bitcoin-reg${i}
  cat <<EOFBTC >  $TEMPDIR/bitcoin-reg${i}/bitcoin.conf
server=1
txindex=1
regtest=1
rpcallowip=127.0.0.1/31
[regtest]
bind=127.0.0.1:1000${i}
addnode=127.0.0.1:10001
addnode=127.0.0.1:10002
addnode=127.0.0.1:10003
addnode=127.0.0.1:10004
addnode=127.0.0.1:10005
rpcuser=foo
rpcpassword=bar
rpcport=1001${i}
zmqpubrawblock=tcp://127.0.0.1:1002${i}
zmqpubrawtx=tcp://127.0.0.1:1002${i}
EOFBTC
  echo "starting bitcoind $i"
  $BTCD -datadir=$TEMPDIR/bitcoin-reg${i} 2>&1 >/dev/null &
  mkdir $TEMPDIR/eclair-reg${i}

  cat <<EOFECL >  $TEMPDIR/eclair-reg${i}/eclair.conf
eclair {
  chain="regtest"
  server {
    port = 1100${i}
  }
  api {
    enabled = true
    port = 1101${i}
    password=password
  }
  bitcoind {
    rpcport = 1001${i}
    zmqblock = "tcp://127.0.0.1:1002${i}"
    zmqtx = "tcp://127.0.0.1:1002${i}"
  }
  node-alias="regtest-${i}"
  gui {
    unit = btc
  }
}  
EOFECL
  
done



echo "waiting 20 secs for bitcoind to startup"
sleep 20

echo "generate 1000 blocks so we have funds to spend"
address=`$BTCCLI -datadir=$TEMPDIR/bitcoin-reg3 getnewaddress`
$BTCCLI -datadir=$TEMPDIR/bitcoin-reg3 generatetoaddress 1000 $address 2>&1 >/dev/null


for i in 1 2 4 5 
do
  echo "sending funds to bitcoind $i"
  address=`$BTCCLI -datadir=$TEMPDIR/bitcoin-reg${i} getnewaddress`
  $BTCCLI -datadir=$TEMPDIR/bitcoin-reg3 sendtoaddress $address 1
done

echo "generating another 6 blocks"
$BTCCLI -datadir=$TEMPDIR/bitcoin-reg3 generatetoaddress 6 $address


echo "starting Eclair nodes"
java -Declair.datadir=$TEMPDIR/eclair-reg1 -Dconfig.file=$TEMPDIR/eclair-reg1/eclair.conf -jar $ECLAIRGUI >$TEMPDIR/eclair-reg1/sdtout.log 2>&1 &
java -Declair.datadir=$TEMPDIR/eclair-reg2 -Dconfig.file=$TEMPDIR/eclair-reg2/eclair.conf -jar $ECLAIRD   >$TEMPDIR/eclair-reg2/sdtout.log 2>&1 &
java -Declair.datadir=$TEMPDIR/eclair-reg3 -Dconfig.file=$TEMPDIR/eclair-reg3/eclair.conf -jar $ECLAIRD   >$TEMPDIR/eclair-reg3/sdtout.log 2>&1 &
java -Declair.datadir=$TEMPDIR/eclair-reg4 -Dconfig.file=$TEMPDIR/eclair-reg4/eclair.conf -jar $ECLAIRD   >$TEMPDIR/eclair-reg4/sdtout.log 2>&1 &
java -Declair.datadir=$TEMPDIR/eclair-reg5 -Dconfig.file=$TEMPDIR/eclair-reg5/eclair.conf -jar $ECLAIRGUI >$TEMPDIR/eclair-reg5/sdtout.log 2>&1 &

echo "sleeping 20 secs whilst eclair nodes start"
sleep 20
for i in 1 2 3 4 5
do
  while true; do
    echo "checking node $i"
    nodeidi=`$ECLAIRCLI -a localhost:1101$i -p password getinfo | jq ".nodeId" | sed -e s/\"//g`
    if (( ${#nodeidi} > 10 )); then
      echo "node $i started"
      break
    fi
    sleep 5
  done 
done

for i in 1 2 3 4
do
  echo i is $i
  nodeidi=`$ECLAIRCLI -a localhost:1101$i -p password getinfo | jq ".nodeId" | sed -e s/\"//g`
  echo i nodeid is [$nodeidi]
  j=$((i+1)) 
  echo j is $j
  nodeidj=`$ECLAIRCLI -a localhost:1101$j -p password getinfo | jq ".nodeId" | sed -e s/\"//g`
  echo "connect and open channels between $i and $j"
  $ECLAIRCLI -a localhost:1101$i -p password connect --uri=$nodeidj@localhost:1100$j
  $ECLAIRCLI -a localhost:1101$i -p password open --nodeId=$nodeidj --fundingSatoshis=10000000
  $ECLAIRCLI -a localhost:1101$j -p password open --nodeId=$nodeidi --fundingSatoshis=10000000
done

sleep 5

echo "generating another 6 blocks"
$BTCCLI -datadir=$TEMPDIR/bitcoin-reg3 generatetoaddress 6 $address

echo "to create aliases run:"
echo "source ./regtest_alias.sh"
