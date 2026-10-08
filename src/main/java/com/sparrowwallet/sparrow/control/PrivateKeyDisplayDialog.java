package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.sparrow.AppServices;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextArea;

/** Only constructed after the user requests disclosure and unlocks the wallet. */
public class PrivateKeyDisplayDialog extends Dialog<Void> {
    public PrivateKeyDisplayDialog(Keystore decryptedKeystore) {
        setTitle("Private Key (WIF)");
        getDialogPane().setHeaderText("Keep this private key secret. Anyone with it can spend this wallet's funds.");
        getDialogPane().getStylesheets().add(AppServices.class.getResource("general.css").toExternalForm());
        AppServices.setStageIcon(getDialogPane().getScene().getWindow());
        TextArea key = new TextArea(decryptedKeystore.getSingleKey().getKey().getPrivateKeyEncoded().toString());
        key.setEditable(false);
        key.setWrapText(true);
        key.setPrefRowCount(2);
        key.getStyleClass().add("fixed-width");
        getDialogPane().setContent(key);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefWidth(560);
        setOnHidden(event -> key.clear());
        AppServices.moveToActiveWindowScreen(this);
    }
}
