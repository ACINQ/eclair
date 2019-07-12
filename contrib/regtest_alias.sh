echo "creating aliases"

TEMPDIR=/tmp/eclair
temp=`ls -rtd $(pwd)/../eclair-core/target/bitcoin-* | tail -1`
BTCCLI=$temp/bin/bitcoin-cli
ECLAIRCLI=$(pwd)/../eclair-core/eclair-cli
for i in 1 2 3 4 5
do
  alias b${i}="$BTCCLI -datadir=$TEMPDIR/bitcoin-reg${i}"
  alias e${i}="$ECLAIRCLI -a localhost:1101${i} -p password"
done

echo "alias b1,b2,b2,b4,b5 allow connection to bitcoind - e.g. b1 getwalletinfo"
echo "alias e1,e2,e2,e4,e5 allow connection to eclair - e.g. e1 getinfo"

alias stopbtc="b1 stop; b2 stop; b3 stop; b4 stop; b5 stop"
alias stope="ps -ef | grep eclair.datadir=/tmp/eclair/eclair-reg | grep -v grep | awk '{print \$2}' | xargs kill"
alias stopall="stope;stopbtc"
echo "alias stope to stop eclair"
echo "alias stopbtc to stop bitcoind"
echo "alias stopall to stop both"


address=`$BTCCLI -datadir=$TEMPDIR/bitcoin-reg3 getnewaddress`
alias generate="$BTCCLI -datadir=$TEMPDIR/bitcoin-reg3 generatetoaddress 1 $address"
alias generate10="$BTCCLI -datadir=$TEMPDIR/bitcoin-reg3 generatetoaddress 10 $address"
alias generate100="$BTCCLI -datadir=$TEMPDIR/bitcoin-reg3 generatetoaddress 100 $address"
alias generate1000="$BTCCLI -datadir=$TEMPDIR/bitcoin-reg3 generatetoaddress 1000 $address"

echo "alias generate generate10 generate100 generate1000 to generate blocks"
