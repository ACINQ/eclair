# Usage

## Command line with eclair-cli

### Windows user

On windows, you will have to install Bash first. We recommend installing Git Bash which is packed in the [Git installer from git-scm](https://git-scm.com/downloads).

Then you'll have to install jq:

- Get the latest windows version from https://stedolan.github.io/jq/download/
- Rename the `jq-win64.exe` file to `jq.exe` and move it to `C:/Users/your_name/bin`

## eclair-cli installation

- Download the eclair-cli file from [our sources](https://github.com/ACINQ/eclair/blob/master/eclair-core/eclair-cli)
- (optional) Move the file to `~/bin`
- Enable the [JSON API](./API.md) in your `eclair.conf` settings.

Run this command to list the available calls:

```shell
./eclair-cli -p <api_password> help
```

ℹ️ **Protip:** you can edit the `eclair-cli` file and save the API password/url so you don't have to set them every time. We will omit them in the examples below.

Note that you may have to install jq first if it's not already installed on your machine:

```shell
sudo apt-get install jq
```

## Example 1: open a channel with eclair-cli

Your node listens on 8081. You want to open a 140 mBTC channel with `endurance.acinq.co` on Testnet.

First connect to the endurance node:

```shell
eclair-cli connect --uri=03933884aaf1d6b108397e5efe5c86bcf2d8ca8d2f700eda99db9214fc2712b134@endurance.acinq.co:9735
```

Then open a channel with endurance:

```shell
eclair-cli open --nodeId=03933884aaf1d6b108397e5efe5c86bcf2d8ca8d2f700eda99db9214fc2712b134 --fundingSatoshis=14000000
```

This will broadcast a funding transaction to the bitcoin blockchain.
Once that transaction is confirmed, your lightning channel will be ready for off-chain payments.

:warning: You should NEVER use RBF to bump the fees of that funding transaction, otherwise you won't be able to recover your funds unless your peer cooperates. If the transaction doesn't confirm, you should use CPFP to bump the fees.

## Example 2: pay an invoice with eclair-cli

```shell
eclair-cli payinvoice --invoice=lntb17u1pdthhsdpp5z5am8.......
```

## Example 3: generate a payment request for 1 mBTC

```shell
eclair-cli createinvoice --amountMsat=100000000 --description="my first invoice" | jq .serialized
```

This command will return a lightning payment request, such as:

```shell
lntb1m1pdthhh0pp5063r8hu6f6hk7tpauhgvl3nnf4ur3xntcnhujcz5w82yq7nhjuysdq6d4ujqenfwfehggrfdemx76trv5xqrrss6uxhewtmjkumpr7w6prkgttku76azfq7l8cx9v74pcv85hzyvs9n23dhu9u354xcqpnzey45ua3g2m4dywuw7udrt2sdsvjf3rawdqcpas9mah
```

You can check this invoice with the `parseinvoice` command:

```shell
eclair-cli parseinvoice --invoice=lntb1m1pdthhh0pp5063r8hu6f6hk7tpauhgvl3nnf4ur3xntcnhujcz5w82yq7nhjuysdq6d4ujqenfwfehggrfdemx76trv5xqrrss6uxhewtmjkumpr7w6prkgttku76azfq7l8cx9v74pcv85hzyvs9n23dhu9u354xcqpnzey45ua3g2m4dywuw7udrt2sdsvjf3rawdqcpas9mah
```

Which will breakdown the invoice in human readable data.

## Example 4: list local channels in NORMAL state
```shell
eclair-cli channels | jq '.[] | select(.state == "NORMAL")'
```

## Example 5: compute your total balance across all channels
We divide by `1000` because we want satoshis, not millisatoshis.
```shell
eclair-cli usablebalances | jq 'map(.canSend / 1000) | add'
```