package com.educore.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.educore.api.config.ApplicationProperties;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StaffAccountInvitationTokenProtectorTest {
    private final UUID organisation=UUID.randomUUID(),school=UUID.randomUUID(),invitation=UUID.randomUUID();
    private final SensitiveValueCipher cipher=new SensitiveValueCipher(properties(),new SecureRandom());
    private final StaffAccountInvitationTokenProtector protector=new StaffAccountInvitationTokenProtector(cipher);
    private final String token=new SessionTokenCodec(new SecureRandom()).newRawToken();

    @Test void roundTripsOnlyWithTheExactOrganisationSchoolAndInvitation(){
        var value=protector.protect(token,organisation,school,invitation);
        assertThat(value.ciphertext()).doesNotContain(token);
        assertThat(protector.reveal(value,organisation,school,invitation)).isEqualTo(token);
        assertThatThrownBy(()->protector.reveal(value,UUID.randomUUID(),school,invitation)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->protector.reveal(value,organisation,UUID.randomUUID(),invitation)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->protector.reveal(value,organisation,school,UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
    }
    @Test void staffCiphertextCannotBeReadAsAGuardianInvitation(){
        var value=protector.protect(token,organisation,school,invitation);
        var guardian=new InvitationTokenProtector(cipher);
        assertThatThrownBy(()->guardian.revealForDelivery(new InvitationTokenProtector.ProtectedToken(value.ciphertext(),value.nonce()),invitation.toString()))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void freshNoncesProtectRepeatedPlaintextAndSecretRecordsAreRedacted(){
        var first=protector.protect(token,organisation,school,invitation);
        var second=protector.protect(token,organisation,school,invitation);
        assertThat(first.nonce()).isNotEqualTo(second.nonce());assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(first.toString()).doesNotContain(token,first.ciphertext(),first.nonce());
    }
    @Test void missingPurposeScopeIsRejectedRatherThanSharingAnEncryptionNamespace(){
        assertThatThrownBy(()->protector.protect(token,null,school,invitation)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->protector.protect(token,organisation,null,invitation)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->protector.protect(token,organisation,school,null)).isInstanceOf(IllegalArgumentException.class);
    }
    private static ApplicationProperties properties(){
        byte[] key=new byte[32];new SecureRandom().nextBytes(key);
        var security=new ApplicationProperties.Security("educore_session",false,Duration.ofHours(8),Duration.ofDays(30),Duration.ofMinutes(45),"",
                Base64.getEncoder().encodeToString(key),Duration.ofMinutes(5),Duration.ofMinutes(10),Duration.ofHours(72),"EduCore");
        return new ApplicationProperties("http://localhost:3000",List.of("http://localhost:3000"),Optional.empty(),Optional.empty(),security,new ApplicationProperties.Bootstrap(false,"",""));
    }
}
