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
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.core.RuntimeEnvironmentInfo;
import org.springframework.cloud.deployer.spi.nomad.NomadDeployerProperties;
import org.springframework.cloud.deployer.spi.task.LaunchState;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.cloud.deployer.spi.task.TaskStatus;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Deployer responsible for deploying
 * {@link org.springframework.cloud.deployer.resource.docker.DockerResource} based Task
 * using the Nomad
 * <a href="https://www.nomadproject.io/docs/drivers/docker.html">Docker</a> driver.
 *
 * @author Donovan Muller
 */
public class DockerNomadTaskLauncher extends AbstractDockerNomadDeployer
	implements TaskLauncher {

	protected static final Logger logger = LoggerFactory.getLogger(DockerNomadTaskLauncher.class);

	private final NomadApiClient client;

	private final NomadDeployerProperties deployerProperties;

	public DockerNomadTaskLauncher(NomadApiClient client,
								   NomadDeployerProperties deployerProperties) {
		super(client, deployerProperties);

		this.client = client;
		this.deployerProperties = deployerProperties;
	}

	@Override
	public String launch(AppDeploymentRequest request) {
		String taskId = createTaskId(request);

		Job jobSpec = buildBatchJobSpec(taskId, deployerProperties, request);
		jobSpec.setTaskGroups(buildTaskGroups(taskId, request, deployerProperties));

		try {
			EvaluationResponse jobEvalResult = client.getJobsApi().register(jobSpec);
			logger.info("Launched task '{}': {}", taskId, jobEvalResult);
		} catch (IOException | NomadException e) {
			logger.error(e.getMessage(), e);
			throw new RuntimeException(e);
		}

		return taskId;
	}

	@Override
	public void cancel(String taskId) {
		logger.info("Cancelling task '{}'", taskId);
		try {
			Job job = getJobByName(taskId);
			if (job != null) {
				client.getJobsApi().deregister(job.getId());
			}
		} catch (NomadException | IOException e) {
			logger.error(e.getMessage(), e);
			throw new RuntimeException(e);
		}
	}

	@Override
	public TaskStatus status(String taskId) {
		try {
			Job job = getJobByName(taskId);
			if (job == null) {
				return new TaskStatus(taskId, LaunchState.unknown, new HashMap<>());
			}

			List<AllocationListStub> allocations = getAllocationEvaluation(client, job);
			return buildTaskStatus(taskId, allocations.stream().findFirst().orElse(null));
		} catch (NomadException | IOException e) {
			logger.error(e.getMessage(), e);
			throw new RuntimeException(e);
		}
	}

	@Override
	public void cleanup(String taskId) {
		cancel(taskId);
	}

	@Override
	public void destroy(String taskId) {
		cancel(taskId);
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

	/**
	 * Multiple instances of tasks are not currently supported.
	 */
	@Override
	protected Integer getAppCount(AppDeploymentRequest request) {
		return 1;
	}

	@Override
	protected TaskGroup buildTaskGroup(String appId, AppDeploymentRequest request,
									   NomadDeployerProperties deployerProperties, int count) {
		TaskGroup taskGroup = super.buildTaskGroup(appId, request, deployerProperties,
			count);
		RestartPolicy restartPolicy = taskGroup.getRestartPolicy();
		restartPolicy.setMode("fail");
		restartPolicy.setAttempts(1);

		return taskGroup;
	}

	@Override
	protected Task buildTask(AppDeploymentRequest request, String appId) {
		Task taskBuilder = new Task();
		taskBuilder.setName(appId);
		taskBuilder.setDriver("docker");
		Map<String, Object> configBuilder = new HashMap<>();
		try {
			configBuilder.put("image", request.getResource().getURI().getSchemeSpecificPart());
		} catch (IOException e) {
			throw new IllegalArgumentException(
				"Unable to get URI for " + request.getResource(), e);
		}

		taskBuilder.setResources(new Resources()
			.setCpu(getCpuResource(deployerProperties, request))
			.setMemoryMb(getMemoryResource(deployerProperties, request)));

		HashMap<String, String> env = new HashMap<>();
		env.putAll(getAppEnvironmentVariables(request));
		env.putAll(arrayToMap(deployerProperties.getEnvironmentVariables()));

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

	protected List<AllocationListStub> getAllocationEvaluation(NomadApiClient client,
															   JobSummary jobSummary) throws NomadException, IOException {
		return client.getJobsApi().allocations(jobSummary.getJobId()).getValue();
	}

	protected TaskStatus buildTaskStatus(String id, AllocationListStub allocation) {
		if (allocation == null) {
			return new TaskStatus(id, LaunchState.unknown, new HashMap<>());
		}
		switch (allocation.getClientStatus()) {
			case "pending":
				return new TaskStatus(id, LaunchState.launching, new HashMap<>());

			case "running":
				return new TaskStatus(id, LaunchState.running, new HashMap<>());

			case "failed":
				return new TaskStatus(id, LaunchState.failed, new HashMap<>());

			case "lost":
				return new TaskStatus(id, LaunchState.failed, new HashMap<>());

			case "complete":
				return new TaskStatus(id, LaunchState.complete, new HashMap<>());

			case "dead":
				return new TaskStatus(id, LaunchState.complete, new HashMap<>());

			default:
				return new TaskStatus(id, LaunchState.unknown, new HashMap<>());
		}
	}

	private String createTaskId(AppDeploymentRequest request) {
		String groupId = request.getDeploymentProperties()
			.get(AppDeployer.GROUP_PROPERTY_KEY);
		String taskId;
		if (groupId == null) {
			taskId = String.format("%s", request.getDefinition().getName());
		} else {
			taskId = String.format("%s-%s", groupId, request.getDefinition().getName());
		}
		return String.format("%s-%d", taskId.replace('.', '-'),
			System.currentTimeMillis());
	}

	protected List<String> createCommandLineArguments(AppDeploymentRequest request) {
		List<String> commandlineArguments = new LinkedList<>();
		// add properties from deployment request
		Map<String, String> args = request.getDefinition().getProperties();
		for (Map.Entry<String, String> entry : args.entrySet()) {
			commandlineArguments
				.add(String.format("--%s=%s", entry.getKey(), entry.getValue()));
		}
		// add provided command line args
		commandlineArguments.addAll(request.getCommandlineArguments());

		logger.debug("Using command args: " + commandlineArguments);
		return commandlineArguments;
	}

}
