package com.joshlong.mogul.storage;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * signing is local: no network, no real bucket, just the same credentials the client has.
 */
class TemporaryReadUriTest {

	private final S3Presigner presigner = S3Presigner.builder()
		.region(Region.US_EAST_1)
		.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("an-access-key", "a-secret")))
		.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
		.build();

	@Test
	void signsAReadOfOneObjectForAWhile() {
		var uri = new Storage(null, this.presigner).temporaryReadUri("a-bucket", "a-folder/a-file.mp3",
				Duration.ofHours(2));
		assertThat(uri.getScheme()).isEqualTo("https");
		assertThat(uri.getPath()).isEqualTo("/a-bucket/a-folder/a-file.mp3");
		assertThat(uri.getQuery()).contains("X-Amz-Expires=7200").contains("X-Amz-Signature=");
		assertThat(uri.toString()).doesNotContain("a-secret");
	}

	@Test
	void needsSomethingToSignWith() {
		assertThatIllegalStateException()
			.isThrownBy(() -> new Storage(null).temporaryReadUri("a-bucket", "a-key", Duration.ofMinutes(1)));
	}

	@Test
	void refusesANonsenseTtl() {
		assertThatIllegalStateException()
			.isThrownBy(() -> new Storage(null, this.presigner).temporaryReadUri("a-bucket", "a-key", Duration.ZERO));
	}

}
