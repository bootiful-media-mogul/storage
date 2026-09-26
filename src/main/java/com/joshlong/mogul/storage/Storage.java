package com.joshlong.mogul.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.BufferedInputStream;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

@Component
public class Storage {

	private final Logger log = LoggerFactory.getLogger(getClass());

	/**
	 * what every object this class writes is told about its own cacheability.
	 * <p>
	 * a managed file's key never changes, so a re-rendered episode lands at the URL the
	 * old one is already cached under. the way out is not a shorter TTL -- it is that
	 * callers address these objects with a {@code ?v=<etag>} that changes when the bytes
	 * do, which makes each version a distinct thing that can then be cached as hard as
	 * you like.
	 * <p>
	 * that only holds while the CDN actually keys on {@code v}. if the distribution's
	 * cache policy drops query strings, every version collides on one entry and this
	 * header pins whichever one got there first for a year. see
	 * {@code pipeline/bin/cloudfront_cache_policy.sh}, which is what puts {@code v} in
	 * the cache key; it has to be in place before this reaches production.
	 */
	private static final String CACHE_CONTROL = "public, max-age=31536000, immutable";

	private final S3Client s3;

	public Storage(S3Client s3) {
		this.s3 = s3;
	}

	public void remove(URI uri) {
		this.validUri(uri);
		this.remove(uri.getHost(), uri.getPath());
	}

	public void remove(String bucket, String objectName) {
		if (this.bucketExists(bucket)) {
			var delete = DeleteObjectRequest.builder().bucket(bucket).key(objectName).build();
			this.s3.deleteObject(delete);
		}
	}

	public void write(URI uri, Resource resource, MediaType mediaType) {
		this.validUri(uri);
		this.write(uri.getHost(), uri.getPath(), resource, mediaType);
	}

	/**
	 * reads an object <em>out</em> of S3 and onto local disk, for the benefit of the
	 * tools -- {@code ffmpeg}, {@code magick} -- that insist on a path and won't look at
	 * an {@link java.io.InputStream}.
	 */
	public void read(String bucket, String objectName, File destination) {
		// the SDK refuses to write over an existing file, and a temp file is created
		// the moment it is named, so clear the placeholder out of the way first.
		if (destination.exists())
			Assert.state(destination.delete(), () -> "could not clear the local file [" + destination + "]");
		this.log.debug("reading [{}/{}] into [{}]", bucket, objectName, destination.getAbsolutePath());
		var request = GetObjectRequest.builder().bucket(bucket).key(objectName).build();
		this.s3.getObject(request, ResponseTransformer.toFile(destination));
		Assert.state(destination.exists(), () -> "the read of [" + bucket + "/" + objectName + "] produced no file");
	}

	/**
	 * writes a file from local disk <em>into</em> S3 in a single PUT. the SDK knows the
	 * content length up front this way, so there's no reason to pay for the multipart
	 * dance that {@link #write(String, String, Resource, MediaType)} has to do when all
	 * it's been handed is a stream.
	 */
	public void write(String bucket, String objectName, File file, MediaType mediaType) {
		Assert.state(file.exists() && file.isFile(), () -> "the file [" + file + "] must exist to be written");
		this.log.debug("writing [{}] ({} bytes) to [{}/{}]", file.getAbsolutePath(), file.length(), bucket, objectName);
		this.ensureBucketExists(bucket);
		var builder = PutObjectRequest.builder().bucket(bucket).key(objectName).cacheControl(CACHE_CONTROL);
		if (mediaType != null)
			builder = builder.contentType(mediaType.toString());
		this.s3.putObject(builder.build(), RequestBody.fromFile(file));
	}

