# Live single-key wallet regression test

`SingleKeyRegtestTest` is excluded from the normal test suite. The dedicated
`verifySingleKeyRegtest` task requires both `SPARROW_REGTEST_DATADIR` and
`SPARROW_REGTEST_RPC_PORT` to be explicitly set. It requires Java 25, `bitcoin-cli`,
and a running, dedicated Bitcoin Core regtest node with wallet support and cookie
authentication. It verifies the node reports `regtest` before creating a wallet
or mining blocks. It never falls back to the default Bitcoin data directory.

For example, start a dedicated node on RPC port 18483. The fallback fee is needed
because a fresh regtest chain has no fee-estimation history:

```sh
mkdir -p "$PWD/build/wif-regtest-node"
bitcoind -regtest -datadir="$PWD/build/wif-regtest-node" \
  -server -daemon -listen=0 -networkactive=0 \
  -rpcport=18483 -fallbackfee=0.00002
```

Then run the matrix, adjusting the `BITCOIN_CLI` path for your system:

```sh
SPARROW_REGTEST_DATADIR="$PWD/build/wif-regtest-node" \
SPARROW_REGTEST_RPC_PORT=18483 \
BITCOIN_CLI=/opt/homebrew/bin/bitcoin-cli \
./gradlew verifySingleKeyRegtest
```

Use a disposable regtest chain. The test creates a uniquely named Core funding
wallet, mines 116 blocks, and broadcasts 15 transactions. It leaves the funding
wallet and transactions available for inspection. The private-key vectors are
public, nonvaluable scalars 1001 through 1005 and must never hold real funds.

For compressed P2PKH, nested SegWit, native SegWit, Taproot, and uncompressed
P2PKH, the test independently derives the public descriptor's address with Core,
funds it, builds and signs a partial spend, checks `testmempoolaccept`, broadcasts,
and confirms it. Change must return to the imported address. It then saves an
encrypted Sparrow database, closes and reopens it, unlocks it, and successfully
spends that confirmed change. RPC calls use an independent Core funding wallet;
no `txindex` is required. Transaction IDs and addresses are recorded in JUnit
standard output for inspection under `build/test-results/verifySingleKeyRegtest`.
The dedicated Gradle task runs on every invocation, even when source files have
not changed.

This test checks the live transaction and persistence paths. The wallet history
is populated from verified Core transaction responses; it does not replace
manual checks of Sparrow's live subscriptions, balance updates, import, Receive,
Addresses, Settings, and Send screens.

## Import dialog balance checks

With Sparrow connected to the dedicated regtest node, open **File > Import
Private Key** and enter a public test WIF. Choose **Check Balances**, then select
a date before the test transactions. Bitcoin Core scans all candidate addresses
together. The dialog shows each address's balance and transaction count, plus a
signed **Mempool** amount when unconfirmed transactions change its balance.
The mempool amount is already included in **Balance**. These are address balances,
not estimates of spendable funds; immature coinbase outputs can be included.

Verify funded, unused, and previously used addresses with zero balance. Send an
unconfirmed test payment, refresh, then mine it and refresh again to check the
mempool display. Changing the WIF or disconnecting must clear the results;
closing the dialog must discard callbacks. A failed lookup must show unavailable
balances, never zero. Selecting an address and confirming it remains a separate
manual step, and offline import remains available.

Electrum servers can look up indexed address history directly. Bitcoin Core
requires importing public address descriptors and scanning from the chosen
date; earlier history may be missing, and a scan already started can continue
after closing the dialog. Only public addresses or script hashes are sent to the
configured server, never the WIF or private key.
