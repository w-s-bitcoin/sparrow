package com.sparrowwallet.sparrow.control;

import java.util.function.Consumer;

/** Applies scanner results without exposing scanner exceptions that may contain private input. */
final class PrivateKeyImportScanHandler {
    private PrivateKeyImportScanHandler() {
    }

    static void handle(QRScanDialog.Result result, Consumer<String> setPrivateKey, Consumer<String> showError) {
        if(result.exception != null) {
            //A failed camera attempt must not discard a WIF the user has already entered.
            showError.accept("Unable to scan the QR code. Check that a camera is connected and camera access is allowed, then try again or paste the WIF private key.");
        } else if(result.payload != null) {
            //Plain WIF QR codes are text payloads; the import field performs WIF/network validation.
            setPrivateKey.accept(result.payload.trim());
        } else {
            setPrivateKey.accept("");
            showError.accept("The QR code must contain a WIF private key.");
        }
    }
}
