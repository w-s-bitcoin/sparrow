package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.drongo.wallet.BlockTransactionHash;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletNode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SingleKeyHistoryTest {
    @Test
    void historyAndRefreshKeepWatchingTheUsedImportedAddress() throws Exception {
        Network previous = Network.get();
        try {
            Network.set(Network.TESTNET);
            for(ScriptType type : List.of(ScriptType.P2PKH, ScriptType.P2SH_P2WPKH, ScriptType.P2WPKH, ScriptType.P2TR)) {
                checkHistory(Wallet.fromSingleKey("History test", ECKey.fromPrivate(BigInteger.ONE, true), type));
            }
            checkHistory(Wallet.fromSingleKey("Uncompressed history test", ECKey.fromPrivate(BigInteger.ONE, false), ScriptType.P2PKH));
        } finally {
            Network.set(previous);
        }
    }

    private void checkHistory(Wallet wallet) throws Exception {
        WalletNode receive = wallet.getFreshNode(KeyPurpose.RECEIVE);
        String address = receive.getAddress().toString();
        Transaction transaction = new Transaction();
        transaction.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]));
        transaction.addOutput(10_000L, receive.getAddress());
        BlockTransaction funding = new BlockTransaction(transaction.getTxId(), 100, new Date(1_000_000), 0L, transaction);
        RecordingServer server = new RecordingServer(funding);

        Map<WalletNode, Set<BlockTransactionHash>> first = server.getHistory(wallet);
        assertEquals(Set.of(receive), first.keySet());
        server.calculateNodeHistory(wallet, first);
        assertTrue(receive.isUsed());
        assertEquals(10_000L, wallet.getWalletUtxos().keySet().stream().mapToLong(output -> output.getValue()).sum());

        Map<WalletNode, Set<BlockTransactionHash>> reopened = server.getHistory(wallet);
        Map<WalletNode, Set<BlockTransactionHash>> refreshed = server.getHistory(wallet, List.of(receive));
        assertEquals(Set.of(receive), reopened.keySet());
        assertEquals(Set.of(receive), refreshed.keySet());
        assertEquals(List.of(receive, receive, receive), server.subscribed);
        assertEquals(List.of(ElectrumServer.getScriptHash(receive), ElectrumServer.getScriptHash(receive), ElectrumServer.getScriptHash(receive)), server.scriptHashes);
        assertEquals(3, server.referenceRequests);
        assertEquals(3, server.transactionRequests);
        assertEquals(1, wallet.getNode(KeyPurpose.RECEIVE).getChildren().size());
        assertTrue(wallet.getNode(KeyPurpose.CHANGE).getChildren().isEmpty());
        assertEquals(1, wallet.getPurposeNodes().size());
        assertEquals(0, wallet.getGapLimit());
        assertEquals(address, wallet.getFreshNode(KeyPurpose.RECEIVE).getAddress().toString());
        assertEquals(address, wallet.getFreshNode(KeyPurpose.CHANGE).getAddress().toString());
    }

    /** Only RPC boundaries are replaced; the wallet's history and gap management run normally. */
    private static class RecordingServer extends ElectrumServer {
        private final BlockTransaction funding;
        private final List<WalletNode> subscribed = new ArrayList<>();
        private final List<String> scriptHashes = new ArrayList<>();
        private int referenceRequests;
        private int transactionRequests;

        private RecordingServer(BlockTransaction funding) {
            this.funding = funding;
        }

        @Override
        public void subscribeWalletNodes(Wallet wallet, Collection<WalletNode> nodes, Map<WalletNode, Set<BlockTransactionHash>> nodeTransactionMap, int startIndex) {
            assertEquals(0, startIndex);
            for(WalletNode node : nodes) {
                subscribed.add(node);
                scriptHashes.add(ElectrumServer.getScriptHash(node));
                nodeTransactionMap.put(node, Set.of());
            }
        }

        @Override
        public void getReferences(Wallet wallet, Collection<WalletNode> nodes, Map<WalletNode, Set<BlockTransactionHash>> nodeTransactionMap, int startIndex) {
            referenceRequests++;
            for(WalletNode node : nodes) {
                nodeTransactionMap.put(node, Set.of(funding));
            }
        }

        @Override
        public void getReferencedTransactions(Wallet wallet, Map<WalletNode, Set<BlockTransactionHash>> nodeTransactionMap) {
            transactionRequests++;
            wallet.updateTransactions(Map.of(funding.getHash(), funding));
        }
    }
}
