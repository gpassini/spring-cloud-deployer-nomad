package org.springframework.cloud.deployer.spi.nomad.maven;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hashicorp.nomad.apimodel.*;
import com.hashicorp.nomad.javasdk.EvaluationResponse;
import com.hashicorp.nomad.javasdk.NomadApiClient;
import com.hashicorp.nomad.javasdk.NomadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.deployer.resource.maven.MavenResource;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.core.RuntimeEnvironmentInfo;
import org.springframework.cloud.deployer.spi.nomad.AbstractNomadDeployer;
import org.springframework.cloud.deployer.spi.nomad.NomadDeployerProperties;
import org.springframework.cloud.deployer.spi.nomad.NomadDeploymentPropertyKeys;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toMap;

/**
 * Deployer responsible for deploying
 * {@link org.springframework.cloud.deployer.resource.maven.MavenResource} based
 * applications using the Nomad
 * <a href="https://www.nomadproject.io/docs/drivers/java.html">Java</a> driver.
 *
 * @author Donovan Muller
 */
public class MavenNomadAppDeployer extends AbstractNomadDeployer
	implements AppDeployer, MavenSupport {

	private static final Logger logger = LoggerFactory
		.getLogger(MavenNomadAppDeployer.class);

	private final NomadApiClient client;

	private final NomadDeployerProperties deployerProperties;

	public MavenNomadAppDeployer(NomadApiClient client,
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

		try {
			EvaluationResponse jobEvalResult = client.getJobsApi().register(jobSpec);
			logger.info("Deployed app '{}': {}", deploymentId, jobEvalResult);
		} catch (IOException | NomadException e) {
			logger.error(e.getMessage(), e);
			throw new RuntimeException(e);
		}

		return deploymentId;
	}

	@Override
	public void undeploy(String deploymentId) {
		logger.info("Undeploying job '{}'", deploymentId);
		try {
			Job job = getJobByName(deploymentId);
			client.getJobsApi().deregister(job.getId());
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

		try {
			List<AllocationListStub> allocations = getAllocationEvaluation(client, job);
			return buildAppStatus(deploymentId, allocations);
		} catch (NomadException | IOException e) {
			logger.error(e.getMessage(), e);
			throw new RuntimeException(e);
		}
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
		taskBuilder.setDriver("java");

		Map<String, Object> configBuilder = new HashMap<>();
		MavenResource resource = (MavenResource) request.getResource();

		configBuilder.put("jarPath", String.format("%s/%s",
			deployerProperties.getArtifactDestination(), resource.getFilename()));
		configBuilder.put("jvmOptions", new ArrayList<>(
			StringUtils.commaDelimitedListToSet(request.getDeploymentProperties()
				.getOrDefault(NomadDeploymentPropertyKeys.NOMAD_JAVA_OPTS,
					deployerProperties.getJavaOpts()))));
		taskBuilder.setConfig(configBuilder);

		NetworkResource network = new NetworkResource();
		network.setMBits(deployerProperties.getResources().getNetworkMBits());
		List<Port> dynamicPorts = new ArrayList<>();
		dynamicPorts.add(new Port()
			.setValue(configureExternalPort(request)));
		network.setDynamicPorts(dynamicPorts);

		taskBuilder.setResources(new Resources()
			.setCpu(getCpuResource(deployerProperties, request))
			.setMemoryMb(getMemoryResource(deployerProperties, request))
			.setNetworks(singletonList(network)));

		Map<String, String> env = new HashMap<>();
		env.putAll(getAppEnvironmentVariables(request));
		env.putAll(arrayToMap(deployerProperties.getEnvironmentVariables()));
		// just in case the server.port is specified as a env. variable, should never
		// happen :)
		env.replaceAll((key, value) -> replaceServerPortWithPort(value));
		env.put("SPRING_APPLICATION_JSON", toSpringApplicationJson(request));

		taskBuilder.setEnv(env);

		// see
		// https://www.nomadproject.io/docs/job-specification/artifact.html#download-and-verify-checksums
		Map<String, String> options = new HashMap<>();
		options.put("checksum", String.format("md5:%s",
			new ResourceChecksum().generateMD5Checksum(resource)));
		taskBuilder.setArtifacts(singletonList(new TaskArtifact()
			.setGetterSource(toURIString((MavenResource) request.getResource(),
				deployerProperties))
			.setRelativeDest(deployerProperties.getArtifactDestination())
			.setGetterOptions(options)));
		taskBuilder.setLogConfig(new LogConfig()
			.setMaxFiles(deployerProperties.getLoggingMaxFiles())
			.setMaxFileSizeMb(deployerProperties.getLoggingMaxFileSize()));

		return taskBuilder;
	}

	protected String replaceServerPortWithPort(String potentialBootServerPort) {
		// handle both `SERVER_PORT` and `server.port` variations
		if (potentialBootServerPort.replaceAll("_", ".")
			.equalsIgnoreCase("server.port")) {
			return "${NOMAD_PORT_http}";
		}

		return potentialBootServerPort;
	}

	/**
	 * Coverts the results of {@link AbstractNomadDeployer#createCommandLineArguments} (a
	 * {@link List} of <code>--arg1=val1,--arg2=val2</code>) to SPRING_APPLICATION_JSON
	 * JSON.
	 */
	protected String toSpringApplicationJson(AppDeploymentRequest request) {
		try {
			return new ObjectMapper()
				.writeValueAsString(createCommandLineArguments(request).stream()
					.map(argument -> argument.replaceAll("-", ""))
					.collect(toMap(
						argument -> argument.substring(0,
							argument.indexOf("=")),
						argument -> argument
							.substring(argument.lastIndexOf("=") + 1))));
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Unable to create SPRING_APPLICATION_JSON",
				e);

		}
	}

}
