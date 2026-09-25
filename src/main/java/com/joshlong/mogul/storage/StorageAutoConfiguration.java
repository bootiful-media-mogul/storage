package com.joshlong.mogul.storage;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@AutoConfiguration
@EnableConfigurationProperties(StorageProperties.class)
class StorageAutoConfiguration {

	@Bean
	S3Client s3Client(StorageProperties properties) {
		var aws = properties.aws();
		var credentials = AwsBasicCredentials.create(aws.accessKey(), aws.accessKeySecret());
		return S3Client.builder()
			.region(Region.of(aws.region()))
			.credentialsProvider(StaticCredentialsProvider.create(credentials))
			.forcePathStyle(true)
			.build();
	}

	@Bean
	Storage storage(S3Client s3Client) {
		return new Storage(s3Client);
	}

}
