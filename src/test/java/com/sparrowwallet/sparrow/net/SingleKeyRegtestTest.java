package com.sparrowwallet.sparrow.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.OutputDescriptor;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.crypto.*;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.wallet.*;
import com.sparrowwallet.sparrow.io.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Explicitly opt-in; see docs/testing-single-key-regtest.md. Never contacts a default node. */
@Tag("regtest")
class SingleKeyRegtestTest {
    private static final String PASSWORD = "regtest-only-wallet-password";
    @TempDir Path tempDir;

    @Test
    void allAddressTypesBroadcastAndSpendPersistedChange() throws Exception {
        String dataDir = System.getenv("SPARROW_REGTEST_DATADIR");
        String port = System.getenv("SPARROW_REGTEST_RPC_PORT");
        assertNotNull(dataDir, "Set SPARROW_REGTEST_DATADIR to a dedicated regtest data directory");
        assertNotNull(port, "Set SPARROW_REGTEST_RPC_PORT to the dedicated node's RPC port");
        Core core = new Core(dataDir, port);
        assertEquals("regtest", core.call("getblockchaininfo").getAsJsonObject().get("chain").getAsString(), "Live tests require regtest");
        Network previous = Network.get();
        Network.set(Network.REGTEST);
        try {
            String fundingWallet = "wif-live-matrix-" + UUID.randomUUID().toString().substring(0, 8);
            core.call("createwallet", fundingWallet);
            core.wallet = fundingWallet;
            String miningAddress = core.call("getnewaddress", "Mining", "bech32").getAsString();
            core.call("generatetoaddress", "101", miningAddress);
            System.out.println("Live single-key matrix: Core " + core.call("getnetworkinfo").getAsJsonObject().get("version") + ", regtest, funding wallet " + fundingWallet);
            List<ScriptType> types = List.of(ScriptType.P2PKH, ScriptType.P2SH_P2WPKH, ScriptType.P2WPKH, ScriptType.P2TR, ScriptType.P2PKH);
            for(int i = 0; i < types.size(); i++) {
                checkAddressType(core, miningAddress, types.get(i), i < 4, 1001 + i);
            }
        } finally {
            Network.set(previous);
        }
    }

    private void checkAddressType(Core core, String miningAddress, ScriptType type, boolean compressed, int scalar) throws Exception {
        String name = type + (compressed ? "-compressed" : "-uncompressed");
        // Deliberately public, nonvaluable keys confined to the asserted regtest chain.
        ECKey key = ECKey.fromPrivate(BigInteger.valueOf(scalar), compressed);
        String wif = key.getPrivateKeyEncoded().toString();
        Wallet wallet = Wallet.fromSingleKey(name, DumpedPrivateKey.fromBase58(wif).getKey(), type);
        wallet.checkWallet();
        WalletNode receive = wallet.getFreshNode(KeyPurpose.RECEIVE);
        receive.setLabel("Reusable regtest address");
        String address = receive.getAddress().toString();
        String descriptor = OutputDescriptor.getOutputDescriptor(wallet).toString(true);
        assertFalse(descriptor.contains(wif));
        JsonObject info = core.call("getdescriptorinfo", descriptor).getAsJsonObject();
        assertFalse(info.get("hasprivatekeys").getAsBoolean());
        assertFalse(info.get("isrange").getAsBoolean());
        var addresses = core.call("deriveaddresses", info.get("descriptor").getAsString()).getAsJsonArray();
        assertEquals(1, addresses.size());
        assertEquals(address, addresses.get(0).getAsString());

        String fundingId = core.call("sendtoaddress", address, "0.01").getAsString();
        core.call("generatetoaddress", "1", miningAddress);
        recordConfirmed(core, wallet, fundingId);
        assertEquals(1_000_000L, balance(wallet));
        assertEquals(1, wallet.getWalletUtxos().size());
        String firstSpend = spend(core, wallet, miningAddress, 100_000L);
        long firstChange = balance(wallet);
        assertTrue(firstChange > 890_000L && firstChange < 900_000L);

        wallet = saveAndReopenEncrypted(wallet);
        assertEquals(address, wallet.getFreshNode(KeyPurpose.RECEIVE).getAddress().toString());
        assertEquals("Reusable regtest address", wallet.getFreshNode(KeyPurpose.RECEIVE).getLabel());
        assertEquals(firstChange, balance(wallet));
        assertEquals(1, wallet.getWalletUtxos().size());
        assertEquals(firstSpend, wallet.getWalletUtxos().keySet().iterator().next().getHash().toString());
        String secondSpend = spend(core, wallet, miningAddress, 100_000L);
        assertTrue(balance(wallet) < firstChange - 100_000L);
        assertEquals(1, wallet.getWalletAddresses().size());
        assertEquals(1, wallet.getWalletUtxos().size());
        assertSame(wallet.getFreshNode(KeyPurpose.RECEIVE), wallet.getFreshNode(KeyPurpose.CHANGE));
        System.out.println(name + " address=" + address + " funding=" + fundingId + " spend=" + firstSpend + " changeSpend=" + secondSpend + " remainingSats=" + balance(wallet));
        wallet.clearPrivate();
    }

