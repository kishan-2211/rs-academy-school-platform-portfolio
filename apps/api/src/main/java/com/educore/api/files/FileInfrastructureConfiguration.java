package com.educore.api.files;

import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration
class FileInfrastructureConfiguration {

    @Bean(destroyMethod = "close")
    S3Client privateFileS3Client(FileStorageProperties properties) {
        URI endpoint = properties.endpoint();
        return S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyleAccess())
                        .build())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(properties.readTimeout())
                        .apiCallTimeout(properties.readTimeout().plus(properties.connectTimeout().multipliedBy(2)))
                        .build())
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(properties.connectTimeout())
                        .socketTimeout(properties.readTimeout()))
                .build();
    }

    @Bean
    ObjectStorage privateObjectStorage(S3Client privateFileS3Client, FileStorageProperties properties) {
        return new S3ObjectStorage(privateFileS3Client, properties);
    }

    @Bean
    MalwareScanner privateFileMalwareScanner(FileStorageProperties properties) {
        return new ClamAvMalwareScanner(properties.clamav());
    }
}
