package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.crypto.DumpedPrivateKey;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletModel;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.controlsfx.glyphfont.Glyph;
import tornadofx.control.Field;
import tornadofx.control.Fieldset;
import tornadofx.control.Form;

import java.util.List;

/** Imports a single WIF into a persistent wallet after explicit address selection. */
public class PrivateKeyImportDialog extends Dialog<Wallet> {
    private final PasswordField privateKey = new PasswordField();
    private final VBox addresses = new VBox(12);
    private final ToggleGroup addressSelection = new ToggleGroup();
    private final Label validationMessage = new Label();
    private final CheckBox confirmAddress = new CheckBox("I confirm that the selected address is the one I want to import.");

    public PrivateKeyImportDialog() {
        DialogPane pane = getDialogPane();
        pane.getStylesheets().add(AppServices.class.getResource("general.css").toExternalForm());
        pane.getStylesheets().add(AppServices.class.getResource("dialog.css").toExternalForm());
        AppServices.setStageIcon(pane.getScene().getWindow());
        setTitle("Import Private Key");
        pane.setHeaderText("Import Private Key");
        pane.setGraphic(new WalletModelImage(WalletModel.SEED));

        privateKey.setPromptText("Wallet Import Format (WIF)");
        privateKey.getStyleClass().add("fixed-width");
        privateKey.setId("importPrivateKey");
        Glyph cameraGlyph = new Glyph(FontAwesome5.FONT_NAME, FontAwesome5.Glyph.CAMERA);
        cameraGlyph.setFontSize(12);
        Button scan = new Button("", cameraGlyph);
        scan.setTooltip(new Tooltip("Scan a WIF QR code"));
        scan.setOnAction(event -> {
            QRScanDialog dialog = new QRScanDialog();
            dialog.initOwner(pane.getScene().getWindow());
            dialog.showAndWait().ifPresent(result -> {
                PrivateKeyImportScanHandler.handle(result, privateKey::setText, this::updateAddresses, message -> setValidationMessage(message, true));
                resizeToContent();
            });
        });
        HBox keyInput = new HBox(5, privateKey, scan);
        HBox.setHgrow(privateKey, Priority.ALWAYS);
        Field keyField = new Field();
        keyField.setText("Private Key:");
        keyField.getInputs().add(keyInput);
        Fieldset fields = new Fieldset();
        fields.getChildren().add(keyField);
        Form form = new Form();
        form.getChildren().add(fields);

        Label explanation = new Label("A WIF does not identify its address type. Select the address you intend to use. " +
                "Import the key again in a separate wallet if you also use another address type. Selection works offline; balances are not checked here.");
        explanation.setWrapText(true);
        explanation.setMinHeight(Region.USE_PREF_SIZE);
        Label reuseWarning = new Label("This wallet has one reusable address. Every receive and any transaction change return to that same address. " +
                "Address reuse makes your transactions easier to link. Keep a safe backup of the private key.");
        reuseWarning.setWrapText(true);
        reuseWarning.setMinHeight(Region.USE_PREF_SIZE);
        validationMessage.setWrapText(true);
        validationMessage.setMinHeight(Region.USE_PREF_SIZE);
        validationMessage.visibleProperty().bind(validationMessage.textProperty().isNotEmpty());
        validationMessage.managedProperty().bind(validationMessage.visibleProperty());
        confirmAddress.setWrapText(true);
        confirmAddress.setMinHeight(Region.USE_PREF_SIZE);
        addresses.setMinHeight(Region.USE_PREF_SIZE);
        VBox content = new VBox(14, form, explanation, validationMessage, addresses, reuseWarning, confirmAddress);
        content.setMinHeight(Region.USE_PREF_SIZE);
        pane.setContent(content);

        ButtonType importType = new ButtonType("Import Wallet", ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().addAll(ButtonType.CANCEL, importType);
        Button importButton = (Button)pane.lookupButton(importType);
        importButton.disableProperty().bind(addressSelection.selectedToggleProperty().isNull().or(confirmAddress.selectedProperty().not()));
        privateKey.textProperty().addListener((observable, oldValue, newValue) -> updateAddresses());
        addressSelection.selectedToggleProperty().addListener((observable, oldValue, newValue) -> confirmAddress.setSelected(false));
        setResultConverter(button -> button == importType ? Wallet.fromSingleKey("Imported Private Key", parseKey(), (ScriptType)addressSelection.getSelectedToggle().getUserData()) : null);
        setOnShown(event -> {
            resizeToContent();
            Platform.runLater(privateKey::requestFocus);
        });
        setOnHidden(event -> privateKey.clear());
        AppServices.onEscapePressed(pane.getScene(), () -> setResult(null));
        AppServices.moveToActiveWindowScreen(this);
        pane.setPrefWidth(740);
        pane.setMinHeight(Region.USE_PREF_SIZE);
        setResizable(true);
        updateAddresses();
    }

    private ECKey parseKey() {
        return DumpedPrivateKey.fromBase58(privateKey.getText().trim()).getKey();
    }

    private void updateAddresses() {
        resizeToContent();
        addressSelection.selectToggle(null);
        addressSelection.getToggles().clear();
        addresses.getChildren().clear();
        confirmAddress.setSelected(false);
        if(privateKey.getText().isBlank()) {
            setValidationMessage("Enter a WIF to see its candidate addresses on " + Network.get() + ".", false);
            return;
        }

        ECKey key;
        try {
            key = parseKey();
        } catch(Exception e) {
            // Never display parsing exceptions: they may contain secret input.
            if(e.getMessage() != null && e.getMessage().startsWith("Invalid version ")) {
                setValidationMessage("This WIF is for a different Bitcoin network. The current network is " + Network.get() + ".", true);
            } else if("Invalid checksum".equals(e.getMessage())) {
                setValidationMessage("The WIF checksum is invalid. Check that the entire private key was entered correctly.", true);
            } else if(e.getMessage() != null && e.getMessage().contains("secp256k1 range")) {
                setValidationMessage("This WIF contains an invalid private key.", true);
            } else {
                setValidationMessage("Invalid WIF. Enter one valid WIF private key (51 or 52 Base58 characters). Seeds, hex keys and encrypted keys are not supported here.", true);
            }
            return;
        }

        setValidationMessage(key.isCompressed() ? "" : "This WIF uses an uncompressed public key; only Legacy (P2PKH) is supported.", false);
        List<ScriptType> scriptTypes = key.isCompressed() ? List.of(ScriptType.P2PKH, ScriptType.P2SH_P2WPKH, ScriptType.P2WPKH, ScriptType.P2TR) : List.of(ScriptType.P2PKH);
        for(ScriptType scriptType : scriptTypes) {
            RadioButton select = new RadioButton(scriptType.getDescription());
            select.setToggleGroup(addressSelection);
            select.setUserData(scriptType);
            CopyableLabel address = new CopyableLabel();
            address.setText(scriptType.getAddress(PolicyType.SINGLE_KEY, key).toString());
            address.getStyleClass().add("fixed-width");
            address.setMaxWidth(Double.MAX_VALUE);
            VBox.setMargin(address, new Insets(0, 0, 0, 25));
            addresses.getChildren().add(new VBox(4, select, address));
        }
    }

    private void setValidationMessage(String message, boolean error) {
        validationMessage.getStyleClass().remove("failure");
        if(error) {
            validationMessage.getStyleClass().add("failure");
        }
        validationMessage.setText(message);
    }

    private void resizeToContent() {
        Platform.runLater(() -> {
            if(isShowing()) {
                getDialogPane().applyCss();
                getDialogPane().layout();
                ((Stage)getDialogPane().getScene().getWindow()).sizeToScene();
            }
        });
    }
}