    private String spend(Core core, Wallet wallet, String miningAddress, long amount) throws Exception {
        Address recipient = Address.fromString(core.call("getnewaddress", "Recipient", "bech32").getAsString());
        WalletNode reusable = wallet.getFreshNode(KeyPurpose.RECEIVE);
        List<BlockTransactionHashIndex> previousUtxos = new ArrayList<>(wallet.getWalletUtxos().keySet());
        TransactionParameters parameters = new TransactionParameters(List.of(new MaxUtxoSelector()), Collections.emptyList(),
                List.of(new Payment(recipient, "Live regtest payment", amount, false)), Collections.emptyList(), Set.of(reusable),
                2.0d, 1.0d, 1.0d, null, core.call("getblockcount").getAsInt(), false, false, true);
        WalletTransaction walletTransaction = wallet.createWalletTransaction(parameters);
        PSBT psbt = walletTransaction.createPSBT();
        assertTrue(psbt.getExtendedPublicKeys().isEmpty());
        assertTrue(wallet.canSignAllInputs(psbt));
        wallet.sign(psbt);
        psbt = new PSBT(psbt.serialize());
        psbt.verifySignatures();
        wallet.finalise(psbt);
        assertTrue(psbt.isFinalized());
        Transaction signed = psbt.extractTransaction();
        assertEquals(2, signed.getOutputs().size());
        assertEquals(1L, signed.getOutputs().stream().filter(output -> output.getScript().equals(reusable.getOutputScript())).count());
        assertEquals(amount, signed.getOutputs().stream().filter(output -> output.getScript().equals(recipient.getOutputScript())).findFirst().orElseThrow().getValue());
        String hex = Utils.bytesToHex(signed.bitcoinSerialize());
        JsonObject accepted = core.call("testmempoolaccept", "[\"" + hex + "\"]").getAsJsonArray().get(0).getAsJsonObject();
        assertTrue(accepted.get("allowed").getAsBoolean(), accepted.toString());
        String txid = core.call("sendrawtransaction", hex).getAsString();
        assertEquals(signed.getTxId().toString(), txid);
        assertTrue(core.call("getmempoolentry", txid).getAsJsonObject().get("vsize").getAsInt() > 0);
        JsonObject decoded = core.call("decoderawtransaction", hex).getAsJsonObject();
        assertEquals(txid, decoded.get("txid").getAsString());
        assertEquals(2, decoded.getAsJsonArray("vout").size());
        assertEquals(reusable.getAddress().toString(), decoded.getAsJsonArray("vout").asList().stream().map(JsonElement::getAsJsonObject)
                .map(output -> output.getAsJsonObject("scriptPubKey")).filter(script -> script.get("hex").getAsString().equals(Utils.bytesToHex(reusable.getOutputScript().getProgram())))
                .findFirst().orElseThrow().get("address").getAsString());
        core.call("generatetoaddress", "1", miningAddress);
        BlockTransaction confirmed = recordConfirmed(core, wallet, txid);
        for(BlockTransactionHashIndex previous : previousUtxos) {
            previous.setSpentBy(new BlockTransactionHashIndex(confirmed.getHash(), confirmed.getHeight(), confirmed.getDate(), null, 0, previous.getValue()));
        }
        assertEquals(1, wallet.getWalletUtxos().size());
        return txid;
    }