	/*
	 * writes N-mb sized chunks at a time to s3
	 */
	protected void doWriteForLargeFiles(String bucketName, String keyName, Resource resource, DataSize maxSize,
			MediaType mediaType) throws Exception {
		try (var inputStream = new BufferedInputStream(resource.getInputStream())) {
			var chunkSize = (int) maxSize.toBytes();
			var builder = CreateMultipartUploadRequest.builder()
				.bucket(bucketName)
				.key(keyName)
				.cacheControl(CACHE_CONTROL);
			if (mediaType != null)
				builder = builder.contentType(mediaType.toString());
			var createMultipartUploadRequest = builder.build();
			var response = this.s3.createMultipartUpload(createMultipartUploadRequest);
			var uploadId = response.uploadId();
			var completedParts = new ArrayList<CompletedPart>();
			var partNumber = 1;
			var buffer = new byte[chunkSize];
			var bytesRead = -1;
			while ((bytesRead = inputStream.read(buffer)) > 0) {
				this.log.trace("uploading part [{}]", partNumber);
				var actualBytes = bytesRead == chunkSize ? buffer : Arrays.copyOf(buffer, bytesRead);
				var uploadPartRequest = UploadPartRequest.builder()
					.bucket(bucketName)
					.key(keyName)
					.uploadId(uploadId)
					.partNumber(partNumber)
					.build();
				var etag = this.s3.uploadPart(uploadPartRequest, RequestBody.fromBytes(actualBytes)).eTag();
				completedParts.add(CompletedPart.builder().partNumber(partNumber).eTag(etag).build());
				partNumber++;
				if (actualBytes != buffer) {
					buffer = new byte[chunkSize];
				}
			}
			var completedMultipartUpload = CompletedMultipartUpload.builder().parts(completedParts).build();
			var completeMultipartUploadRequest = CompleteMultipartUploadRequest.builder()
				.bucket(bucketName)
				.key(keyName)
				.uploadId(uploadId)
				.multipartUpload(completedMultipartUpload)
				.build();
			this.s3.completeMultipartUpload(completeMultipartUploadRequest);
		}
	}

	public void write(String bucket, String objectName, Resource resource, MediaType mediaType) {
		try {
			var largeFile = DataSize.ofMegabytes(10);
			this.log.info("started executing an S3 PUT for [{}/{}] on thread [{}]", bucket, objectName,
					Thread.currentThread());
			this.ensureBucketExists(bucket);
			this.doWriteForLargeFiles(bucket, objectName, resource, largeFile, mediaType);
			this.log.info("finished executing an S3 PUT for [{}/{}] on thread [{}]", bucket, objectName,
					Thread.currentThread());
		} //
		catch (Throwable throwable) {
			throw new RuntimeException(throwable);
		}

	}

	protected boolean bucketExists(String bucketName) {
		var buckets = this.s3.listBuckets();
		if (buckets.hasBuckets()) {
			return buckets.buckets().stream().anyMatch(bucket -> bucket.name().equalsIgnoreCase(bucketName));
		}
		return false;
	}

	/*
	 * much faster than downloading the bytes and trying to write them back up again!
	 *
	 * this is also the one write that the CDN actually sees: it is how an object reaches
	 * the visible bucket, which is what the distribution is pointed at. setting the
	 * cache-control on the PUT and not here would leave every public object without one.
	 * METADATA_DIRECTIVE is already REPLACE, so it costs nothing to say.
	 */
	public void copy(String src, String dest, String key, MediaType newContentType) {
		var copyRequestBuilder = CopyObjectRequest.builder()
			.metadataDirective("REPLACE")
			.cacheControl(CACHE_CONTROL)
			.sourceBucket(src)
			.sourceKey(key)
			.destinationBucket(dest)
			.destinationKey(key);
		copyRequestBuilder = newContentType == null ? copyRequestBuilder
				: copyRequestBuilder.contentType(newContentType.toString());
		var result = copyRequestBuilder.build();
		this.s3.copyObject(result);
	}

	public record ObjectMetadata(long contentLength, String etag) {
	}

	public ObjectMetadata metadata(String bucket, String key) {
		var request = HeadObjectRequest.builder().bucket(bucket).key(key).build();
		var head = this.s3.headObject(request);
		return new ObjectMetadata(head.contentLength(), head.eTag());
	}

	public long contentLength(String bucket, String key) {
		return this.metadata(bucket, key).contentLength();
	}

	public boolean exists(String bucket, String key) {
		var request = HeadObjectRequest.builder().bucket(bucket).key(key).build();
		try {
			this.s3.headObject(request);
			return true;
		}
		catch (Throwable throwable) {
			return false;
		}
	}

	public Resource read(String bucket, String objectName) {
		try {
			var getObjectRequest = GetObjectRequest.builder().bucket(bucket).key(objectName).build();
			var inputStream = this.s3.getObject(getObjectRequest);
			return new InputStreamResource(new BufferedInputStream(inputStream));
		}
		catch (Throwable throwable) {
			log.warn("error when reading bucket [{}] and object name [{}] from S3", bucket, objectName);
			return null;
		}
	}

	private void validUri(URI uri) {
		Assert.state(uri != null && uri.getScheme().equalsIgnoreCase("s3") && uri.getPath().split("/").length == 2,
				"this uri [" + Objects.requireNonNull(uri) + "] is not a valid s3 reference");
	}

	protected void ensureBucketExists(String bucketName) {
		if (this.bucketExists(bucketName)) {
			this.log.trace("the bucket named [{}] already exists", bucketName);
			return;
		}
		this.log.info("attempting to create the bucket called [{}]", bucketName);
		this.s3.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
	}

}
