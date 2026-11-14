package com.techflag.workdsh.admin;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * workdsh.body-store.type selects where Desktop visible text is kept: "database" (default) or "s3".
 * Credentials come from workdsh.body-store.s3.access-key/secret-key, or the AWS default chain when both are blank.
 */
@Configuration
public class BodyStoreConfig {
    @Bean
    BodyStore bodyStore(@Value("${workdsh.body-store.type:database}") String type,
                        @Value("${workdsh.body-store.s3.endpoint:}") String endpoint,
                        @Value("${workdsh.body-store.s3.region:us-east-1}") String region,
                        @Value("${workdsh.body-store.s3.bucket:}") String bucket,
                        @Value("${workdsh.body-store.s3.prefix:}") String prefix,
                        @Value("${workdsh.body-store.s3.access-key:}") String accessKey,
                        @Value("${workdsh.body-store.s3.secret-key:}") String secretKey,
                        @Value("${workdsh.body-store.s3.path-style:false}") boolean pathStyle) {
        return switch (type) {
            case "database" -> new BodyStore.Database();
            case "s3" -> {
                if (bucket.isBlank()) throw new IllegalStateException("workdsh.body-store.s3.bucket is required when workdsh.body-store.type=s3");
                if (accessKey.isBlank() != secretKey.isBlank()) throw new IllegalStateException("Set both S3 access key and secret key, or neither");
                var builder = S3Client.builder()
                        .region(Region.of(region))
                        .httpClientBuilder(UrlConnectionHttpClient.builder())
                        .forcePathStyle(pathStyle)
                        // Plain, fully signed bodies: aws-chunked uploads (used on plain HTTP endpoints) are not
                        // supported by every S3-compatible service.
                        .serviceConfiguration(S3Configuration.builder().chunkedEncodingEnabled(false).build())
                        // Only checksums the protocol requires: S3-compatible services differ on aws-chunked trailers.
                        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                        .credentialsProvider(accessKey.isBlank() ? DefaultCredentialsProvider.builder().build()
                                : StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
                if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
                yield new S3BodyStore(builder.build(), bucket, prefix);
            }
            default -> throw new IllegalStateException("workdsh.body-store.type must be database or s3");
        };
    }
}
