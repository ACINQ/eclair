# Trampoline Payments

Eclair started supporting [trampoline payments](https://github.com/lightning/bolts/pull/829) in v0.3.3.

It is disabled by default, as it is still being reviewed for spec acceptance. However, if you want to experiment with it, here is what you can do.

First of all, you need to activate the feature for any node that will act as a trampoline node. Update your `eclair.conf` with the following values:

```conf
eclair.trampoline-payments-enable=true
```

## Sending trampoline payments

The CLI allows you to fully control how your payment is split and sent. This is a good way to start experimenting with Trampoline.

Let's imagine that the network looks like this:

```txt
Alice -----> Bob -----> Carol -----> Dave
```

Where Bob is a trampoline node and Alice, Carol and Dave are "normal" nodes.

Let's imagine that Dave has generated an MPP invoice for 400000 msat: `lntb1500n1pwxx94fp...`.
Alice wants to pay that invoice using Bob as a trampoline.
To spice things up, Alice will use MPP between Bob and herself, splitting the payment in two parts.

Initiate the payment by sending the first part:

```sh
eclair-cli sendtoroute --amountMsat=150000 --nodeIds=$ALICE_ID,$BOB_ID --trampolineFeesMsat=10000 --trampolineCltvExpiry=450 --finalCltvExpiry=16 --invoice=lntb1500n1pwxx94fp...
```

Note the `trampolineFeesMsat` and `trampolineCltvExpiry`. At the moment you have to estimate those yourself. If the values you provide are too low, Bob will send an error and you can retry with higher values. In future versions, we will automatically fill those values for you.

The command will return some identifiers that must be used for the other parts:

```json
{
  "paymentId": "4e8f2440-dbfd-4e76-bb45-a0647a966b2a",
  "parentId": "cd083b31-5939-46ac-bf90-8ac5b286a9e2",
  "trampolineSecret": "9e13d1b602496871bb647b48e8ff8f15a91c07affb0a3599e995d470ac488715"
}
```

The `parentId` is important: this is the identifier used to link the MPP parts together.

The `trampolineSecret` is also important: this is what prevents a malicious trampoline node from stealing money.

Now that you have those, you can send the second part:

```sh
eclair-cli sendtoroute --amountMsat=260000 --parentId=cd083b31-5939-46ac-bf90-8ac5b286a9e2 --trampolineSecret=9e13d1b602496871bb647b48e8ff8f15a91c07affb0a3599e995d470ac488715 --nodeIds=$ALICE_ID,$BOB_ID --trampolineFeesMsat=10000 --trampolineCltvExpiry=450 --finalCltvExpiry=16 --invoice=lntb1500n1pwxx94fp...
```

Note that Alice didn't need to know about Carol. Bob will find the route to Dave through Carol on his own. That's the magic of trampoline!

A couple gotchas:

- you need to make sure you specify the same `trampolineFeesMsat` and `trampolineCltvExpiry` as the first part
- the total `amountMsat` sent need to cover the `trampolineFeesMsat` specified

You can then check the status of the payment with the `getsentinfo` command:

```sh
eclair-cli getsentinfo --id=cd083b31-5939-46ac-bf90-8ac5b286a9e2
```

Once Dave accepts the payment you should see all the details about the payment success (preimage, route, fees, etc).
