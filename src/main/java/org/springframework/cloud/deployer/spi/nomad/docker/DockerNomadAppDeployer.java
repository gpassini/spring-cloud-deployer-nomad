package org.springframework.cloud.deployer.spi.nomad.docker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hashicorp.nomad.apimodel.*;
import com.hashicorp.nomad.javasdk.EvaluationResponse;
import com.hashicorp.nomad.javasdk.NomadApiClient;
import com.hashicorp.nomad.javasdk.NomadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppInstanceStatus;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.core.RuntimeEnvironmentInfo;
import org.springframework.cloud.deployer.spi.nomad.NomadDeployerProperties;
import org.springframework.cloud.deployer.spi.nomad.NomadSupport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Collections.singletonList;

/**
 * Deployer responsible for deploying
 * {@link org.springframework.cloud.deployer.resource.docker.DockerResource} based
 * applications using the Nomad
 * <a href="https://www.nomadproject.io/docs/drivers/docker.html">Docker</a> driver.
 *
 * @author Donovan Muller
 */
public class DockerNomadAppDeployer extends AbstractDockerNomadDeployer
	implements AppDeployer, NomadSupport {

	private static final Logger logger = LoggerFactory
		.getLogger(DockerNomadAppDeployer.class);

	private final NomadApiClient client;

	private final NomadDeployerProperties deployerProperties;

	public DockerNomadAppDeployer(NomadApiClient client,
								  NomadDeployerProperties deployerProperties) {
		super(client, deployerProperties);

		this.client = client;
		this.deployerProperties = deployerProperties;
	}

	@Override
	public String deploy(AppDeploymentRequest request) {
		String deploymentId = createDeploymentId(request);

		AppStatus status = status(deploymentId);
		if (!status.getState().equals(DeploymentState.unknown)) {
			throw new IllegalStateException(
				String.format("App '%s' is already deployed", deploymentId));
		}

		Job jobSpec = buildJobSpec(deploymentId, deployerProperties, request);
		jobSpec.setTaskGroups(buildTaskGroups(deploymentId, request, deployerProperties));

		EvaluationResponse jobEvalResult;
		try {
			jobEvalResult = client.getJobsApi().register(jobSpec);
		} catch (IOException | NomadException e) {
			logger.error(e.getMessage(), e);
			throw new RuntimeException(e);
		}
		logger.info("Deployed app '{}': {}", deploymentId, jobEvalResult);

		return deploymentId;
	}

	@Override
	public void undeploy(String deploymentId) {
		logger.info("Undeploying job '{}'", deploymentId);

		AppStatus status = status(deploymentId);
		if (status.getState().equals(DeploymentState.unknown)) {
			throw new IllegalStateException(
				String.format("App '%s' is not deployed", deploymentId));
		}

		try {
			client.getJobsApi().deregister(deploymentId);
		} catch (IOException | NomadException e) {
			logger.error(e.getMessage(), e);
			throw new RuntimeException(e);
		}
	}

	@Override
	public AppStatus status(String deploymentId) {
		Job job;
		try {
			job = getJobByName(deploymentId);
		} catch (NomadException | IOException e) {
			logger.error(e.getMessage(), e);
			throw new RuntimeException(e);
		}
		if (job == null) {
			return AppStatus.of(deploymentId).build();
		}

		AppStatus appStatus;
		if (!job.getStatus().equals("dead")) {
			try {
				List<AllocationListStub> allocations = getAllocationEvaluation(client, job);
				appStatus = buildAppStatus(deploymentId, allocations);
			} catch (NomadException | IOException e) {
				logger.error(e.getMessage(), e);
				throw new RuntimeException(e);
			}
		} else {
			appStatus = AppStatus.of(deploymentId).with(new AppInstanceStatus() {
				@Override
				public String getId() {
					return deploymentId;
				}

				@Override
				public DeploymentState getState() {
					return DeploymentState.failed;
				}

				@Override
				public Map<String, String> getAttributes() {
					return null;
				}
			}).build();
		}

		return appStatus;
	}

	@Override
	public RuntimeEnvironmentInfo environmentInfo() {
		try {
			return createRuntimeEnvironmentInfo(AppDeployer.class, this.getClass());
		} catch (NomadException | IOException e) {
			logger.error(e.getMessage(), e);
			throw new RuntimeException(e);
		}
	}

	@Override
	protected Task buildTask(AppDeploymentRequest request, String deploymentId) {
		Task taskBuilder = new Task();
		taskBuilder.setName(deploymentId);
		taskBuilder.setDriver("docker");
		Map<String, Object> configBuilder = new HashMap<>();
		try {
			configBuilder.put("image", request.getResource().getURI().getSchemeSpecificPart());
		} catch (IOException e) {
			throw new IllegalArgumentException(
				"Unable to get URI for " + request.getResource(), e);
		}

		NetworkResource network = new NetworkResource();
		network.setMBits(deployerProperties.getResources().getNetworkMBits());
		List<Port> dynamicPorts = new ArrayList<>();
		dynamicPorts.add(new Port().setValue(configureExternalPort(request)));
		network.setDynamicPorts(dynamicPorts);

		taskBuilder.setResources(new Resources()
			.setCpu(getCpuResource(deployerProperties, request))
			.setMemoryMb(getMemoryResource(deployerProperties, request))
			.setNetworks(singletonList(network)));

		HashMap<String, String> env = new HashMap<>();
		env.put("SPRING_CLOUD_APPLICATION_GUID", "${NOMAD_ALLOC_ID}");
		env.putAll(arrayToMap(deployerProperties.getEnvironmentVariables()));

		// Deprecated: https://www.nomadproject.io/docs/drivers/docker#deprecated-port_map-syntax
//		Map<String, Integer> portMap = new HashMap<>();
//		portMap.put("http", configureExternalPort(request));
//		configBuilder.portMap(Stream.of(portMap).collect(toList()));
		configBuilder.put("volumes", createVolumes(deployerProperties, request));

		// See
		// https://github.com/spring-cloud/spring-cloud-deployer-kubernetes/blob/master/src/main/java/org/springframework/cloud/deployer/spi/kubernetes/DefaultContainerFactory.java#L91
		EntryPointStyle entryPointStyle = determineEntryPointStyle(deployerProperties,
			request);
		switch (entryPointStyle) {
			case exec:
				configBuilder.put("args", createCommandLineArguments(request));
				break;
			case boot:
				if (env.containsKey("SPRING_APPLICATION_JSON")) {
					throw new IllegalStateException(
						"You can't use boot entry point style and also set SPRING_APPLICATION_JSON for the app");
				}
				try {
					env.put("SPRING_APPLICATION_JSON", new ObjectMapper()
						.writeValueAsString(request.getDefinition().getProperties()));
				} catch (JsonProcessingException e) {
					throw new IllegalStateException(
						"Unable to create SPRING_APPLICATION_JSON", e);
				}
				break;
			case shell:
				for (String key : request.getDefinition().getProperties().keySet()) {
					String envVar = key.replace('.', '_').toUpperCase();
					env.put(envVar, request.getDefinition().getProperties().get(key));
				}
				break;
		}

		taskBuilder.setConfig(configBuilder);
		taskBuilder.setEnv(env);

		taskBuilder.setLogConfig(new LogConfig()
			.setMaxFiles(deployerProperties.getLoggingMaxFiles())
			.setMaxFileSizeMb(deployerProperties.getLoggingMaxFileSize()));

		return taskBuilder;
	}

}
