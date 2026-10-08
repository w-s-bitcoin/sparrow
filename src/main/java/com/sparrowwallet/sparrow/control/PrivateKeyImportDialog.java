package com.sparrowwallet.sparrow.control;

import com.google.common.eventbus.Subscribe;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.crypto.DumpedPrivateKey;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletModel;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.ConnectionEvent;
import com.sparrowwallet.sparrow.event.DisconnectionEvent;
import com.sparrowwallet.sparrow.event.HideAmountsStatusEvent;
import com.sparrowwallet.sparrow.event.UnitFormatChangedEvent;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.net.AddressActivityService;
import com.sparrowwallet.sparrow.net.ServerType;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Screen;
import org.controlsfx.glyphfont.Glyph;
import tornadofx.control.Field;
import tornadofx.control.Fieldset;
import tornadofx.control.Form;

import java.text.DateFormat;
import java.util.*;

/** Imports a single WIF into a persistent wallet after explicit address selection. */
public class PrivateKeyImportDialog extends Dialog<Wallet> {
    private final PasswordField privateKey = new PasswordField();
    private final VBox addresses = new VBox(12);
    private final ScrollPane addressScroll = new ScrollPane(addresses);
    private final ToggleGroup addressSelection = new ToggleGroup();
    private final Label validationMessage = new Label();
    private final CheckBox confirmAddress = new CheckBox("I confirm that the selected address is the one I want to import.");
    private final Button checkBalances = new Button("Check Balances");
    private final ProgressIndicator balanceProgress = new ProgressIndicator();
    private final Label balanceStatus = new Label();
    private final Map<Address, CandidateRow> candidateRows = new LinkedHashMap<>();
    private final ChangeListener<Boolean> connectionListener = (observable, oldValue, newValue) -> resetBalanceCheck();
    private AddressActivityService balanceService;
    private Date scanSince;

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
                "Check balances to help identify an existing address, or select an address to import offline. " +
                "Import the key again in a separate wallet if you also use another address type.");
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
        addressScroll.setFitToWidth(true);
        addressScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        addressScroll.getStyleClass().addAll("edge-to-edge", "private-key-addresses");
        addressScroll.visibleProperty().bind(javafx.beans.binding.Bindings.isNotEmpty(addressSelection.getToggles()));
        addressScroll.managedProperty().bind(addressScroll.visibleProperty());
        balanceStatus.setWrapText(true);
        balanceStatus.setMinHeight(Region.USE_PREF_SIZE);
        balanceStatus.setMinWidth(0);
        checkBalances.setMinWidth(Region.USE_PREF_SIZE);
        checkBalances.setTooltip(new Tooltip("Checks all displayed addresses with your connected server. The server can link these address queries. Your private key is never sent."));
        checkBalances.setOnAction(event -> checkAddressBalances());
        balanceProgress.managedProperty().bind(balanceProgress.visibleProperty());
        balanceProgress.maxHeightProperty().bind(checkBalances.heightProperty());
        balanceProgress.setVisible(false);
        HBox balanceControls = new HBox(10, checkBalances, balanceProgress, balanceStatus);
        balanceControls.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(balanceStatus, Priority.ALWAYS);
        balanceControls.visibleProperty().bind(javafx.beans.binding.Bindings.isNotEmpty(addressSelection.getToggles()));
        balanceControls.managedProperty().bind(balanceControls.visibleProperty());
        VBox content = new VBox(14, form, explanation, validationMessage, addressScroll, balanceControls, reuseWarning, confirmAddress);
        content.setMinHeight(Region.USE_PREF_SIZE);
        pane.setContent(content);

        ButtonType importType = new ButtonType("Import Wallet", ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().addAll(ButtonType.CANCEL, importType);
        Button importButton = (Button)pane.lookupButton(importType);
        importButton.disableProperty().bind(addressSelection.selectedToggleProperty().isNull().or(confirmAddress.selectedProperty().not()));
        privateKey.textProperty().addListener((observable, oldValue, newValue) -> updateAddresses());
        addressSelection.selectedToggleProperty().addListener((observable, oldValue, newValue) -> confirmAddress.setSelected(false));
        setResultConverter(button -> {
            if(button != importType) {
                return null;
            }
            Wallet wallet = Wallet.fromSingleKey("Imported Private Key", parseKey(), (ScriptType)addressSelection.getSelectedToggle().getUserData());
            wallet.setBirthDate(scanSince);
            return wallet;
        });
        setOnShown(event -> {
            EventManager.get().register(this);
            AppServices.onlineProperty().addListener(connectionListener);
            resetBalanceCheck();
            resizeToContent();
            Platform.runLater(privateKey::requestFocus);
        });
        setOnHidden(event -> {
            EventManager.get().unregister(this);
            AppServices.onlineProperty().removeListener(connectionListener);
            resetBalanceCheck();
            privateKey.clear();
        });
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

    @Subscribe
    public void connected(ConnectionEvent event) {
        resetBalanceCheck();
    }

    @Subscribe
    public void disconnected(DisconnectionEvent event) {
        resetBalanceCheck();
    }

    @Subscribe
    public void unitFormatChanged(UnitFormatChangedEvent event) {
        refreshAmounts();
    }

    @Subscribe
    public void hideAmountsChanged(HideAmountsStatusEvent event) {
        refreshAmounts();
    }

    private void refreshAmounts() {
        candidateRows.values().forEach(CandidateRow::refreshAmounts);
        resizeToContent();
    }

    private void updateAddresses() {
        resetBalanceCheck();
        scanSince = null;
        resizeToContent();
        addressSelection.selectToggle(null);
        addressSelection.getToggles().clear();
        addresses.getChildren().clear();
        addressScroll.setVvalue(0);
        candidateRows.clear();
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
            Address candidateAddress = scriptType.getAddress(PolicyType.SINGLE_KEY, key);
            CandidateRow row = new CandidateRow(select, candidateAddress);
            candidateRows.put(candidateAddress, row);
            addresses.getChildren().add(row);
        }
        updateBalanceControls();
    }

    private void resetBalanceCheck() {
        if(balanceService != null) {
            AddressActivityService previousService = balanceService;
            balanceService = null;
            previousService.cancel();
        }
        balanceProgress.setVisible(false);
        checkBalances.setText("Check Balances");
        balanceStatus.getStyleClass().remove("failure");
        candidateRows.values().forEach(row -> row.showStatus("Balance not checked"));
        updateBalanceControls();
    }

    private void updateBalanceControls() {
        boolean connected = AppServices.isConnected();
        checkBalances.setDisable(candidateRows.isEmpty() || !connected || balanceService != null);
        balanceStatus.setText(connected ? "Check all addresses using your connected server." : "Connect to a server to check balances.");
    }

    private void checkAddressBalances() {
        if(!AppServices.isConnected() || candidateRows.isEmpty() || balanceService != null) {
            updateBalanceControls();
            return;
        }

        Date since = null;
        boolean bitcoinCore = Config.get().getServerType() == ServerType.BITCOIN_CORE;
        if(bitcoinCore) {
            WalletBirthDateDialog scanDateDialog = new WalletBirthDateDialog(scanSince, true);
            scanDateDialog.setTitle("Address Scan Start Date");
            scanDateDialog.getDialogPane().setHeaderText("Select a date earlier than the first transaction for any of these addresses:");
            scanDateDialog.getDialogPane().setPrefWidth(460);
            scanDateDialog.getDialogPane().getButtonTypes().stream()
                    .filter(type -> type.getButtonData() == ButtonBar.ButtonData.OK_DONE)
                    .forEach(type -> ((Button)scanDateDialog.getDialogPane().lookupButton(type)).setText("Scan Addresses"));
            scanDateDialog.initOwner(getDialogPane().getScene().getWindow());
            Optional<Date> selectedDate = scanDateDialog.showAndWait();
            if(selectedDate.isEmpty()) {
                return;
            }
            since = selectedDate.get();
        }

        if(!AppServices.isConnected()) {
            updateBalanceControls();
            return;
        }

        AddressActivityService service = new AddressActivityService(candidateRows.keySet(), since);
        balanceService = service;
        Date checkedSince = since;
        balanceProgress.setVisible(true);
        checkBalances.setDisable(true);
        balanceStatus.getStyleClass().remove("failure");
        balanceStatus.setText(bitcoinCore ? "Scanning addresses with Bitcoin Core..." : "Checking addresses with your connected server...");
        candidateRows.values().forEach(row -> row.showStatus("Checking..."));
        service.setOnSucceeded(event -> {
            if(balanceService != service || !isShowing()) {
                return;
            }
            balanceService = null;
            balanceProgress.setVisible(false);
            checkBalances.setText("Refresh Balances");
            checkBalances.setDisable(!AppServices.isConnected());
            service.getValue().forEach((address, activity) -> candidateRows.get(address).showActivity(activity));
            scanSince = checkedSince;
            balanceStatus.setText(checkedSince == null ? "Balances checked with your connected server." :
                    "Scanned from " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(checkedSince) + ". Earlier transactions may not be included.");
            resizeToContent();
        });
        service.setOnFailed(event -> {
            if(balanceService != service || !isShowing()) {
                return;
            }
            balanceService = null;
            balanceProgress.setVisible(false);
            checkBalances.setDisable(!AppServices.isConnected());
            candidateRows.values().forEach(row -> row.showStatus("Balance unavailable"));
            balanceStatus.getStyleClass().add("failure");
            // Server errors may contain arbitrary remote text. Keep this message local and independent of key input.
            balanceStatus.setText(bitcoinCore ? "Could not check balances. Check your node connection and choose a scan date within its available block history." :
                    "Could not check balances. Check your server connection and try again.");
            resizeToContent();
        });
        service.start();
        resizeToContent();
    }

    private static class CandidateRow extends VBox {
        private final FlowPane activity = new FlowPane(12, 4);
        private AddressActivityService.AddressActivity lastResult;

        private CandidateRow(RadioButton select, Address candidateAddress) {
            super(4);
            CopyableLabel address = new CopyableLabel();
            address.setText(candidateAddress.toString());
            address.getStyleClass().add("fixed-width");
            address.setMaxWidth(Double.MAX_VALUE);
            VBox.setMargin(address, new Insets(0, 0, 0, 25));
            VBox.setMargin(activity, new Insets(0, 0, 0, 25));
            getChildren().addAll(select, address, activity);
            showStatus("Balance not checked");
        }

        private void showStatus(String message) {
            lastResult = null;
            activity.getChildren().setAll(new Label(message));
        }

        private void showActivity(AddressActivityService.AddressActivity result) {
            lastResult = result;
            HBox balance = amount("Balance:", result.totalBalance());
            Tooltip.install(balance, new Tooltip("Confirmed balance plus the net change from unconfirmed transactions. This may include immature coinbase outputs."));
            activity.getChildren().setAll(balance, new Label("Transactions: " + result.transactionCount()));
            if(result.unconfirmedBalance() != 0) {
                HBox pending = amount("Mempool:", result.unconfirmedBalance());
                Tooltip.install(pending, new Tooltip("Net balance change from unconfirmed transactions, already included in the balance."));
                activity.getChildren().add(pending);
            }
        }

        private void refreshAmounts() {
            if(lastResult != null) {
                showActivity(lastResult);
            }
        }

        private static HBox amount(String title, long value) {
            CoinLabel amount = new CoinLabel();
            amount.setValue(value);
            amount.refresh();
            return new HBox(4, new Label(title), amount);
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
                DialogPane pane = getDialogPane();
                Stage stage = (Stage)pane.getScene().getWindow();
                pane.applyCss();
                double rowsHeight = addresses.prefHeight(Math.max(1, pane.getWidth() - 40));
                addressScroll.setPrefViewportHeight(rowsHeight);
                pane.layout();
                Rectangle2D screen = Screen.getScreensForRectangle(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight())
                        .stream().findFirst().orElse(Screen.getPrimary()).getVisualBounds();
                double decorationHeight = Math.max(0, stage.getHeight() - pane.getHeight());
                double excess = pane.prefHeight(pane.getWidth()) + decorationHeight - (screen.getHeight() - 24);
                if(excess > 0) {
                    //Keep the confirmation and actions visible, scrolling only the candidate addresses on shorter screens.
                    addressScroll.setPrefViewportHeight(Math.max(80, rowsHeight - excess));
                }
                stage.sizeToScene();
                stage.setY(Math.max(screen.getMinY(), Math.min(stage.getY(), screen.getMaxY() - stage.getHeight())));
            }
        });
    }
}
