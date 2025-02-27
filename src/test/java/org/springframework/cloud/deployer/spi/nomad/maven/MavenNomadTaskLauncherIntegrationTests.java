package org.springframework.cloud.deployer.spi.nomad.maven;

import org.junit.runner.RunWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.deployer.resource.maven.MavenResource;
import org.springframework.cloud.deployer.spi.nomad.NomadAutoConfiguration;
import org.springframework.cloud.deployer.spi.nomad.docker.DockerNomadTaskLauncherIntegrationTests;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.IOException;
import java.util.Properties;

/**
 * Integration tests for
 * {@link org.springframework.cloud.deployer.spi.nomad.maven.MavenNomadTaskLauncher}.
 *
 * @author Donovan Muller
 */
@RunWith(SpringRunner.class)
@SpringBootTest(
		classes = { MavenNomadTaskLauncherIntegrationTests.TestApplication.class },
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		value = {
				"maven.remoteRepositories.spring.url=https://repo.spring.io/libs-snapshot",
				"spring.cloud.consul.enabled=false",
				"spring.cloud.deployer.nomad.restartPolicyAttempts=1" })
public class MavenNomadTaskLauncherIntegrationTests
		extends DockerNomadTaskLauncherIntegrationTests {

	@Override
	protected Resource testApplication() {
		Properties properties = new Properties();
		try {
			properties.load(new ClassPathResource("integration-test-app.properties")
					.getInputStream());
		}
		catch (IOException e) {
			throw new RuntimeException(
					"Failed to determine which version of integration-test-app to use",
					e);
		}
		return new MavenResource.Builder(mavenProperties)
				.groupId("org.springframework.cloud")
				.artifactId("spring-cloud-deployer-spi-test-app")
				.version(properties.getProperty("version")).classifier("exec")
				.extension("jar").build();
	}

	/**
	 * See
	 * org.springframework.cloud.deployer.spi.nomad.maven.MavenNomadAppDeployerIntegrationTests.{@link org.springframework.cloud.deployer.spi.nomad.maven.MavenNomadAppDeployerIntegrationTests.TestApplication}
	 */
	@SpringBootApplication
	@Import(NomadAutoConfiguration.class)
	public static class TestApplication {

		public static void main(String[] args) {
			SpringApplication.run(TestApplication.class, args);
		}

	}

}
