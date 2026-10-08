package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.crypto.DumpedPrivateKey;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PrivateKeyImportScanHandlerTest {
    private static final String TEST_WIF = ECKey.fromPrivate(BigInteger.ONE).getPrivateKeyEncoded().toString();

    @Test
    void plainWifScanReachesTheExistingWifValidation() {
        AtomicReference<String> input = new AtomicReference<>("");
        AtomicReference<String> error = new AtomicReference<>();
        PrivateKeyImportScanHandler.handle(new QRScanDialog.Result("  " + TEST_WIF + "\n"), input::set, () -> {}, error::set);

        assertEquals(TEST_WIF, input.get());
        assertEquals(BigInteger.ONE, DumpedPrivateKey.fromBase58(input.get()).getKey().getPrivKey());
        assertNull(error.get());
    }

    @Test
    void missingCameraPreservesTheEnteredPrivateKeyAndExplainsTheFailure() {
        AtomicReference<String> input = new AtomicReference<>(TEST_WIF);
        AtomicReference<String> error = new AtomicReference<>();
        PrivateKeyImportScanHandler.handle(new QRScanDialog.Result(new UnsupportedOperationException("No cameras available")), input::set, () -> fail("Camera failure must preserve address confirmation"), error::set);

        assertEquals(TEST_WIF, input.get());
        assertTrue(error.get().contains("Unable to scan the QR code"));
        assertTrue(error.get().contains("camera access is allowed"));
        assertTrue(error.get().contains("paste the WIF"));
        assertFalse(error.get().contains(TEST_WIF));
    }

    @Test
    void scannerErrorsCannotExposePrivateInputOrClearTheExistingKey() {
        AtomicReference<String> input = new AtomicReference<>(TEST_WIF);
        AtomicReference<String> error = new AtomicReference<>();
        String unsafeMessage = "Failed to process private payload " + TEST_WIF;
        PrivateKeyImportScanHandler.handle(new QRScanDialog.Result(new IllegalStateException(unsafeMessage)), input::set, () -> fail("Camera failure must preserve address confirmation"), error::set);

        assertEquals(TEST_WIF, input.get());
        assertTrue(error.get().contains("Unable to scan the QR code"));
        assertFalse(error.get().contains(unsafeMessage));
        assertFalse(error.get().contains(TEST_WIF));
    }

    @Test
    void recognizedAddressQrCannotLeaveAnOldPrivateKeySelectedForImport() {
        AtomicReference<String> input = new AtomicReference<>(TEST_WIF);
        AtomicReference<String> error = new AtomicReference<>();
        QRScanDialog.Result addressResult = new QRScanDialog.Result(ScriptType.P2WPKH.getAddress(PolicyType.SINGLE_HD, ECKey.fromPrivate(BigInteger.TWO)));
        PrivateKeyImportScanHandler.handle(addressResult, input::set, () -> {}, error::set);

        assertEquals("", input.get());
        assertEquals("The QR code must contain a WIF private key.", error.get());
    }

    @Test
    void successfulRetryRevalidatesAnUnchangedWifAfterCameraFailure() {
        StringProperty input = new SimpleStringProperty(TEST_WIF);
        AtomicInteger textChanges = new AtomicInteger();
        input.addListener((observable, oldValue, newValue) -> textChanges.incrementAndGet());
        AtomicReference<String> error = new AtomicReference<>();
        AtomicInteger revalidations = new AtomicInteger();
        Runnable revalidate = () -> {
            assertEquals(BigInteger.ONE, DumpedPrivateKey.fromBase58(input.get()).getKey().getPrivKey());
            error.set(null);
            revalidations.incrementAndGet();
        };

        PrivateKeyImportScanHandler.handle(new QRScanDialog.Result(new UnsupportedOperationException("No cameras available")), input::set, revalidate, error::set);
        assertNotNull(error.get());
        assertEquals(0, revalidations.get());

        PrivateKeyImportScanHandler.handle(new QRScanDialog.Result("  " + TEST_WIF + "\n"), input::set, revalidate, error::set);
        assertEquals(0, textChanges.get(), "JavaFX does not notify a listener when the same WIF is scanned again");
        assertEquals(1, revalidations.get());
        assertNull(error.get());
        assertEquals(TEST_WIF, input.get());
    }
}
