package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.net.cormorant.Cormorant;
import javafx.concurrent.Service;
import javafx.concurrent.Task;

import java.util.*;
import java.util.concurrent.CancellationException;

/** An address-only preview. The server supplies history heights; these balances are not a spendability or confirmation proof. */
public class AddressActivityService extends Service<Map<Address, AddressActivityService.AddressActivity>> {
    private static final int MAX_ATTEMPTS = 3;

    private final List<Address> addresses;
    private final Date since;

    public AddressActivityService(Collection<Address> addresses, Date since) {
        this.addresses = List.copyOf(new LinkedHashSet<>(addresses));
        this.since = since == null ? null : new Date(since.getTime());
    }

    @Override
    protected Task<Map<Address, AddressActivity>> createTask() {
        return new Task<>() {
            @Override
            protected Map<Address, AddressActivity> call() throws ServerException {
                CloseableTransport transport;
                ElectrumServerRpc rpc;
                Cormorant cormorant;
                ServerType serverType;
                synchronized(ElectrumServer.class) {
                    //Never create a connection: a preview must stay within the user's active server session.
                    transport = ElectrumServer.transport;
                    rpc = ElectrumServer.electrumServerRpc;
                    serverType = Config.get().getServerType();
                    cormorant = serverType == ServerType.BITCOIN_CORE ? ElectrumServer.getCormorant() : null;
                }

                ConnectionCheck active = () -> {
                    if(isCancelled() || Thread.currentThread().isInterrupted()) {
                        throw new CancellationException();
                    }
                    synchronized(ElectrumServer.class) {
                        if(transport == null || transport != ElectrumServer.transport || !transport.isConnected()
                                || serverType != Config.get().getServerType()
                                || (serverType == ServerType.BITCOIN_CORE && (cormorant == null || cormorant != ElectrumServer.getCormorant()))) {
                            throw new ServerException("The server connection changed. Connect and check balances again.");
                        }
                    }
                };

                active.check();
                if(cormorant != null) {
                    updateMessage("Scanning candidate addresses...");
                    //The explicit Core scan may continue after this preview is cancelled. Never abort a shared wallet rescan.
                    cormorant.checkAddressesImport(addresses, since);
                    active.check();
                }
                updateMessage("Checking balances and transaction history...");
                return lookup(addresses, rpc, transport, active);
            }
        };
    }

    static Map<Address, AddressActivity> lookup(Collection<Address> addresses, ElectrumServerRpc rpc,
                                               CloseableTransport transport, ConnectionCheck active) throws ServerException {
        Map<Address, String> paths = new LinkedHashMap<>();
        for(Address address : addresses) {
            paths.putIfAbsent(address, "m/0/" + paths.size());
        }
        Map<String, String> scriptHashes = new LinkedHashMap<>();
        paths.forEach((address, path) -> scriptHashes.put(path, ElectrumServer.getScriptHash(address)));

        for(int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            active.check();
            Map<Address, Map<String, Integer>> history = readHistory(paths, rpc.getScriptHashHistory(transport, null, scriptHashes, true));
            Set<String> txids = new LinkedHashSet<>();
            history.values().forEach(entries -> txids.addAll(entries.keySet()));

            active.check();
            Map<String, String> rawTransactions = txids.isEmpty() ? Collections.emptyMap() : rpc.getTransactions(transport, null, txids);
            Map<String, Transaction> transactions = new LinkedHashMap<>();
            for(String txid : txids) {
                active.check();
                String raw = rawTransactions == null ? null : rawTransactions.get(txid);
                if(raw == null || raw.equals(Sha256Hash.ZERO_HASH.toString())) {
                    throw new ServerException("The server did not return all transactions needed to check balances.");
                }
                try {
                    Transaction transaction = new Transaction(Utils.hexToBytes(raw));
                    transaction.verify();
                    if(!transaction.getTxId().toString().equals(txid)) {
                        throw new IllegalArgumentException("Transaction ID mismatch");
                    }
                    transactions.put(txid, transaction);
                } catch(Exception e) {
                    throw new ServerException("The server returned an invalid transaction while checking balances.");
                }
            }

            active.check();
            Map<Address, Map<String, Integer>> refreshed = readHistory(paths, rpc.getScriptHashHistory(transport, null, scriptHashes, true));
            if(!history.equals(refreshed)) {
                continue;
            }

            Map<Address, AddressActivity> results = new LinkedHashMap<>();
            for(Map.Entry<Address, Map<String, Integer>> entry : history.entrySet()) {
                active.check();
                results.put(entry.getKey(), calculateActivity(entry.getKey(), entry.getValue(), transactions));
            }
            active.check();
            return Collections.unmodifiableMap(results);
        }

        throw new ServerException("Transaction history changed while checking balances. Try again.");
    }

