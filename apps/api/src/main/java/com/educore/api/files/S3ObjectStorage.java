package com.educore.api.files;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

final class S3ObjectStorage implements ObjectStorage {

    private final S3Client client;
    private final FileStorageProperties properties;
    private final AtomicBoolean bucketReady = new AtomicBoolean();

    S3ObjectStorage(S3Client client, FileStorageProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public void put(String objectKey, Path source, String mediaType, long contentLength, String sha256Hex) {
        ensureBucket();
        try {
            String base64Checksum = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256Hex));
            client.putObject(PutObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(objectKey)
                            .contentType(mediaType)
                            .contentLength(contentLength)
                            .checksumSHA256(base64Checksum)
                            .metadata(Map.of("sha256", sha256Hex))
                            .build(),
                    RequestBody.fromFile(source));
        } catch (S3Exception | IllegalArgumentException exception) {
            throw new FileInfrastructureException("Private object upload failed", exception);
        }
    }

    @Override
    public void copyTo(String objectKey, OutputStream target) {
        ensureBucket();
        try (var response = client.getObject(GetObjectRequest.builder()
                .bucket(properties.bucket())
                .key(objectKey)
                .checksumMode(ChecksumMode.ENABLED)
                .build())) {
            response.transferTo(target);
        } catch (S3Exception | IOException exception) {
            throw new FileInfrastructureException("Private object download failed", exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.deleteObject(request -> request.bucket(properties.bucket()).key(objectKey));
        } catch (S3Exception exception) {
            throw new FileInfrastructureException("Private object cleanup failed", exception);
        }
    }

    @Override
    public void verifyAvailable() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(properties.bucket()).build());
            bucketReady.set(true);
        } catch (S3Exception exception) {
            throw new FileInfrastructureException("Private object bucket health check failed", exception);
        }
    }

    private void ensureBucket() {
        if (bucketReady.get()) return;
        synchronized (bucketReady) {
            if (bucketReady.get()) return;
            try {
                client.headBucket(HeadBucketRequest.builder().bucket(properties.bucket()).build());
            } catch (NoSuchBucketException exception) {
                createBucket();
            } catch (S3Exception exception) {
                if (exception.statusCode() == 404) createBucket();
                else throw new FileInfrastructureException("Private object bucket is unavailable", exception);
            }
            bucketReady.set(true);
        }
    }

    private void createBucket() {
        if (!properties.autoCreateBucket()) {
            throw new FileInfrastructureException("Private object bucket has not been provisioned");
        }
        try {
            client.createBucket(CreateBucketRequest.builder().bucket(properties.bucket()).build());
        } catch (S3Exception exception) {
            throw new FileInfrastructureException("Private object bucket could not be created", exception);
        }
    }
}
