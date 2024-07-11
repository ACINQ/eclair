# TODO

- I removed `enable-trampoline-payments`, so the ACINQ node must:
  - activate `trampoline_payment`
  - activate `trampoline_payment_prototype` to support previous Phoenix versions
- add trampoline to Bolt 12 features (can be requested in `invoice_request`)
  - add blinded relay with trampoline onion (when intermediate node)
  - write BOLT spec
  - create official test vector for trampoline-to-blinded-path
- add trampoline failures defined in the spec  
- `lightning-kmp`:
  - must support both options for a while (to allow being paid by older Phoenix)
  - Bolt 11 invoices will simply set both feature bits
  - Bolt 12 invoices will do it based on what the `invoice_request` contains
