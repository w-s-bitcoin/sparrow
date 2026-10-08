package com.sparrowwallet.sparrow.net;

import com.github.arteam.simplejsonrpc.client.Transport;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

class AddressActivityServiceEdgeTest {
    private static final Address ADDRESS = address(3101);
    private static final Address RECIPIENT = address(3102);

    @Test
    void duplicateHistoryEntriesCountTheTransactionAndBalanceOnce() throws Exception {
        Transaction funding = funding(100_000);
        HistoryRpc rpc = new HistoryRpc(call -> new ScriptHashTx[]{reference(funding, 12), reference(funding, 12)}, funding);

        var activity = AddressActivityService.lookup(List.of(ADDRESS), rpc, null, () -> {}).get(ADDRESS);

        assertEquals(1, activity.transactionCount());
        assertEquals(100_000, activity.confirmedBalance());
        assertEquals(0, activity.unconfirmedBalance());
        assertEquals(100_000, activity.totalBalance());
    }

    @Test
    void conflictingHeightsForTheSameTransactionFailInsteadOfChoosingABalance() {
        Transaction funding = funding(100_000);
        HistoryRpc rpc = new HistoryRpc(call -> new ScriptHashTx[]{reference(funding, 12), reference(funding, 0)}, funding);

        assertThrows(ServerException.class, () -> AddressActivityService.lookup(List.of(ADDRESS), rpc, null, () -> {}));
    }

    @Test
    void chainedPendingSpendsRemoveTheirParentOutputsOnlyOnce() throws Exception {
        Transaction funding = funding(100_000);
        Transaction pending = new Transaction();
        pending.addInput(funding.getTxId(), 0, new Script(new byte[0]));
        pending.addOutput(60_000, ADDRESS);
        pending.addOutput(39_000, RECIPIENT);
        Transaction descendant = new Transaction();
        descendant.addInput(pending.getTxId(), 0, new Script(new byte[0]));
        descendant.addOutput(59_000, RECIPIENT);
        HistoryRpc rpc = new HistoryRpc(call -> new ScriptHashTx[]{reference(funding, 12), reference(pending, 0), reference(descendant, -1)},
                funding, pending, descendant);

        var activity = AddressActivityService.lookup(List.of(ADDRESS), rpc, null, () -> {}).get(ADDRESS);

        assertEquals(3, activity.transactionCount());
        assertEquals(100_000, activity.confirmedBalance());
        assertEquals(-100_000, activity.unconfirmedBalance());
        assertEquals(0, activity.totalBalance());
    }

    @Test
    void confirmationDuringTheLookupRetriesTheSnapshot() throws Exception {
        Transaction funding = funding(100_000);
        HistoryRpc rpc = new HistoryRpc(call -> new ScriptHashTx[]{reference(funding, call == 1 ? 0 : 12)}, funding);

        var activity = AddressActivityService.lookup(List.of(ADDRESS), rpc, null, () -> {}).get(ADDRESS);

        assertTrue(rpc.historyCalls >= 3, "The changed history must be checked again before a result is returned");
        assertEquals(100_000, activity.confirmedBalance());
        assertEquals(0, activity.unconfirmedBalance());
        assertEquals(1, activity.transactionCount());
    }

    @Test
    void persistentlyChangingHistoryFailsAfterBoundedRetries() {
        Transaction funding = funding(100_000);
        HistoryRpc rpc = new HistoryRpc(call -> new ScriptHashTx[]{reference(funding, call % 2 == 0 ? 12 : 0)}, funding);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThrows(ServerException.class, () -> AddressActivityService.lookup(List.of(ADDRESS), rpc, null, () -> {})));
        assertTrue(rpc.historyCalls <= 12, "An unstable address must not cause an unbounded lookup");
    }

    @Test
    void cancelledOrStaleLookupCannotStartRequests() {
        Transaction funding = funding(100_000);
        HistoryRpc rpc = new HistoryRpc(call -> new ScriptHashTx[]{reference(funding, 12)}, funding);

        assertThrows(ServerException.class, () -> AddressActivityService.lookup(List.of(ADDRESS), rpc, null, () -> {
            throw new ServerException("Lookup is no longer active");
        }));
        assertEquals(0, rpc.historyCalls);
        assertEquals(0, rpc.transactionCalls);
    }

    @Test
    void connectionChangeWhileFetchingTransactionsDiscardsTheResult() {
        Transaction funding = funding(100_000);
        AtomicBoolean active = new AtomicBoolean(true);
        HistoryRpc rpc = new HistoryRpc(call -> new ScriptHashTx[]{reference(funding, 12)}, funding) {
            @Override
            public Map<String, String> getTransactions(Transport transport, Wallet wallet, Set<String> txids) {
                Map<String, String> result = super.getTransactions(transport, wallet, txids);
                active.set(false);
                return result;
            }
        };

        assertThrows(ServerException.class, () -> AddressActivityService.lookup(List.of(ADDRESS), rpc, null, () -> {
            if(!active.get()) {
                throw new ServerException("Connection changed");
            }
        }));
        assertEquals(1, rpc.transactionCalls);
    }

    private static Address address(long scalar) {
        //Deliberately public keys for synthetic transactions only.
        return ScriptType.P2WPKH.getAddress(PolicyType.SINGLE_KEY, ECKey.fromPrivate(BigInteger.valueOf(scalar)));
    }

    private static Transaction funding(long amount) {
        Transaction transaction = new Transaction();
        transaction.addInput(Sha256Hash.wrap("01".repeat(32)), 0, new Script(new byte[0]));
        transaction.addOutput(amount, ADDRESS);
        return transaction;
    }

    private static ScriptHashTx reference(Transaction transaction, int height) {
        return new ScriptHashTx(height, transaction.getTxId().toString(), null);
    }

    private static class HistoryRpc extends SimpleElectrumServerRpc {
        private final IntFunction<ScriptHashTx[]> histories;
        private final Map<String, String> rawTransactions = new LinkedHashMap<>();
        int historyCalls;
        int transactionCalls;

        HistoryRpc(IntFunction<ScriptHashTx[]> histories, Transaction... transactions) {
            this.histories = histories;
            for(Transaction transaction : transactions) {
                rawTransactions.put(transaction.getTxId().toString(), Utils.bytesToHex(transaction.bitcoinSerialize()));
            }
        }

        @Override
        public Map<String, ScriptHashTx[]> getScriptHashHistory(Transport transport, Wallet wallet, Map<String, String> pathScriptHashes, boolean failOnError) {
            assertTrue(failOnError);
            assertEquals(Set.of(ElectrumServer.getScriptHash(ADDRESS)), Set.copyOf(pathScriptHashes.values()));
            ScriptHashTx[] history = histories.apply(++historyCalls);
            Map<String, ScriptHashTx[]> result = new LinkedHashMap<>();
            pathScriptHashes.keySet().forEach(path -> result.put(path, history));
            return result;
        }

        @Override
        public Map<String, String> getTransactions(Transport transport, Wallet wallet, Set<String> txids) {
            transactionCalls++;
            assertEquals(rawTransactions.keySet(), txids);
            return new LinkedHashMap<>(rawTransactions);
        }
    }
}