    private static Map<Address, Map<String, Integer>> readHistory(Map<Address, String> paths, Map<String, ScriptHashTx[]> response) throws ServerException {
        Map<Address, Map<String, Integer>> result = new LinkedHashMap<>();
        Map<String, Integer> transactionHeights = new HashMap<>();
        for(Map.Entry<Address, String> entry : paths.entrySet()) {
            ScriptHashTx[] references = response == null ? null : response.get(entry.getValue());
            if(references == null) {
                throw new ServerException("The server did not return all candidate address histories.");
            }
            Map<String, Integer> history = new LinkedHashMap<>();
            for(ScriptHashTx reference : references) {
                if(reference == null || reference == ScriptHashTx.ERROR_TX || reference.tx_hash == null || reference.height < -1) {
                    throw new ServerException("The server returned invalid transaction history.");
                }
                String txid;
                try {
                    txid = Sha256Hash.wrap(reference.tx_hash).toString();
                } catch(Exception e) {
                    throw new ServerException("The server returned an invalid transaction ID.");
                }
                Integer previousHeight = transactionHeights.putIfAbsent(txid, reference.height);
                if(previousHeight != null && previousHeight != reference.height) {
                    throw new ServerException("The server returned inconsistent transaction heights. Try again.");
                }
                history.put(txid, reference.height);
            }
            result.put(entry.getKey(), history);
        }
        return result;
    }

    private static AddressActivity calculateActivity(Address address, Map<String, Integer> history,
                                                     Map<String, Transaction> transactions) throws ServerException {
        Map<HashIndex, Long> received = new HashMap<>();
        Script script = address.getOutputScript();
        for(String txid : history.keySet()) {
            for(TransactionOutput output : transactions.get(txid).getOutputs()) {
                if(script.equals(output.getScript())) {
                    received.put(new HashIndex(output.getHash(), output.getIndex()), output.getValue());
                }
            }
        }

        long confirmed = 0;
        long unconfirmed = 0;
        Set<HashIndex> spent = new HashSet<>();
        try {
            for(Map.Entry<String, Integer> entry : history.entrySet()) {
                Transaction transaction = transactions.get(entry.getKey());
                long net = 0;
                boolean related = false;
                for(TransactionOutput output : transaction.getOutputs()) {
                    if(script.equals(output.getScript())) {
                        related = true;
                        net = Math.addExact(net, output.getValue());
                    }
                }
                for(TransactionInput input : transaction.getInputs()) {
                    HashIndex outpoint = new HashIndex(input.getOutpoint().getHash(), input.getOutpoint().getIndex());
                    Long value = received.get(outpoint);
                    if(value != null) {
                        related = true;
                        if(!spent.add(outpoint)) {
                            throw new ServerException("The server returned conflicting spends. Try again.");
                        }
                        net = Math.subtractExact(net, value);
                    }
                }
                if(!related) {
                    throw new ServerException("The server returned incomplete address history.");
                }
                if(entry.getValue() > 0) {
                    confirmed = Math.addExact(confirmed, net);
                } else {
                    unconfirmed = Math.addExact(unconfirmed, net);
                }
            }
            long total = Math.addExact(confirmed, unconfirmed);
            if(confirmed < 0 || total < 0 || confirmed > Transaction.MAX_SATOSHIS || total > Transaction.MAX_SATOSHIS) {
                throw new ServerException("The server returned inconsistent address balances.");
            }
        } catch(ArithmeticException e) {
            throw new ServerException("The server returned invalid address balances.");
        }
        return new AddressActivity(confirmed, unconfirmed, history.size());
    }

    @FunctionalInterface
    interface ConnectionCheck {
        void check() throws ServerException;
    }

    /** Confirmed balance includes immature coinbase outputs. Unconfirmed balance is the signed mempool change to it. */
    public record AddressActivity(long confirmedBalance, long unconfirmedBalance, int transactionCount) {
        public long totalBalance() {
            return confirmedBalance + unconfirmedBalance;
        }
    }
}
