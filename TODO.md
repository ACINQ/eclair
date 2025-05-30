# TODO

- Preparatory PR to refactor `CheckBalance.scala` to stop relying on lcp/rcp txs (only on outpoints instead)
- Test that on restart, published txs have different feerates
- Test restart with multiple revoked HTLC txs confirmed
- Then refactor HTLC / Claim-HTLC txs (follow-up PR):
  - `HtlcTimeoutTx` / `HtlcSuccessTx` should be enriched with witness data (remote sig + preimage) in `Transactions.scala`, the `addSigs` methods should return this new class
  - Same for `ClaimHtlcSuccess` with should have a wrapping type with preimage?
  - Use those types in `ReplaceableTx`, put `Commitment` and keys separately  
- Remove `redeemableHtlcTxs` functions from `ErrorHandlers` + `Helpers` returns the enriched txs
