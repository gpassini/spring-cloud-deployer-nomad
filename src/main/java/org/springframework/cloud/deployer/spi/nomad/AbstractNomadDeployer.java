package org.springframework.cloud.deployer.spi.nomad;

import com.hashicorp.nomad.apimodel.*;
import com.hashicorp.nomad.javasdk.NomadApiClient;
import com.hashicorp.nomad.javasdk.NomadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.core.RuntimeEnvironmentInfo;
import org.springframework.cloud.deployer.spi.util.ByteSizeUtils;
import org.springframework.cloud.deployer.spi.util.RuntimeVersionUtils;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

/**
 * @author Donovan Muller
 */
public abstract class AbstractNomadDeployer implements NomadSupport {

	protected static final String SERVER_PORT_KEY = "server.port";

	protected static final String SPRING_DEPLOYMENT_KEY = "spring-deployment-id";

	protected static final String SPRING_GROUP_KEY = "spring-group-id";

	protected static final String SPRING_APP_KEY = "spring-app-id";

	private static final Logger logger = LoggerFactory
		.getLogger(AbstractNomadDeployer.class);

	private final NomadApiClient client;

	private final NomadDeployerProperties deployerProperties;

	protected AbstractNomadDeployer(NomadApiClient client,
									NomadDeployerProperties deployerProperties) {
		this.client = client;
		this.deployerProperties = deployerProperties;
	}

	/**
	 * Create the RuntimeEnvironmentInfo.
	 *
	 * @return the Kubernetes runtime environment info
	 */
	protected RuntimeEnvironmentInfo createRuntimeEnvironmentInfo(Class spiClass,
																  Class implementationClass) throws NomadException, IOException {
		Set<String> hostVersions = client.getAgentApi().members().getValue().getMembers().stream()
			.map(member -> member.getTags().get("build")).collect(Collectors.toSet());

		RuntimeEnvironmentInfo.Builder runtimeEnvironment = new RuntimeEnvironmentInfo.Builder()
			.spiClass(spiClass)
			.implementationName(implementationClass.getSimpleName())
			.implementationVersion(
				RuntimeVersionUtils.getVersion(implementationClass))
			.platformType("Hashicorp Nomad").platformApiVersion("v1")
			.platformClientVersion(deployerProperties.getRuntimePlatformVersion())
			.platformHostVersion(
				StringUtils.collectionToCommaDelimitedString(hostVersions));

		client.getAgentApi().members().getValue().getMembers().forEach(member -> runtimeEnvironment
			.addPlatformSpecificInfo(String.format("%s-build", member.getName()),
				member.getTags().get("build"))
			.addPlatformSpecificInfo(String.format("%s-region", member.getName()),
				member.getTags().get("region"))
			.addPlatformSpecificInfo(String.format("%s-datacenter", member.getName()),
				member.getTags().get("dc")));

		return runtimeEnvironment.build();
	}

	protected abstract Task buildTask(AppDeploymentRequest request, String deploymentId);

	protected String createDeploymentId(AppDeploymentRequest request) {
		String groupId = request.getDeploymentProperties()
			.get(AppDeployer.GROUP_PROPERTY_KEY);
		String deploymentId;
		if (groupId == null) {
			deploymentId = String.format("%s", request.getDefinition().getName());
		} else {
			deploymentId = String.format("%s-%s", groupId,
				request.getDefinition().getName());
		}
		return deploymentId.replace('.', '-');
	}

	protected Job buildJobSpec(String deploymentId,
							   NomadDeployerProperties deployerProperties, AppDeploymentRequest request) {
		return buildJobSpec(deploymentId, deployerProperties, request, JobTypes.SERVICE);
	}

	protected Job buildBatchJobSpec(String taskId,
									NomadDeployerProperties deployerProperties, AppDeploymentRequest request) {
		return buildJobSpec(taskId, deployerProperties, request, JobTypes.BATCH);
	}

