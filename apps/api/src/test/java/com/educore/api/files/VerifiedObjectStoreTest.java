package com.educore.api.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import com.educore.api.documents.OfficialDocumentObjectStore;
import com.educore.api.payroll.PayrollPayslipObjectStore;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Consumer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VerifiedObjectStoreTest {
    @ParameterizedTest
    @ValueSource(strings = { "attachment", "official-document", "payslip" })
    void eachExistingStoreBoundaryVerifiesBeforeReleasingBytes(String kind) throws Exception {
        byte[] bytes = "Synthetic document bytes only".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var output = new ByteArrayOutputStream();
        Consumer<String> download;
        if (kind.equals("attachment")) {
            var storage = mock(ObjectStorage.class, CALLS_REAL_METHODS);
            doAnswer(call -> { ((OutputStream) call.getArgument(1)).write(bytes); return null; })
                    .when(storage).copyTo(anyString(), any());
            download = sha -> storage.copyVerifiedTo("synthetic-key", bytes.length, sha, output);
        } else if (kind.equals("official-document")) {
            var storage = mock(OfficialDocumentObjectStore.class, CALLS_REAL_METHODS);
            doAnswer(call -> { ((OutputStream) call.getArgument(1)).write(bytes); return null; })
                    .when(storage).copyTo(anyString(), any());
            download = sha -> storage.copyVerifiedTo("synthetic-key", bytes.length, sha, output);
        } else {
            var storage = mock(PayrollPayslipObjectStore.class, CALLS_REAL_METHODS);
            doAnswer(call -> { ((OutputStream) call.getArgument(1)).write(bytes); return null; })
                    .when(storage).copyTo(anyString(), any());
            download = sha -> storage.copyVerifiedTo("synthetic-key", bytes.length, sha, output);
        }
        assertThatThrownBy(() -> download.accept("0".repeat(64)))
                .isInstanceOf(FileInfrastructureException.class);
        assertThat(output.size()).isZero();
        download.accept(digest);
        assertThat(output.toByteArray()).isEqualTo(bytes);
    }
}
