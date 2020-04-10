/*
 * Copyright 2012-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wavefront.spring.autoconfigure.account;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Tests for {@link AccountProvisioningEnvironmentPostProcessor}.
 *
 * @author Stephane Nicoll
 */
@ExtendWith(OutputCaptureExtension.class)
class AccountProvisioningEnvironmentPostProcessorTests {

	private static final String API_TOKEN_PROPERTY = "management.metrics.export.wavefront.api-token";

	private static final String URI_PROPERTY = "management.metrics.export.wavefront.uri";

	private SpringApplication application = mock(SpringApplication.class);

	@Test
	void environmentIsNotModifiedIfApiTokenExists() {
		ConfigurableEnvironment environment = mock(ConfigurableEnvironment.class);
		given(environment.getProperty(API_TOKEN_PROPERTY)).willReturn("test");
		new AccountProvisioningEnvironmentPostProcessor().postProcessEnvironment(environment, this.application);
		verify(environment).getProperty(API_TOKEN_PROPERTY);
		verifyNoMoreInteractions(environment);
	}

	@Test
	void environmentIsNotModifiedIfApiTokenIsNotNecessary() {
		MockEnvironment environment = new MockEnvironment();
		environment.setProperty(URI_PROPERTY, "proxy://example.com:2878");
		new AccountProvisioningEnvironmentPostProcessor().postProcessEnvironment(environment, this.application);
		assertThat(environment.getProperty(API_TOKEN_PROPERTY)).isNull();
	}

	@Test
	void apiTokenIsRegisteredWithTokenFileWhenItExists() throws IOException {
		Resource apiTokenResource = mock(Resource.class);
		given(apiTokenResource.isReadable()).willReturn(true);
		given(apiTokenResource.getInputStream())
				.willReturn(new ByteArrayInputStream("abc-def".getBytes(StandardCharsets.UTF_8)));
		MockEnvironment environment = new MockEnvironment();
		new TestAccountProvisioningEnvironmentPostProcessor(apiTokenResource, (clusterInfo, applicationInfo) -> {
			throw new IllegalArgumentException("Should not be called");
		}).postProcessEnvironment(environment, this.application);
		assertThat(environment.getProperty(API_TOKEN_PROPERTY)).isEqualTo("abc-def");
		assertThat(environment.getProperty(URI_PROPERTY)).isEqualTo("https://wavefront.surf");
	}

	@Test
	void apiTokenIsRegisteredWithNewAccountWhenTokenFileDoesNotExist(@TempDir Path directory, CapturedOutput output) {
		Path apiTokenFile = directory.resolve("test.token");
		assertThat(apiTokenFile).doesNotExist();
		MockEnvironment environment = new MockEnvironment();
		TestAccountProvisioningEnvironmentPostProcessor postProcessor = new TestAccountProvisioningEnvironmentPostProcessor(
				new PathResource(apiTokenFile), (clusterInfo, applicationInfo) -> {
					assertThat(clusterInfo).isEqualTo("https://wavefront.surf");
					return new AccountInfo("abc-def", "/us/test");
				});
		postProcessor.postProcessEnvironment(environment, this.application);
		assertThat(environment.getProperty(API_TOKEN_PROPERTY)).isEqualTo("abc-def");
		assertThat(environment.getProperty(URI_PROPERTY)).isEqualTo("https://wavefront.surf");
		assertThat(apiTokenFile).exists();
		assertThat(apiTokenFile).hasContent("abc-def");
		postProcessor.onApplicationEvent(mockApplicationStartedEvent());
		assertThat(output).contains("https://wavefront.surf", "https://wavefront.surf/us/test",
				API_TOKEN_PROPERTY + "=abc-def");
	}

	@Test
	void apiTokenRegistrationFailureLogsWarning(CapturedOutput output) {
		Resource apiTokenResource = mock(Resource.class);
		given(apiTokenResource.isReadable()).willReturn(false);
		MockEnvironment environment = new MockEnvironment();
		TestAccountProvisioningEnvironmentPostProcessor postProcessor = new TestAccountProvisioningEnvironmentPostProcessor(
				apiTokenResource, (clusterInfo, applicationInfo) -> {
					throw new AccountProvisioningFailedException("test message");
				});
		postProcessor.postProcessEnvironment(environment, this.application);
		verify(apiTokenResource).isReadable();
		verifyNoMoreInteractions(apiTokenResource);
		postProcessor.onApplicationEvent(mockApplicationStartedEvent());
		assertThat(output).contains("https://wavefront.surf", "test message");
	}