	protected Job buildJobSpec(String deploymentId,
							   NomadDeployerProperties deployerProperties, AppDeploymentRequest request,
							   JobTypes jobType) {
		Job jobSpec = new Job();
		jobSpec.setId(deploymentId);
		jobSpec.setName(deploymentId);
		jobSpec.setRegion(deployerProperties.getRegion());
		jobSpec.setType(jobType.name().toLowerCase());
		jobSpec.setDatacenters(deployerProperties.getDatacenters());
		jobSpec.setPriority(Integer.valueOf(request.getDeploymentProperties()
			.getOrDefault(NomadDeploymentPropertyKeys.JOB_PRIORITY,
				deployerProperties.getPriority().toString())));
		jobSpec.setMeta(createMeta(request));

		List<Constraint> constraints = new ArrayList<>();
		// At a minimum a Java runtime must be available
//		constraints.add(new Constraint()
//			.setLTarget("${driver.java}")
//			.setOperand("=")
//			.setRTarget("1"));
		if (!StringUtils.isEmpty(deployerProperties.getMinimumJavaVersion())) {
			constraints.add(new Constraint()
				.setLTarget("${driver.java.version}")
				.setOperand(">=")
				.setLTarget(deployerProperties.getMinimumJavaVersion()));
		}
		jobSpec.setConstraints(constraints);

		return jobSpec;
	}

	protected List<TaskGroup> buildTaskGroups(String deploymentId,
											  AppDeploymentRequest request, NomadDeployerProperties deployerProperties) {
		TaskGroup taskGroup = buildTaskGroup(deploymentId, request, deployerProperties,
			getAppCount(request));
		taskGroup.setTasks(Stream.of(buildTask(request, deploymentId)).collect(toList()));

		return Stream.of(taskGroup).collect(toList());
	}

	/**
	 * The TaskGroup name uses the <code>deploymentId</code> (and not the
	 * AppDeployer.GROUP_PROPERTY_KEY deployment property) because we don't necessarily
	 * want apps in the same group to be scheduled on the same client. See
	 * https://www.nomadproject.io/docs/internals/architecture.html
	 */
	protected TaskGroup buildTaskGroup(String deploymentId, AppDeploymentRequest request,
									   NomadDeployerProperties deployerProperties, int count) {
		TaskGroup taskGroup = new TaskGroup();
		taskGroup.setName(deploymentId);
		taskGroup.setCount(count);

		taskGroup.setRestartPolicy(new RestartPolicy()
			.setDelay(milliToNanoseconds(deployerProperties.getRestartPolicyDelay()))
			.setInterval(milliToNanoseconds(deployerProperties.getRestartPolicyInterval()))
			.setAttempts(deployerProperties.getRestartPolicyAttempts())
			.setMode(deployerProperties.getRestartPolicyMode()));

		taskGroup.setEphemeralDisk(new EphemeralDisk()
			.setSticky(Boolean.parseBoolean(request.getDeploymentProperties().getOrDefault(
				NomadDeploymentPropertyKeys.NOMAD_EPHEMERAL_DISK_STICKY,
				deployerProperties.getEphemeralDisk().getSticky().toString())))
			.setMigrate(Boolean.parseBoolean(request.getDeploymentProperties().getOrDefault(
				NomadDeploymentPropertyKeys.NOMAD_EPHEMERAL_DISK_MIGRATE,
				deployerProperties.getEphemeralDisk().getMigrate().toString())))
			.setSizeMb(Integer.valueOf(request.getDeploymentProperties().getOrDefault(
				NomadDeploymentPropertyKeys.NOMAD_EPHEMERAL_DISK_SIZE,
				deployerProperties.getEphemeralDisk().getSize().toString()))));

		return taskGroup;
	}

	/**
	 * Get the allocations for the specified Job. Multiple allocations will be present for
	 * partitioned apps (<code>app.xxx.count > 1</code>).
	 */
	protected List<AllocationListStub> getAllocationEvaluation(NomadApiClient client,
															   Job jobSummary) throws NomadException, IOException {
		return client.getJobsApi().allocations(jobSummary.getId()).getValue();
	}

