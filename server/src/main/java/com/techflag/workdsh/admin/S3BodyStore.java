package com.techflag.workdsh.admin;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * S3-compatible object storage (AWS S3, MinIO, Alibaba Cloud OSS, Tencent Cloud COS and similar).
 * One object per accepted record; objects are never rewritten, only created and deleted.
 */
public class S3BodyStore implements BodyStore {
    private final S3Client client;
    private final String bucket;
    private final String prefix;

    public S3BodyStore(S3Client client, String bucket, String prefix) {
        this.client = client;
        this.bucket = bucket;
        this.prefix = prefix == null || prefix.isBlank() ? "" : prefix.replaceAll("^/+|/+$", "") + "/";
    }

    public boolean external() { return true; }

    private String object(String key) { return prefix + key; }

    public void put(String key, String text) {
        client.putObject(PutObjectRequest.builder().bucket(bucket).key(object(key)).contentType("text/plain; charset=utf-8").build(),
                RequestBody.fromBytes(text.getBytes(StandardCharsets.UTF_8)));
    }

    public String get(String key) {
        return client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(object(key)).build()).asUtf8String();
    }

    /** One request per object: multi-object delete is not implemented uniformly by S3-compatible services. */
    public void delete(Collection<String> keys) {
        for (String key : keys) client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(object(key)).build());
    }
}
