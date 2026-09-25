package com.joshlong.mogul.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mogul.storage")
record StorageProperties(Aws aws) {

	public record Aws(String accessKey, String accessKeySecret, String region) {
	}
}
