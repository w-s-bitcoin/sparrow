package com.sparrowwallet.sparrow.io;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.OutputDescriptor;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.crypto.Argon2KeyDeriver;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.crypto.EncryptionType;
import com.sparrowwallet.drongo.crypto.Key;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.SingleKey;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.io.db.KeystoreDao;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SingleKeyPersistenceTest {
    private static final String PASSWORD = "single-key-storage-test";
    private static final List<ScriptType> SCRIPT_TYPES = List.of(ScriptType.P2PKH, ScriptType.P2SH_P2WPKH, ScriptType.P2WPKH, ScriptType.P2TR);

    @TempDir
    Path tempDir;

    @Test
    void unencryptedDatabaseRoundTripsEveryAddressTypeAndCompression() throws Exception {
        assertRoundTrips(PersistenceType.DB, false);
    }

    @Test
    void encryptedDatabaseRoundTripsEveryAddressTypeAndCompression() throws Exception {
        assertRoundTrips(PersistenceType.DB, true);
    }

    @Test
    void unencryptedJsonRoundTripsEveryAddressTypeAndCompression() throws Exception {
        assertRoundTrips(PersistenceType.JSON, false);
    }

    @Test
    void encryptedJsonRoundTripsEveryAddressTypeAndCompression() throws Exception {
        assertRoundTrips(PersistenceType.JSON, true);
    }

    private void assertRoundTrips(PersistenceType persistenceType, boolean encrypted) throws Exception {
        for(ScriptType scriptType : SCRIPT_TYPES) {
            assertRoundTrip(persistenceType, encrypted, scriptType, true);
        }
        assertRoundTrip(persistenceType, encrypted, ScriptType.P2PKH, false);
    }

    private void assertRoundTrip(PersistenceType persistenceType, boolean encrypted, ScriptType scriptType, boolean compressed) throws Exception {
        String name = persistenceType + "-" + scriptType + "-" + compressed;
        ECKey importedKey = ECKey.fromPrivate(BigInteger.ONE, compressed);
        Wallet wallet = Wallet.fromSingleKey(name, importedKey, scriptType);
        wallet.getKeystores().getFirst().setLabel("Imported savings");
        wallet.getFreshNode(KeyPurpose.RECEIVE).setLabel("Reusable savings address");
        String address = wallet.getFreshNode(KeyPurpose.RECEIVE).getAddress().toString();

        if(scriptType == ScriptType.P2PKH) {
            //Published secp256k1 generator / private-key-one examples, independent of the persisted model.
            assertEquals(compressed ? "1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMH" : "1EHNa6Q4Jz2uvNExL497mE43ikXhwF6kZm", address);
        }

        Persistence persistence = persistenceType.getInstance();
        Storage storage = new Storage(persistence, tempDir.resolve(name + "." + persistenceType.getExtension()).toFile());
        configureEncryption(storage, wallet, encrypted);
        File walletFile;
        try {
            storage.saveWallet(wallet);
            walletFile = storage.getWalletFile();
        } finally {
            storage.closeAndWait();
        }

        Persistence reopenedPersistence = persistenceType.getInstance();
        Storage reopenedStorage = new Storage(reopenedPersistence, walletFile);
        try {
            assertEquals(encrypted, reopenedPersistence.isEncrypted(walletFile));
            WalletAndKey loaded = encrypted ? reopenedPersistence.loadWallet(reopenedStorage, PASSWORD) : reopenedPersistence.loadWallet(reopenedStorage);
            Wallet reopened = loaded.getWallet();
            assertTrue(reopened.isValid(), name);
            assertTrue(reopened.isSingleKeyWallet());
            assertEquals(scriptType, reopened.getScriptType());
            assertEquals("Imported savings", reopened.getKeystores().getFirst().getLabel());
            assertArrayEquals(importedKey.getPubKey(), reopened.getKeystores().getFirst().getSinglePublicKey());
            assertNull(reopened.getKeystores().getFirst().getExtendedPublicKey());
            assertEquals(address, reopened.getFreshNode(KeyPurpose.RECEIVE).getAddress().toString());
            assertEquals("Reusable savings address", reopened.getFreshNode(KeyPurpose.RECEIVE).getLabel());
            assertSame(reopened.getFreshNode(KeyPurpose.RECEIVE), reopened.getFreshNode(KeyPurpose.CHANGE));
            assertEquals(encrypted, reopened.isEncrypted());
            assertPublicOutput(reopened, importedKey);
            if(encrypted) {
                assertThrows(IllegalStateException.class, () -> reopened.getKeystores().getFirst().getSingleKey().getKey());
                reopened.decrypt(loaded.getKey());
            }
            assertEquals(importedKey.getPrivateKeyEncoded().toString(), reopened.getKeystores().getFirst().getSingleKey().getKey().getPrivateKeyEncoded().toString());
            assertTrue(reopened.isValid());
            loaded.clear();
        } finally {
            reopenedStorage.closeAndWait();
        }
    }

    @Test
    void sparrowBackupAndExportRestoreThePrivateKey() throws Exception {
        for(boolean encrypted : List.of(false, true)) {
            Wallet wallet = Wallet.fromSingleKey("backup-" + encrypted, ECKey.fromPrivate(BigInteger.ONE), ScriptType.P2TR);
            Storage storage = new Storage(PersistenceType.DB, tempDir.resolve(wallet.getName() + ".mv.db").toFile());
            configureEncryption(storage, wallet, encrypted);
            try {
                storage.saveWallet(wallet);
                ByteArrayOutputStream backup = new ByteArrayOutputStream();
                storage.copyWallet(backup);
                assertRestoredBackup(backup, encrypted);

                ByteArrayOutputStream exported = new ByteArrayOutputStream();
                new Sparrow().exportWallet(wallet, storage, exported);
                assertRestoredBackup(exported, encrypted);
            } finally {
                storage.closeAndWait();
            }
        }
    }

    private void assertRestoredBackup(ByteArrayOutputStream backup, boolean encrypted) throws Exception {
        Wallet restored = new Sparrow().importWallet(new ByteArrayInputStream(backup.toByteArray()), encrypted ? PASSWORD : null);
        assertTrue(restored.isValid());
        assertTrue(restored.isSingleKeyWallet());
        assertEquals(ScriptType.P2TR, restored.getScriptType());
        assertFalse(restored.isEncrypted());
        assertEquals(BigInteger.ONE, restored.getKeystores().getFirst().getSingleKey().getKey().getPrivKey());
    }

    @Test
    void openingJsonMigratesTheSingleKeyToDatabase() throws Exception {
        for(boolean encrypted : List.of(false, true)) {
            Wallet wallet = Wallet.fromSingleKey("migration-" + encrypted, ECKey.fromPrivate(BigInteger.ONE, false), ScriptType.P2PKH);
            Storage original = new Storage(PersistenceType.JSON, tempDir.resolve(wallet.getName() + ".json").toFile());
            configureEncryption(original, wallet, encrypted);
            original.saveWallet(wallet);
            File jsonFile = original.getWalletFile();
            original.closeAndWait();

            Storage storage = new Storage(jsonFile);
            try {
                WalletAndKey loaded = encrypted ? storage.loadEncryptedWallet(PASSWORD) : storage.loadUnencryptedWallet();
                assertEquals(PersistenceType.DB, storage.getType());
                assertTrue(loaded.getWallet().isValid());
                assertEquals("1EHNa6Q4Jz2uvNExL497mE43ikXhwF6kZm", loaded.getWallet().getFreshNode(KeyPurpose.RECEIVE).getAddress().toString());
                assertFalse(loaded.getWallet().getKeystores().getFirst().getSingleKey().isCompressed());
                if(encrypted) {
                    loaded.getWallet().decrypt(loaded.getKey());
                }
                assertEquals(BigInteger.ONE, loaded.getWallet().getKeystores().getFirst().getSingleKey().getKey().getPrivKey());
                loaded.clear();
            } finally {
                storage.closeAndWait();
            }
        }
    }

    @Test
    void changingKeyEncryptionClearsThePreviousDatabaseRepresentation() throws Exception {
        Wallet wallet = Wallet.fromSingleKey("secret-encryption", ECKey.fromPrivate(BigInteger.ONE), ScriptType.P2WPKH);
        Storage storage = new Storage(PersistenceType.DB, tempDir.resolve("secret-encryption.mv.db").toFile());
        storage.setEncryptionPubKey(Storage.NO_PASSWORD_KEY);
        storage.saveWallet(wallet);
        storage.closeAndWait();

        Argon2KeyDeriver deriver = new Argon2KeyDeriver();
        Key encryptionKey = new Key(deriver.deriveECKey(PASSWORD).getPrivKeyBytes(), deriver.getSalt(), EncryptionType.Deriver.ARGON2);
        try {
            Jdbi jdbi = Jdbi.create("jdbc:h2:" + tempDir.resolve("secret-encryption") + ";DATABASE_TO_UPPER=false;DB_CLOSE_ON_EXIT=FALSE", "sa", "").installPlugin(new SqlObjectPlugin());
            jdbi.useHandle(handle -> {
                handle.execute("set schema wallet_master");
                KeystoreDao dao = handle.attach(KeystoreDao.class);
                wallet.encrypt(encryptionKey);
                dao.updateKeystoreEncryption(wallet.getKeystores().getFirst());
                assertNull(handle.createQuery("select privateKey from singleKey").mapTo(byte[].class).one());
                assertNotNull(handle.createQuery("select encryptedBytes from singleKey").mapTo(byte[].class).one());
                Keystore encrypted = dao.getForWalletId(wallet.getId()).getFirst();
                assertTrue(encrypted.getSingleKey().isEncrypted());
                encrypted.decrypt(encryptionKey);
                assertEquals(BigInteger.ONE, encrypted.getSingleKey().getKey().getPrivKey());

                dao.updateKeystoreEncryption(encrypted);
                assertNotNull(handle.createQuery("select privateKey from singleKey").mapTo(byte[].class).one());
                assertNull(handle.createQuery("select encryptedBytes from singleKey").mapTo(byte[].class).one());
                assertFalse(dao.getForWalletId(wallet.getId()).getFirst().getSingleKey().isEncrypted());
            });
        } finally {
            encryptionKey.clear();
        }
    }

    @Test
    void publicExportsAreWatchOnlyAndIncompatibleExportsFailBeforeWriting() throws Exception {
        ECKey key = ECKey.fromPrivate(BigInteger.ONE);
        Wallet wallet = Wallet.fromSingleKey("public-export", key, ScriptType.P2WPKH);
        assertPublicOutput(wallet, key);
        for(WalletExport exporter : List.of(new Electrum(), new ElectrumPersonalServer(), new SpecterDesktop(), new SpecterDIY(), new Bip129(), new ColdcardMultisig(), new CaravanMultisig())) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertThrows(ExportException.class, () -> exporter.exportWallet(wallet, output, null), exporter.getName());
            assertEquals(0, output.size(), exporter.getName());
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThrows(ExportException.class, () -> new Bip129().exportKeystore(wallet.getKeystores().getFirst(), output));
        assertEquals(0, output.size());
    }

    private void assertPublicOutput(Wallet wallet, ECKey privateKey) throws Exception {
        ByteArrayOutputStream descriptorExport = new ByteArrayOutputStream();
        new Descriptor().exportWallet(wallet, descriptorExport, null);
        String descriptor = descriptorExport.toString(StandardCharsets.UTF_8);
        assertFalse(descriptor.contains(privateKey.getPrivateKeyEncoded().toString()));
        assertFalse(descriptor.contains(Utils.bytesToHex(privateKey.getPrivKeyBytes())));
        assertFalse(descriptor.contains("/*"));
        Wallet watchOnly = new Descriptor().importWallet(new ByteArrayInputStream(descriptorExport.toByteArray()), null);
        assertTrue(watchOnly.isValid());
        assertFalse(watchOnly.getKeystores().getFirst().hasPrivateKey());
        assertEquals(wallet.getFreshNode(KeyPurpose.RECEIVE).getAddress(), watchOnly.getFreshNode(KeyPurpose.RECEIVE).getAddress());
        assertFalse(JsonPersistence.getGson().toJson(watchOnly).contains("singleKey"));
        assertEquals(OutputDescriptor.getOutputDescriptor(wallet).toString(), OutputDescriptor.getOutputDescriptor(watchOnly).toString());
    }

    @Test
    void jsonRejectsConflictingAndInvalidSecretRepresentations() {
        JsonObject json = JsonPersistence.getGson().toJsonTree(new SingleKey(ECKey.fromPrivate(BigInteger.ONE))).getAsJsonObject();
        json.add("encryptedKey", new JsonObject());
        assertThrows(JsonParseException.class, () -> JsonPersistence.getGson().fromJson(json, SingleKey.class));
        json.remove("encryptedKey");
        json.addProperty("privateKey", "00".repeat(32));
        assertThrows(JsonParseException.class, () -> JsonPersistence.getGson().fromJson(json, SingleKey.class));
    }

    private void configureEncryption(Storage storage, Wallet wallet, boolean encrypted) {
        Argon2KeyDeriver deriver = new Argon2KeyDeriver();
        storage.setKeyDeriver(deriver);
        storage.setEncryptionPubKey(Storage.NO_PASSWORD_KEY);
        if(encrypted) {
            ECKey encryptionKey = deriver.deriveECKey(PASSWORD);
            Key key = new Key(encryptionKey.getPrivKeyBytes(), deriver.getSalt(), EncryptionType.Deriver.ARGON2);
            try {
                wallet.encrypt(key);
                storage.setEncryptionPubKey(ECKey.fromPublicOnly(encryptionKey));
            } finally {
                key.clear();
            }
        }
    }
}
