package org.springframework.cloud.deployer.spi.nomad.maven;

import com.hashicorp.nomad.apimodel.LogConfig;
import com.hashicorp.nomad.apimodel.Resources;
import com.hashicorp.nomad.apimodel.Task;
import com.hashicorp.nomad.apimodel.TaskArtifact;
import com.hashicorp.nomad.javasdk.NomadApiClient;
import com.hashicorp.nomad.javasdk.NomadException;
import org.springframework.cloud.deployer.resource.maven.MavenResource;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.core.RuntimeEnvironmentInfo;
import org.springframework.cloud.deployer.spi.nomad.NomadDeployerProperties;
import org.springframework.cloud.deployer.spi.nomad.NomadDeploymentPropertyKeys;
import org.springframework.cloud.deployer.spi.nomad.docker.DockerNomadTaskLauncher;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static java.util.Collections.singletonList;

/**
 * Deployer responsible for deploying
 * {@link org.springframework.cloud.deployer.resource.maven.MavenResource} based Task
 * using the Nomad <a href="https://www.nomadproject.io/docs/drivers/java.html">Java</a>
 * driver.
 *
 * @author Donovan Muller
 */
public class MavenNomadTaskLauncher extends DockerNomadTaskLauncher
	implements TaskLauncher, MavenSupport {

	private final NomadDeployerProperties deployerProperties;

	public MavenNomadTaskLauncher(NomadApiClient nomadClient,
								  NomadDeployerProperties deployerProperties) {
		super(nomadClient, deployerProperties);

		this.deployerProperties = deployerProperties;
	}

	@Override
	protected Task buildTask(AppDeploymentRequest request, String taskId) {
		Task taskBuilder = new Task();
		taskBuilder.setName(taskId);
		taskBuilder.setDriver("java");

		Map<String, Object> configBuilder = new HashMap<>();
		MavenResource resource = (MavenResource) request.getResource();

		configBuilder.put("jarPath", String.format("%s/%s",
			deployerProperties.getArtifactDestination(), resource.getFilename()));
		configBuilder.put("jvmOptions", new ArrayList<>(
			StringUtils.commaDelimitedListToSet(request.getDeploymentProperties()
				.getOrDefault(NomadDeploymentPropertyKeys.NOMAD_JAVA_OPTS,
					deployerProperties.getJavaOpts()))));

		taskBuilder.setResources(new Resources()
			.setCpu(getCpuResource(deployerProperties, request))
			.setMemoryMb(getMemoryResource(deployerProperties, request)));

		Map<String, String> env = new HashMap<>();
		env.putAll(getAppEnvironmentVariables(request));
		env.putAll(arrayToMap(deployerProperties.getEnvironmentVariables()));

		taskBuilder.setEnv(env);

		configBuilder.put("args", createCommandLineArguments(request));
		taskBuilder.setConfig(configBuilder);

		// TODO support checksum:
		// https://github.com/donovanmuller/spring-cloud-deployer-nomad/issues/19
		// see:
		// https://www.nomadproject.io/docs/job-specification/artifact.html#download-and-verify-checksums
		taskBuilder.setArtifacts(singletonList(new TaskArtifact()
			.setGetterSource(toURIString((MavenResource) request.getResource(),
				deployerProperties))
			.setRelativeDest(deployerProperties.getArtifactDestination())));

		taskBuilder.setLogConfig(new LogConfig()
			.setMaxFiles(deployerProperties.getLoggingMaxFiles())
			.setMaxFileSizeMb(deployerProperties.getLoggingMaxFileSize()));

		return taskBuilder;
	}

	@Override
	public RuntimeEnvironmentInfo environmentInfo() {
		try {
			return createRuntimeEnvironmentInfo(TaskLauncher.class, this.getClass());
		} catch (NomadException | IOException e) {
			logger.error(e.getMessage(), e);
			throw new RuntimeException(e);
		}
	}

}