	/**
	 * Build the {@link AppStatus} based on a Job allocations.
	 */
	protected AppStatus buildAppStatus(String id, List<AllocationListStub> allocations) throws NomadException, IOException {
		AppStatus.Builder statusBuilder = AppStatus.of(id);
		for (AllocationListStub allocation : allocations) {
			statusBuilder.with(new NomadAppInstanceStatus(client.getAllocationsApi().info(allocation.getId()).getValue()));
		}
		return statusBuilder.build();
	}

	protected Job getJobByName(final String deploymentId) throws NomadException, IOException {
		return client.getJobsApi().info(deploymentId).getValue();
	}

	protected Integer getAppCount(AppDeploymentRequest request) {
		String countProperty = request.getDeploymentProperties()
			.get(AppDeployer.COUNT_PROPERTY_KEY);
		return (countProperty != null) ? Integer.parseInt(countProperty) : 1;
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
		if (!commandlineArguments.contains("server.port")) {
			commandlineArguments.add("--server.port=${NOMAD_PORT_http}");
		}

		logger.debug("Using command args: " + commandlineArguments);
		return commandlineArguments;
	}

	protected Map<String, String> getAppEnvironmentVariables(
		AppDeploymentRequest request) {
		Map<String, String> appEnvVarMap = new HashMap<>();
		String appEnvVar = request.getDeploymentProperties()
			.get(NomadDeploymentPropertyKeys.NOMAD_ENVIRONMENT_VARIABLES);
		if (appEnvVar != null) {
			String[] appEnvVars = appEnvVar.split(",");
			for (String envVar : appEnvVars) {
				logger.trace("Adding environment variable from AppDeploymentRequest: "
					+ envVar);
				String[] strings = envVar.split("=", 2);
				Assert.isTrue(strings.length == 2,
					"Invalid environment variable declared: " + envVar);
				appEnvVarMap.put(strings[0], strings[1]);
			}
		}
		return appEnvVarMap;
	}

	/**
	 * Creates a {@link List} of labels for a given <code>deploymentId</code>.
	 */
	protected List<String> createTags(String deploymentId, AppDeploymentRequest request) {
		return createTags(deploymentId, request, null);
	}

	/**
	 * Creates a {@link List} of labels for a given <code>deploymentId</code> and
	 * <code>instanceIndex</code>.
	 */
	protected List<String> createTags(String deploymentId, AppDeploymentRequest request,
									  Integer instanceIndex) {
		List<String> tags = new ArrayList<>();
		tags.add(String.format("%s=%s", SPRING_APP_KEY, deploymentId));
		String groupId = request.getDeploymentProperties()
			.get(AppDeployer.GROUP_PROPERTY_KEY);
		if (groupId != null) {
			tags.add(String.format("%s=%s", SPRING_GROUP_KEY, groupId));
		}
		String appInstanceId = instanceIndex == null ? deploymentId
			: deploymentId + "-" + instanceIndex;
		tags.add(String.format("%s=%s", SPRING_DEPLOYMENT_KEY, appInstanceId));

		return tags;
	}

	/**
	 * Decide if the app should include tags that would allow Fabio to create the routing
	 * entries when registered with Consul. The following deployment/deployer properties
	 * enable this:
	 *
	 * <ul>
	 * <li>spring.cloud.deployer.nomad.fabio.expose</li>
	 * </ul>
	 */
	protected boolean exposeFabioRoute(AppDeploymentRequest request) {
		boolean expose = false;
		String exposeProperty = request.getDeploymentProperties()
			.get(NomadDeploymentPropertyKeys.NOMAD_EXPOSE_VIA_FABIO);
		if (StringUtils.hasText(exposeProperty)) {
			expose = deployerProperties.isExposeViaFabio();
		} else {
			if (Boolean.parseBoolean(exposeProperty.toLowerCase())) {
				expose = true;
			}
		}

		return expose;
	}