	@Test
	void apiTokenWithIOExceptionReadingTokenFileDoesNotFail() throws IOException {
		Resource apiTokenResource = mock(Resource.class);
		given(apiTokenResource.isReadable()).willReturn(true);
		given(apiTokenResource.isFile()).willReturn(false);
		given(apiTokenResource.getInputStream()).willThrow(new IOException("test exception"));
		MockEnvironment environment = new MockEnvironment();
		new TestAccountProvisioningEnvironmentPostProcessor(apiTokenResource,
				(clusterInfo, applicationInfo) -> new AccountInfo("test", "test")).postProcessEnvironment(environment,
						this.application);
		assertThat(environment.getProperty(API_TOKEN_PROPERTY)).isEqualTo("test");
		assertThat(environment.getProperty(URI_PROPERTY)).isEqualTo("https://wavefront.surf");
	}

	@Test
	void apiTokenWithIOExceptionWritingTokenFileDoesNotFail() throws IOException {
		Resource apiTokenResource = mock(Resource.class);
		given(apiTokenResource.isReadable()).willReturn(false);
		given(apiTokenResource.isFile()).willReturn(true);
		given(apiTokenResource.getFile()).willThrow(new IOException("test exception"));
		MockEnvironment environment = new MockEnvironment();
		new TestAccountProvisioningEnvironmentPostProcessor(apiTokenResource,
				(clusterInfo, applicationInfo) -> new AccountInfo("test", "test")).postProcessEnvironment(environment,
						this.application);
		assertThat(environment.getProperty(API_TOKEN_PROPERTY)).isEqualTo("test");
		assertThat(environment.getProperty(URI_PROPERTY)).isEqualTo("https://wavefront.surf");
	}

	@Test
	void uriIsNotSetIfACustomUriIsSet() throws IOException {
		Resource apiTokenResource = mock(Resource.class);
		given(apiTokenResource.isReadable()).willReturn(true);
		given(apiTokenResource.getInputStream())
				.willReturn(new ByteArrayInputStream("abc-def".getBytes(StandardCharsets.UTF_8)));
		MockEnvironment environment = new MockEnvironment();
		environment.setProperty(URI_PROPERTY, "https://example.com");
		new TestAccountProvisioningEnvironmentPostProcessor(apiTokenResource, (clusterInfo, applicationInfo) -> {
			throw new IllegalArgumentException("Should not be called");
		}).postProcessEnvironment(environment, this.application);
		assertThat(environment.getProperty(API_TOKEN_PROPERTY)).isEqualTo("abc-def");
		assertThat(environment.getProperty(URI_PROPERTY)).isEqualTo("https://example.com");
		assertThat(environment.getPropertySources().get("wavefront").getProperty(URI_PROPERTY)).isNull();
	}

	@Test
	void defaultTokenFile() {
		Resource localApiTokenResource = new AccountProvisioningEnvironmentPostProcessor().getLocalApiTokenResource();
		assertThat(localApiTokenResource.getFilename()).isEqualTo(".wavefront_token");
	}

	@Test
	void provisionAccountInvokeClientWithSuppliedArguments() {
		AccountProvisioningClient client = mock(AccountProvisioningClient.class);
		String clusterUri = "https://example.com";
		ApplicationInfo applicationInfo = mock(ApplicationInfo.class);
		new AccountProvisioningEnvironmentPostProcessor().provisionAccount(client, clusterUri, applicationInfo);
		verify(client).provisionAccount(clusterUri, applicationInfo);
	}

	private ApplicationStartedEvent mockApplicationStartedEvent() {
		return new ApplicationStartedEvent(this.application, new String[0], mock(ConfigurableApplicationContext.class));
	}

	static class TestAccountProvisioningEnvironmentPostProcessor extends AccountProvisioningEnvironmentPostProcessor {

		private final Resource localApiToResource;

		private final BiFunction<String, ApplicationInfo, AccountInfo> accountProvisioning;

		TestAccountProvisioningEnvironmentPostProcessor(Resource localApiToResource,
				BiFunction<String, ApplicationInfo, AccountInfo> accountProvisioning) {
			this.localApiToResource = localApiToResource;
			this.accountProvisioning = accountProvisioning;
		}

		@Override
		protected Resource getLocalApiTokenResource() {
			return this.localApiToResource;
		}

		@Override
		protected AccountInfo provisionAccount(AccountProvisioningClient client, String clusterUri,
				ApplicationInfo applicationInfo) {
			return this.accountProvisioning.apply(clusterUri, applicationInfo);
		}

	}

}
