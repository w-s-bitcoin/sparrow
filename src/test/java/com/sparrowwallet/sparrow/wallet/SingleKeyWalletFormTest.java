package com.sparrowwallet.sparrow.wallet;

import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletNode;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.event.WalletEntryLabelsChangedEvent;
import com.sparrowwallet.sparrow.event.WalletHistoryChangedEvent;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SingleKeyWalletFormTest {
    @Test
    void labeledReusableAddressNeverAdvancesOrLoops() {
        Wallet wallet = Wallet.fromSingleKey("single", ECKey.fromPrivate(BigInteger.ONE), ScriptType.P2WPKH);
        wallet.getFreshNode(KeyPurpose.RECEIVE).setLabel("Reusable savings");
        WalletForm form = new WalletForm(null, wallet);
        try {
            NodeEntry receive = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> form.getFreshNodeEntry(KeyPurpose.RECEIVE, null));
            assertSame(receive, assertTimeoutPreemptively(Duration.ofSeconds(2), () -> form.getFreshNodeEntry(KeyPurpose.RECEIVE, receive)));
            assertSame(receive, form.getFreshNodeEntry(KeyPurpose.CHANGE, receive));
            assertEquals("Reusable savings", receive.getLabel());
            assertEquals(1, form.getNodeEntry(KeyPurpose.RECEIVE).getChildren().size());
            assertTrue(wallet.getNode(KeyPurpose.CHANGE).getChildren().isEmpty());
        } finally {
            form.disposeRefreshNodes();
        }
    }

    @Test
    void spentReusableAddressRemainsVisibleAndReturnedFundsHaveClearLabels() {
        Wallet wallet = Wallet.fromSingleKey("single", ECKey.fromPrivate(BigInteger.ONE), ScriptType.P2PKH);
        WalletNode node = wallet.getFreshNode(KeyPurpose.RECEIVE);
        BlockTransactionHashIndex incoming = new BlockTransactionHashIndex(Sha256Hash.ZERO_HASH, 1, null, 0L, 0, 100000);
        Sha256Hash spendHash = Sha256Hash.wrap("01".repeat(32));
        incoming.setSpentBy(new BlockTransactionHashIndex(spendHash, 2, null, 100L, 0, 100000));
        node.getTransactionOutputs().add(incoming);

        boolean previous = Config.get().isHideEmptyUsedAddresses();
        Config.get().setHideEmptyUsedAddresses(true);
        try {
            NodeEntry root = new NodeEntry(wallet, wallet.getNode(KeyPurpose.RECEIVE));
            assertEquals(1, root.getChildren().size());
            assertEquals(node, ((NodeEntry)root.getChildren().getFirst()).getNode());
            BlockTransactionHashIndex returned = new BlockTransactionHashIndex(spendHash, 2, null, 100L, 1, 49000);
            assertEquals(" (returned)", WalletForm.getOutputLabelSuffix(node, returned));
            assertEquals(" (received)", WalletForm.getOutputLabelSuffix(node, incoming));
        } finally {
            Config.get().setHideEmptyUsedAddresses(previous);
        }
    }

    @Test
    void partialSpendKeepsItsSendLabelSeparateFromTheReusableAddressLabel() {
        for(String addressLabel : List.of("", "Public test address - reusable")) {
            Wallet wallet = Wallet.fromSingleKey("single", ECKey.fromPrivate(BigInteger.ONE), ScriptType.P2TR);
            wallet.setStoredBlockHeight(2);
            WalletNode node = wallet.getFreshNode(KeyPurpose.RECEIVE);
            node.setLabel(addressLabel);

            Transaction funding = new Transaction();
            funding.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]));
            funding.addOutput(100_000L, node.getAddress());
            BlockTransaction fundingBlock = new BlockTransaction(funding.getTxId(), 1, null, 0L, funding);
            fundingBlock.setLabel("Funding");
            BlockTransactionHashIndex fundingRef = new BlockTransactionHashIndex(funding.getTxId(), 1, null, 0L, 0, 100_000);
            fundingRef.setLabel("Funding (received)");

            Transaction spend = new Transaction();
            spend.addInput(funding.getTxId(), 0, new Script(new byte[0]));
            spend.addOutput(49_000L, node.getAddress());
            BlockTransaction spendBlock = new BlockTransaction(spend.getTxId(), 0, null, 1_000L, spend);
            fundingRef.setSpentBy(new BlockTransactionHashIndex(spend.getTxId(), 0, null, 1_000L, 0, 100_000));
            BlockTransactionHashIndex returned = new BlockTransactionHashIndex(spend.getTxId(), 0, null, 1_000L, 0, 49_000);
            node.getTransactionOutputs().addAll(List.of(fundingRef, returned));
            wallet.updateTransactions(Map.of(funding.getTxId(), fundingBlock, spend.getTxId(), spendBlock));

            WalletForm form = new WalletForm(null, wallet) {
                @Override
                public String getWalletId() {
                    return "single-key-labels";
                }
            };
            try {
                //History is delivered before the open transaction tab can apply the Send label.
                deliverLabels(() -> form.walletHistoryChanged(historyEvent(wallet, node)));
                assertNull(spendBlock.getLabel());
                assertNull(returned.getLabel());
                assertEquals(addressLabel, node.getLabel());

                //HeadersController fills the still-unlabelled transaction and returned output.
                spendBlock.setLabel("Live Taproot partial spend");
                returned.setLabel(spendBlock.getLabel() + WalletForm.getOutputLabelSuffix(node, returned));
                deliverLabels(() -> form.walletLabelsChanged(new WalletEntryLabelsChangedEvent(wallet,
                        new TransactionEntry(wallet, spendBlock, Collections.emptyMap(), Collections.emptyMap()))));
                assertEquals("Live Taproot partial spend (returned)", returned.getLabel());
                assertEquals("Live Taproot partial spend (input)", fundingRef.getSpentBy().getLabel());
                assertEquals(addressLabel, node.getLabel());

                deliverLabels(() -> form.walletHistoryChanged(historyEvent(wallet, node)));
                assertEquals("Live Taproot partial spend", spendBlock.getLabel());
                assertEquals("Live Taproot partial spend (returned)", returned.getLabel());

                returned.setLabel("Keep this custom output label");
                spendBlock.setLabel("Edited partial spend");
                deliverLabels(() -> form.walletLabelsChanged(new WalletEntryLabelsChangedEvent(wallet,
                        new TransactionEntry(wallet, spendBlock, Collections.emptyMap(), Collections.emptyMap()))));
                assertEquals("Keep this custom output label", returned.getLabel());
                assertEquals(addressLabel, node.getLabel());

                Transaction incoming = new Transaction();
                incoming.addInput(Sha256Hash.ZERO_HASH, 1, new Script(new byte[0]));
                incoming.addOutput(10_000L, node.getAddress());
                BlockTransaction incomingBlock = new BlockTransaction(incoming.getTxId(), 2, null, 0L, incoming);
                BlockTransactionHashIndex incomingRef = new BlockTransactionHashIndex(incoming.getTxId(), 2, null, 0L, 0, 10_000);
                node.getTransactionOutputs().add(incomingRef);
                wallet.updateTransactions(Map.of(incoming.getTxId(), incomingBlock));
                deliverLabels(() -> form.walletHistoryChanged(historyEvent(wallet, node)));
                assertEquals(addressLabel.isEmpty() ? null : addressLabel, incomingBlock.getLabel());
                assertEquals(addressLabel.isEmpty() ? null : addressLabel + " (received)", incomingRef.getLabel());
                assertEquals(addressLabel, node.getLabel());
            } finally {
                form.disposeRefreshNodes();
            }
        }
    }

    private WalletHistoryChangedEvent historyEvent(Wallet wallet, WalletNode node) {
        return new WalletHistoryChangedEvent(wallet, null, List.of(node), List.of()) {
            @Override
            public String getWalletId() {
                return "single-key-labels";
            }
        };
    }

    private void deliverLabels(Runnable update) {
        try {
            update.run();
        } catch(IllegalStateException e) {
            //The model changes happen synchronously before their UI notification is queued.
            assertEquals("Toolkit not initialized", e.getMessage());
        }
    }
}
