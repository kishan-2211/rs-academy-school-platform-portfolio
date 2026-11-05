package com.educore.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class SessionTokenCodecTest {

    private final SessionTokenCodec codec = new SessionTokenCodec(new SecureRandom());

    @Test
    void producesUnpredictableUrlSafeTokensAndStableHashes() {
        String first = codec.newRawToken();
        String second = codec.newRawToken();

        assertThat(first).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(second).isNotEqualTo(first);
        assertThat(codec.hash(first)).hasSize(64).matches("[a-f0-9]+");
        assertThat(codec.hash(first)).isEqualTo(codec.hash(first));
        assertThat(codec.hash(second)).isNotEqualTo(codec.hash(first));
    }
}
