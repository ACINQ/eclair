# Usage

## Command line with eclair-cli

### Windows user

On windows, you will have to install Bash first. We recommend installing Git Bash which is packed in the [Git installer from git-scm](https://git-scm.com/downloads).

Then you'll have to install jq:

- Get the latest windows version from https://stedolan.github.io/jq/download/
- Rename the `jq-win64.exe` file to `jq.exe` and move it to `C:/Users/your_name/bin`

## eclair-cli installation

- Download `eclair-cli` from [this XBT Eclair repository](https://github.com/ashbornelabs/eclair-xbt/blob/master/eclair-core/eclair-cli)
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

Your node listens on 8081. To open a channel, use a peer URI and node ID from a peer on the same XBT Lightning network as your Eclair node.

Connect to the XBT peer:

```shell
eclair-cli connect --uri=NODE_ID@XBT_PEER_HOST:9735
```

Then open a channel with that peer:

```shell
eclair-cli open --nodeId=NODE_ID --fundingSatoshis=14000000
```

This broadcasts a funding transaction to the XBT chain.
Once that transaction is confirmed, your lightning channel will be ready for off-chain payments.

:warning: You should NEVER use RBF to bump the fees of that funding transaction, otherwise you won't be able to recover your funds unless your peer cooperates. If the transaction doesn't confirm, you should use CPFP to bump the fees.

## Example 2: pay an invoice with eclair-cli

Use an invoice created by a peer on the configured XBT Lightning network:

```shell
eclair-cli payinvoice --invoice=INVOICE_FROM_XBT_PEER
```

## Example 3: generate a payment request for 1 mXBT

```shell
eclair-cli createinvoice --amountMsat=100000000 --description="my first XBT invoice" | jq .serialized
```

This command returns a payment request for the configured XBT Lightning network. Do not use Bitcoin-chain invoices.

You can inspect the returned request with `parseinvoice`:

```shell
eclair-cli parseinvoice --invoice=INVOICE_FROM_XBT_PEER
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