    private BlockTransaction recordConfirmed(Core core, Wallet wallet, String txid) throws Exception {
        // All transactions involve the independent Core funder, so no txindex is needed.
        JsonObject tx = core.call("gettransaction", txid).getAsJsonObject();
        assertTrue(tx.get("confirmations").getAsInt() > 0);
        JsonObject header = core.call("getblockheader", tx.get("blockhash").getAsString()).getAsJsonObject();
        int height = header.get("height").getAsInt();
        Date date = new Date(header.get("time").getAsLong() * 1000L);
        Transaction transaction = new Transaction(Utils.hexToBytes(tx.get("hex").getAsString()));
        BlockTransaction confirmed = new BlockTransaction(transaction.getTxId(), height, date, null, transaction);
        wallet.updateTransactions(Map.of(confirmed.getHash(), confirmed));
        wallet.setStoredBlockHeight(core.call("getblockcount").getAsInt());
        WalletNode node = wallet.getFreshNode(KeyPurpose.RECEIVE);
        for(int index = 0; index < transaction.getOutputs().size(); index++) {
            TransactionOutput output = transaction.getOutputs().get(index);
            if(output.getScript().equals(node.getOutputScript())) {
                node.getTransactionOutputs().add(new BlockTransactionHashIndex(transaction.getTxId(), height, date, null, index, output.getValue()));
                JsonObject utxo = core.call("gettxout", txid, Integer.toString(index)).getAsJsonObject();
                assertEquals(output.getValue(), utxo.get("value").getAsBigDecimal().movePointRight(8).longValueExact());
            }
        }
        return confirmed;
    }

    private Wallet saveAndReopenEncrypted(Wallet wallet) throws Exception {
        Path file = tempDir.resolve(wallet.getName() + ".mv.db");
        Persistence savedPersistence = PersistenceType.DB.getInstance();
        Storage storage = new Storage(savedPersistence, file.toFile());
        Argon2KeyDeriver deriver = new Argon2KeyDeriver();
        savedPersistence.setKeyDeriver(deriver);
        ECKey encryptionKey = deriver.deriveECKey(PASSWORD);
        Key key = new Key(encryptionKey.getPrivKeyBytes(), deriver.getSalt(), EncryptionType.Deriver.ARGON2);
        try {
            wallet.encrypt(key);
            storage.setEncryptionPubKey(ECKey.fromPublicOnly(encryptionKey));
            storage.saveWallet(wallet);
        } finally {
            key.clear();
            storage.closeAndWait();
        }
        Persistence persistence = PersistenceType.DB.getInstance();
        Storage reopenedStorage = new Storage(persistence, file.toFile());
        try {
            WalletAndKey loaded = persistence.loadWallet(reopenedStorage, PASSWORD);
            try {
                Wallet reopened = loaded.getWallet();
                assertTrue(reopened.isEncrypted());
                reopened.decrypt(loaded.getKey());
                reopened.checkWallet();
                return reopened.copy();
            } finally {
                loaded.clear();
            }
        } finally {
            reopenedStorage.closeAndWait();
        }
    }

    private static long balance(Wallet wallet) {
        return wallet.getWalletUtxos().keySet().stream().mapToLong(BlockTransactionHashIndex::getValue).sum();
    }

    private static class Core {
        private final List<String> command;
        private String wallet;

        private Core(String dataDir, String port) {
            Path directory = Path.of(dataDir).toAbsolutePath();
            assertTrue(directory.resolve("regtest/.cookie").toFile().isFile(), "Explicit datadir must contain the dedicated regtest cookie");
            assertTrue(port.matches("[0-9]{1,5}"));
            command = List.of(System.getenv().getOrDefault("BITCOIN_CLI", "bitcoin-cli"), "-regtest", "-datadir=" + directory,
                    "-rpcconnect=127.0.0.1", "-rpcport=" + port);
        }

        private JsonElement call(String method, String... arguments) throws Exception {
            List<String> args = new ArrayList<>(command);
            if(wallet != null) {
                args.add("-rpcwallet=" + wallet);
            }
            args.add(method);
            args.addAll(List.of(arguments));
            Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
            if(!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                fail("Regtest RPC timed out: " + method);
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            assertEquals(0, process.exitValue(), "Regtest RPC failed: " + method + ": " + output);
            // bitcoin-cli prints string RPC results without JSON quotes.
            return output.startsWith("{") || output.startsWith("[") || output.equals("null") || output.matches("[0-9]+")
                    ? JsonParser.parseString(output) : new com.google.gson.JsonPrimitive(output);
        }
    }
}
