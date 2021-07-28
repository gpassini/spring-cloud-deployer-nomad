package org.springframework.cloud.deployer.spi.nomad.docker;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.deployer.resource.docker.DockerResource;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.nomad.NomadAutoConfiguration;
import org.springframework.cloud.deployer.spi.test.AbstractAppDeployerIntegrationJUnit5Tests;
import org.springframework.cloud.deployer.spi.test.Timeout;
import org.springframework.core.io.Resource;

/**
 * Integration tests for
 * {@link org.springframework.cloud.deployer.spi.nomad.docker.DockerNomadAppDeployer}.
 *
 * @author Donovan Muller
 */
@SpringBootTest(classes = NomadAutoConfiguration.class,
	properties = {"spring.cloud.consul.enabled=false",
		"spring.cloud.deployer.nomad.restartPolicyAttempts=1"})
public class DockerNomadAppDeployerIntegrationTests
	extends AbstractAppDeployerIntegrationJUnit5Tests {

	@Autowired
	private AppDeployer appDeployer;

	@Test
	@Disabled("Skipping, test is flaky...")
	public void testDeployingStateCalculationAndCancel() {
	}

	@Test
	@Disabled("See https://github.com/donovanmuller/spring-cloud-deployer-nomad/issues/18")
	public void testCommandLineArgumentsPassing() {
	}

	@Test
	@Disabled("See https://github.com/donovanmuller/spring-cloud-deployer-nomad/issues/18")
	public void testApplicationPropertiesPassing() {
	}

	@Override
	protected AppDeployer provideAppDeployer() {
		return appDeployer;
	}

	@Override
	protected String randomName() {
		return "app-" + System.currentTimeMillis();
	}

	@Override
	protected Timeout deploymentTimeout() {
		return new Timeout(36, 5000);
	}

	@Override
	protected Resource testApplication() {
		return new DockerResource(
			"springcloud/spring-cloud-deployer-spi-test-app:latest");
	}

}
