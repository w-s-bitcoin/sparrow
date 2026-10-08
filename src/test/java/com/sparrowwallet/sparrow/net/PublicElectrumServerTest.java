package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.drongo.policy.PolicyType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PublicElectrumServerTest {
    @Test
    void openingSingleKeyWalletDoesNotRemovePublicServerFailoverCandidates() {
        for(PublicElectrumServer server : PublicElectrumServer.values()) {
            assertTrue(server.isSupportedPolicyType(PolicyType.SINGLE_KEY), server.getUrl());
            assertTrue(server.supportsAllPolicyTypes(List.of(PolicyType.SINGLE_HD, PolicyType.MULTI_HD, PolicyType.SINGLE_KEY)), server.getUrl());
        }
    }

    @Test
    void singleKeySupportDoesNotBypassSilentPaymentServerRequirements() {
        List<PublicElectrumServer> candidates = Arrays.stream(PublicElectrumServer.values())
                .filter(server -> server.supportsAllPolicyTypes(List.of(PolicyType.SINGLE_KEY, PolicyType.SINGLE_SP)))
                .toList();
        assertEquals(List.of(PublicElectrumServer.FRIGATE_2140_DEV), candidates);
    }
}
