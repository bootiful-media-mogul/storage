package com.joshlong.mogul.storage;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@AutoConfiguration
@EnableConfigurationProperties(StorageProperties.class)
class StorageAutoConfiguration {

	@Bean
	S3Client s3Client(StorageProperties properties) {
		var aws = properties.aws();
		return S3Client.builder()
			.region(Region.of(aws.region()))
			.credentialsProvider(credentials(aws))
			.forcePathStyle(true)
			.build();
	}

	/**
	 * signs {@link Storage#temporaryReadUri temporary urls}. it never talks to the
	 * network itself; it only needs the same credentials, region and addressing as the
	 * client.
	 */
	@Bean
	S3Presigner s3Presigner(StorageProperties properties) {
		var aws = properties.aws();
		return S3Presigner.builder()
			.region(Region.of(aws.region()))
			.credentialsProvider(credentials(aws))
			.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
			.build();
	}

	@Bean
	Storage storage(S3Client s3Client, S3Presigner s3Presigner) {
		return new Storage(s3Client, s3Presigner);
	}

	private static StaticCredentialsProvider credentials(StorageProperties.Aws aws) {
		return StaticCredentialsProvider.create(AwsBasicCredentials.create(aws.accessKey(), aws.accessKeySecret()));
	}

}
