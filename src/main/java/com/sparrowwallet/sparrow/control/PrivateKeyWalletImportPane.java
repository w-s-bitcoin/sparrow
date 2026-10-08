package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.wallet.WalletModel;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.WalletImportEvent;
import javafx.scene.control.Button;
import javafx.scene.control.Control;

public class PrivateKeyWalletImportPane extends TitledDescriptionPane {
    public PrivateKeyWalletImportPane() {
        super("Private Key (WIF)", "One reusable address", "Import a WIF private key into a persistent single-address wallet. " +
                "Choose its address type and confirm the address before importing. Receives and transaction change reuse this address.", WalletModel.SEED);
    }

    @Override
    protected Control createButton() {
        Button button = new Button("Import...");
        button.setOnAction(event -> {
            PrivateKeyImportDialog dialog = new PrivateKeyImportDialog();
            dialog.initOwner(getScene().getWindow());
            dialog.showAndWait().ifPresent(wallet -> EventManager.get().post(new WalletImportEvent(wallet)));
        });
        return button;
    }
}
