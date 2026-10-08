package com.sparrowwallet.sparrow.wallet;

import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletNode;
import com.sparrowwallet.sparrow.io.Config;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Duration;

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
}
