# Multipart Payments

Eclair started supporting [multi-part payments](https://github.com/lightning/bolts/blob/master/04-onion-routing.md#basic-multi-part-payments) in v0.3.3.

## Receiving multi-part payments

Once the feature is activated, your eclair node will generate invoices that can be paid using MPP.
If the sender also supports MPP, your node will accept the payment.
It's as simple as that!

Here is how you generate an invoice:

```sh
eclair-cli createinvoice --amountMsat=42000000 --description="MPP is #reckless"
```

## Sending multi-part payments

### With the built-in splitting algorithm

If you try paying an invoice that supports MPP, eclair will automatically split the payment (if needed).

You just need to use the `payinvoice` command, nothing complicated here:

```sh
eclair-cli payinvoice --invoice=lnbc11pdkmqhupp5n2e...
```

However, we are still experimenting with various algorithms to efficiently split payments.
You may find that the resulting split looks less optimal that what you expected, in which case the next section is for you!

### With your own splitting algorithm

The CLI allows you to fully control how your payment is split and sent. This is a good way to start experimenting with MPP.

Let's imagine that the network looks like this:

```txt
  +------- Bob -------+
  |                   |
Alice               Dave 
  |                   |
  +------- Carol -----+

```

Dave has generated an MPP invoice for 400000 msat: `lntb1500n1pwxx94fp...`.

You want to send 150000 msat through Bob and 250000 msat through Carol.

Initiate the payment by sending the first part:

```sh
eclair-cli sendtoroute --amountMsat=150000 --nodeIds=$ALICE_ID,$BOB_ID,$DAVE_ID --finalCltvExpiry=16 --invoice=lntb1500n1pwxx94fp...
```

This will return some identifiers that must be used for the other parts:

```json
{
  "paymentId": "4e8f2440-dbfd-4e76-bb45-a0647a966b2a",
  "parentId": "cd083b31-5939-46ac-bf90-8ac5b286a9e2"
}
```

The `parentId` is important: this is the identifier used to link the parts together.

Now that you have those, you can send the second part:

```sh
eclair-cli sendtoroute --parentId=cd083b31-5939-46ac-bf90-8ac5b286a9e2 --amountMsat=250000 --nodeIds=$ALICE_ID,$CAROL_ID,$DAVE_ID --finalCltvExpiry=16 --invoice=lntb1500n1pwxx94fp...
```

You can then check the status of the payment with the `getsentinfo` command:

```sh
eclair-cli getsentinfo --id=cd083b31-5939-46ac-bf90-8ac5b286a9e2
```

Once Dave accepts the HTLCs you should see all the details about the payment success (preimage, route, fees, etc).
