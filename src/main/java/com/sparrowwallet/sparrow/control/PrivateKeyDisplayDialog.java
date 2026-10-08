package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.WalletModel;
import com.sparrowwallet.sparrow.AppServices;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import tornadofx.control.Field;
import tornadofx.control.Fieldset;
import tornadofx.control.Form;

/** Only constructed after the user requests disclosure and unlocks the wallet. */
public class PrivateKeyDisplayDialog extends Dialog<Void> {
    public PrivateKeyDisplayDialog(Keystore decryptedKeystore) {
        setTitle("Private Key (WIF)");
        DialogPane pane = getDialogPane();
        pane.setHeaderText("Private Key (WIF)");
        pane.getStylesheets().add(AppServices.class.getResource("general.css").toExternalForm());
        pane.getStylesheets().add(AppServices.class.getResource("dialog.css").toExternalForm());
        pane.setGraphic(new WalletModelImage(WalletModel.SEED));
        AppServices.setStageIcon(pane.getScene().getWindow());

        TextArea key = new TextArea(decryptedKeystore.getSingleKey().getKey().getPrivateKeyEncoded().toString());
        key.setEditable(false);
        key.setWrapText(true);
        key.setPrefRowCount(2);
        key.getStyleClass().add("fixed-width");
        Field keyField = new Field();
        keyField.setText("Private Key:");
        keyField.getInputs().add(key);
        Fieldset fields = new Fieldset();
        fields.setInputGrow(Priority.ALWAYS);
        fields.getChildren().add(keyField);
        Form form = new Form();
        form.getChildren().add(fields);

        Label warning = new Label("Keep this private key secret. Anyone with it can spend this wallet's funds.");
        warning.setWrapText(true);
        warning.setMinHeight(Region.USE_PREF_SIZE);
        VBox content = new VBox(10, form, warning);
        content.setPadding(new Insets(10));
        pane.setContent(content);
        pane.getButtonTypes().add(ButtonType.CLOSE);
        pane.setPrefWidth(600);
        pane.setMinHeight(Region.USE_PREF_SIZE);
        setOnHidden(event -> key.clear());
        AppServices.moveToActiveWindowScreen(this);
    }
}