	/**
	 * See {@link NomadDeploymentPropertyKeys#NOMAD_FABIO_ROUTE_HOSTNAME}
	 */
	protected String buildFabioUrlPrefix(AppDeploymentRequest request,
										 String deploymentId) {
		return String.format("urlprefix-%s/",
			request.getDeploymentProperties().getOrDefault(
				NomadDeploymentPropertyKeys.NOMAD_FABIO_ROUTE_HOSTNAME,
				deploymentId));
	}

	protected int configureExternalPort(AppDeploymentRequest request) {
		int externalPort = 8080;
		Map<String, String> parameters = request.getDefinition().getProperties();
		if (parameters.containsKey(SERVER_PORT_KEY)) {
			externalPort = Integer.parseInt(parameters.get(SERVER_PORT_KEY));
		}

		return externalPort;
	}

	/**
	 * Get the CPU resource value from the following sources (in order of precedence):
	 *
	 * <ol>
	 * <li>{@link AppDeployer#CPU_PROPERTY_KEY} deployment property</li>
	 * <li>{@link NomadDeploymentPropertyKeys#NOMAD_RESOURCES_CPU} deployment
	 * property</li>
	 * <li>{@link NomadDeployerProperties.Resources#getCpu()} deployer property</li>
	 * </ol>
	 */
	protected Integer getCpuResource(NomadDeployerProperties properties,
									 AppDeploymentRequest request) {
		return Integer.valueOf(request.getDeploymentProperties().getOrDefault(
			AppDeployer.CPU_PROPERTY_KEY,
			request.getDeploymentProperties().getOrDefault(
				NomadDeploymentPropertyKeys.NOMAD_RESOURCES_CPU,
				properties.getResources().getCpu())));
	}

	/**
	 * Get the memory resource value from the following sources (in order of precedence):
	 *
	 * <ol>
	 * <li>{@link AppDeployer#MEMORY_PROPERTY_KEY} deployment property</li>
	 * <li>{@link NomadDeploymentPropertyKeys#NOMAD_RESOURCES_MEMORY} deployment
	 * property</li>
	 * <li>{@link NomadDeployerProperties.Resources#getMemory()} deployer property</li>
	 * </ol>
	 */
	protected Integer getMemoryResource(NomadDeployerProperties properties,
										AppDeploymentRequest request) {
		Integer memory = getCommonDeployerMemory(request);
		return memory != null ? memory
			: Integer.valueOf(request.getDeploymentProperties().getOrDefault(
			NomadDeploymentPropertyKeys.NOMAD_RESOURCES_MEMORY,
			properties.getResources().getMemory()));
	}

	/**
	 * See
	 * org.springframework.cloud.deployer.spi.kubernetes.AbstractKubernetesDeployer#getCommonDeployerMemory
	 */
	private Integer getCommonDeployerMemory(AppDeploymentRequest request) {
		String mem = request.getDeploymentProperties()
			.get(AppDeployer.MEMORY_PROPERTY_KEY);
		if (mem == null) {
			return null;
		}
		return Math.toIntExact(ByteSizeUtils.parseToMebibytes(mem));
	}

	private Map<String, String> createMeta(AppDeploymentRequest request) {
		Map<String, String> metaMap = new HashMap<>();
		String metaValue = request.getDeploymentProperties()
			.get(NomadDeploymentPropertyKeys.NOMAD_META);
		if (metaValue != null) {
			String[] metaKeyValue = metaValue.split(",");
			for (String metaKeyValues : metaKeyValue) {
				String[] strings = metaKeyValues.split("=", 2);
				Assert.isTrue(strings.length == 2,
					"Invalid meta value declared: " + metaKeyValues);
				metaMap.put(strings[0], strings[1]);
			}
		}
		return metaMap;
	}

}
