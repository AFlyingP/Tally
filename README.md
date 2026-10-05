# Tally

Tally is a double-entry ledger service with holds, scheduled payments, and payments to an external bank. It comes with test suites that check its invariants under concurrency and failures.

## Running it

You need JDK 21 and Docker.

```sh
sh task.sh setup
sh task.sh check
```
