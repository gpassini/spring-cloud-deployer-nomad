package org.springframework.cloud.deployer.spi.nomad;

import com.hashicorp.nomad.javasdk.NomadApiClient;
import org.apache.http.HttpHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.deployer.resource.maven.MavenProperties;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.nomad.docker.DockerNomadTaskLauncher;
import org.springframework.cloud.deployer.spi.nomad.docker.IndexingDockerNomadAppDeployer;
import org.springframework.cloud.deployer.spi.nomad.maven.IndexingMavenNomadAppDeployer;
import org.springframework.cloud.deployer.spi.nomad.maven.MavenNomadTaskLauncher;
import org.springframework.cloud.deployer.spi.nomad.maven.MavenResourceController;
import org.springframework.cloud.deployer.spi.nomad.maven.MavenResourceResolver;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * @author Donovan Muller
 */
@Configuration
@EnableConfigurationProperties(NomadDeployerProperties.class)
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
public class NomadAutoConfiguration {

	private NomadDeployerProperties deployerProperties;

	public NomadAutoConfiguration(NomadDeployerProperties deployerProperties) {
		this.deployerProperties = deployerProperties;
	}

	@Bean
	public NomadApiClient nomadClient() {
		return new NomadApiClient(new HttpHost(
			deployerProperties.getNomadHost(),
			deployerProperties.getNomadPort()));
	}

	@Bean
	public AppDeployer appDeployer(NomadApiClient nomadClient) {
		return new ResourceAwareNomadAppDeployer(
			new IndexingDockerNomadAppDeployer(nomadClient, deployerProperties),
			new IndexingMavenNomadAppDeployer(nomadClient, deployerProperties));
	}

	@Bean
	public TaskLauncher taskLauncher(NomadApiClient nomadClient) {
		return new ResourceAwareNomadTaskLauncher(
			new DockerNomadTaskLauncher(nomadClient, deployerProperties),
			new MavenNomadTaskLauncher(nomadClient, deployerProperties));
	}

	@Bean
	public MavenResourceResolver mavenResourceResolver(MavenProperties mavenProperties) {
		return new MavenResourceResolver(mavenProperties);
	}

	@Bean
	@ConditionalOnMissingBean
	public MavenResourceController mavenResourceController(
		MavenResourceResolver mavenResourceResolver) {
		return new MavenResourceController(mavenResourceResolver);
	}

	@Configuration
	@PropertySource("classpath:runtime.properties")
	public class RuntimeConfiguration {

		public RuntimeConfiguration(Environment env,
									NomadDeployerProperties deployerProperties) {
			deployerProperties
				.setRuntimePlatformVersion(env.getProperty("platformClientVersion"));
		}

	}

	@Configuration
	public static class NomadDeployerConfiguration {

		private static final Logger logger = LoggerFactory
			.getLogger(NomadDeployerConfiguration.class);

		private NomadDeployerProperties deployerProperties;

		public NomadDeployerConfiguration(NomadDeployerProperties deployerProperties) {
			this.deployerProperties = deployerProperties;
		}

	}

}
