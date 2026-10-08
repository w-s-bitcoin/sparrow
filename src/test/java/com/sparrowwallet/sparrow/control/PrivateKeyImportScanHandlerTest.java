package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.crypto.DumpedPrivateKey;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PrivateKeyImportScanHandlerTest {
    private static final String TEST_WIF = ECKey.fromPrivate(BigInteger.ONE).getPrivateKeyEncoded().toString();

    @Test
    void plainWifScanReachesTheExistingWifValidation() {
        AtomicReference<String> input = new AtomicReference<>("");
        AtomicReference<String> error = new AtomicReference<>();
        PrivateKeyImportScanHandler.handle(new QRScanDialog.Result("  " + TEST_WIF + "\n"), input::set, error::set);

        assertEquals(TEST_WIF, input.get());
        assertEquals(BigInteger.ONE, DumpedPrivateKey.fromBase58(input.get()).getKey().getPrivKey());
        assertNull(error.get());
    }

    @Test
    void missingCameraPreservesTheEnteredPrivateKeyAndExplainsTheFailure() {
        AtomicReference<String> input = new AtomicReference<>(TEST_WIF);
        AtomicReference<String> error = new AtomicReference<>();
        PrivateKeyImportScanHandler.handle(new QRScanDialog.Result(new UnsupportedOperationException("No cameras available")), input::set, error::set);

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
        PrivateKeyImportScanHandler.handle(new QRScanDialog.Result(new IllegalStateException(unsafeMessage)), input::set, error::set);

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
        PrivateKeyImportScanHandler.handle(addressResult, input::set, error::set);

        assertEquals("", input.get());
        assertEquals("The QR code must contain a WIF private key.", error.get());
    }
}
