package com.sparrowwallet.sparrow.net;

import com.github.arteam.simplejsonrpc.client.Transport;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AddressActivityServiceTest {
    private static final Address ADDRESS = ScriptType.P2WPKH.getAddress(PolicyType.SINGLE_HD, ECKey.fromPrivate(BigInteger.ONE));
    private static final Address RECIPIENT = ScriptType.P2WPKH.getAddress(PolicyType.SINGLE_HD, ECKey.fromPrivate(BigInteger.TWO));

    @Test
    void separatesConfirmedBalanceAndPendingOutgoingDelta() throws Exception {
        Transaction funding = funding(10_000);
        Transaction spending = new Transaction();
        spending.addInput(funding.getTxId(), 0, new Script(new byte[0]));
        spending.addOutput(7_000, RECIPIENT);
        spending.addOutput(2_500, ADDRESS);
        FixtureRpc rpc = new FixtureRpc(Map.of("m/0/0", history(funding, 100, spending, 0)), funding, spending);

        AddressActivityService.AddressActivity activity = lookup(rpc).get(ADDRESS);
        assertEquals(10_000, activity.confirmedBalance());
        assertEquals(-7_500, activity.unconfirmedBalance());
        assertEquals(2_500, activity.totalBalance());
        assertEquals(2, activity.transactionCount());
    }

    @Test
    void distinguishesAnUnusedAddressFromConfirmedSpentToZero() throws Exception {
        Transaction funding = funding(10_000);
        Transaction spending = new Transaction();
        spending.addInput(funding.getTxId(), 0, new Script(new byte[0]));
        spending.addOutput(9_500, RECIPIENT);
        FixtureRpc used = new FixtureRpc(Map.of("m/0/0", history(funding, 100, spending, 101)), funding, spending);
        assertEquals(new AddressActivityService.AddressActivity(0, 0, 2), lookup(used).get(ADDRESS));

        FixtureRpc unused = new FixtureRpc(Map.of("m/0/0", new ScriptHashTx[0]));
        assertEquals(new AddressActivityService.AddressActivity(0, 0, 0), lookup(unused).get(ADDRESS));
        assertEquals(0, unused.transactionCalls);
    }

    @Test
    void batchesPublicScriptHashesAndFetchesSharedTransactionsOnlyOnce() throws Exception {
        ECKey key = ECKey.fromPrivate(BigInteger.ONE);
        List<Address> addresses = List.of(ScriptType.P2PKH, ScriptType.P2SH_P2WPKH, ScriptType.P2WPKH, ScriptType.P2TR).stream()
                .map(type -> type.getAddress(PolicyType.SINGLE_HD, key)).toList();
        Transaction transaction = new Transaction();
        transaction.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]));
        Map<String, ScriptHashTx[]> histories = new LinkedHashMap<>();
        for(int i = 0; i < addresses.size(); i++) {
            transaction.addOutput(1_000L * (i + 1), addresses.get(i));
        }
        for(int i = 0; i < addresses.size(); i++) {
            histories.put("m/0/" + i, new ScriptHashTx[]{new ScriptHashTx(100, transaction.getTxId().toString(), null)});
        }
        FixtureRpc rpc = new FixtureRpc(histories, transaction);
        Map<Address, AddressActivityService.AddressActivity> results = AddressActivityService.lookup(addresses, rpc, null, () -> {});
        assertEquals(1, rpc.transactionCalls);
        assertEquals(Set.of(transaction.getTxId().toString()), rpc.requestedTransactions);
        assertEquals(new LinkedHashSet<>(addresses.stream().map(ElectrumServer::getScriptHash).toList()), new LinkedHashSet<>(rpc.requestedScriptHashes.values()));
        for(int i = 0; i < addresses.size(); i++) {
            assertEquals(new AddressActivityService.AddressActivity(1_000L * (i + 1), 0, 1), results.get(addresses.get(i)));
        }
        assertThrows(UnsupportedOperationException.class, results::clear);
    }

    @Test
    void missingMalformedOrWrongTransactionsNeverProduceZeroBalances() {
        Transaction funding = funding(10_000);
        Transaction wrong = funding(20_000);
        for(String badRaw : List.of(Sha256Hash.ZERO_HASH.toString(), "xyz", Utils.bytesToHex(wrong.bitcoinSerialize()))) {
            FixtureRpc rpc = new FixtureRpc(Map.of("m/0/0", new ScriptHashTx[]{new ScriptHashTx(100, funding.getTxId().toString(), null)}));
            rpc.rawTransactions.put(funding.getTxId().toString(), badRaw);
            assertThrows(ServerException.class, () -> lookup(rpc));
        }
        FixtureRpc missing = new FixtureRpc(Map.of("m/0/0", new ScriptHashTx[]{new ScriptHashTx(100, funding.getTxId().toString(), null)}));
        assertThrows(ServerException.class, () -> lookup(missing));
    }

    @Test
    void omittedOrFailedHistoriesNeverLookUnused() {
        assertThrows(ServerException.class, () -> lookup(new FixtureRpc(Collections.emptyMap())));
        assertThrows(ServerException.class, () -> lookup(new FixtureRpc(Map.of("m/0/0", new ScriptHashTx[]{ScriptHashTx.ERROR_TX}))));
    }

    @Test
    void rejectsHistoryUnrelatedToTheAddressAndConflictingSpends() {
        Transaction unrelated = new Transaction();
        unrelated.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]));
        unrelated.addOutput(10_000, RECIPIENT);
        FixtureRpc unrelatedRpc = new FixtureRpc(Map.of("m/0/0", new ScriptHashTx[]{new ScriptHashTx(100, unrelated.getTxId().toString(), null)}), unrelated);
        assertThrows(ServerException.class, () -> lookup(unrelatedRpc));

        Transaction funding = funding(10_000);
        Transaction first = new Transaction();
        first.addInput(funding.getTxId(), 0, new Script(new byte[0]));
        first.addOutput(9_500, RECIPIENT);
        Transaction conflicting = new Transaction();
        conflicting.addInput(funding.getTxId(), 0, new Script(new byte[0]));
        conflicting.addOutput(9_000, RECIPIENT);
        FixtureRpc conflictRpc = new FixtureRpc(Map.of("m/0/0", new ScriptHashTx[]{
                new ScriptHashTx(100, funding.getTxId().toString(), null),
                new ScriptHashTx(0, first.getTxId().toString(), null),
                new ScriptHashTx(0, conflicting.getTxId().toString(), null)}), funding, first, conflicting);
        assertThrows(ServerException.class, () -> lookup(conflictRpc));
    }

    @Test
    void reportsCoinbaseValueAsBalanceWithoutClaimingSpendability() throws Exception {
        Transaction coinbase = new Transaction();
        coinbase.addInput(Sha256Hash.ZERO_HASH, 0xffffffffL, new Script(new byte[]{1, 1}));
        coinbase.addOutput(50 * Transaction.SATOSHIS_PER_BITCOIN, ADDRESS);
        FixtureRpc rpc = new FixtureRpc(Map.of("m/0/0", new ScriptHashTx[]{new ScriptHashTx(1, coinbase.getTxId().toString(), null)}), coinbase);
        assertEquals(50 * Transaction.SATOSHIS_PER_BITCOIN, lookup(rpc).get(ADDRESS).confirmedBalance());
    }

    private static Transaction funding(long amount) {
        Transaction transaction = new Transaction();
        transaction.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]));
        transaction.addOutput(amount, ADDRESS);
        return transaction;
    }

    private static ScriptHashTx[] history(Transaction first, int firstHeight, Transaction second, int secondHeight) {
        return new ScriptHashTx[]{new ScriptHashTx(firstHeight, first.getTxId().toString(), null), new ScriptHashTx(secondHeight, second.getTxId().toString(), null)};
    }

    private static Map<Address, AddressActivityService.AddressActivity> lookup(FixtureRpc rpc) throws ServerException {
        return AddressActivityService.lookup(List.of(ADDRESS), rpc, null, () -> {});
    }

    private static class FixtureRpc extends SimpleElectrumServerRpc {
        private final Map<String, ScriptHashTx[]> histories;
        private final Map<String, String> rawTransactions = new HashMap<>();
        private Map<String, String> requestedScriptHashes;
        private Set<String> requestedTransactions;
        private int transactionCalls;

        private FixtureRpc(Map<String, ScriptHashTx[]> histories, Transaction... transactions) {
            this.histories = histories;
            for(Transaction transaction : transactions) {
                rawTransactions.put(transaction.getTxId().toString(), Utils.bytesToHex(transaction.bitcoinSerialize()));
            }
        }

        @Override
        public Map<String, ScriptHashTx[]> getScriptHashHistory(Transport transport, Wallet wallet, Map<String, String> paths, boolean failOnError) {
            assertNull(wallet);
            assertTrue(failOnError);
            requestedScriptHashes = new LinkedHashMap<>(paths);
            return histories;
        }

        @Override
        public Map<String, String> getTransactions(Transport transport, Wallet wallet, Set<String> txids) {
            assertNull(wallet);
            transactionCalls++;
            requestedTransactions = new LinkedHashSet<>(txids);
            return rawTransactions;
        }
    }
}
