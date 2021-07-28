package org.springframework.cloud.deployer.spi.nomad.docker;

import com.hashicorp.nomad.apimodel.Task;
import com.hashicorp.nomad.apimodel.TaskGroup;
import com.hashicorp.nomad.javasdk.NomadApiClient;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.nomad.NomadDeployerProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

/**
 * Deployer responsible for deploying
 * {@link org.springframework.cloud.deployer.resource.docker.DockerResource} based
 * applications using the Nomad
 * <a href="https://www.nomadproject.io/docs/drivers/docker.html">Docker</a> driver.
 * <p>
 * This implementation adds support for partitioned/indexed applications. Each indexed
 * application is configured as a separate TaskGroup, allowing each application instance
 * to be scheduled on a different client. If implemented as separate tasks, the
 * application instance would be scheduled on the same client, which might not be ideal.
 * See https://www.nomadproject.io/docs/internals/architecture.html
 *
 * @author Donovan Muller
 */
public class IndexingDockerNomadAppDeployer extends DockerNomadAppDeployer {

	public IndexingDockerNomadAppDeployer(NomadApiClient client,
										  NomadDeployerProperties deployerProperties) {
		super(client, deployerProperties);
	}

	@Override
	protected List<TaskGroup> buildTaskGroups(String appId, AppDeploymentRequest request,
											  NomadDeployerProperties deployerProperties) {

		List<TaskGroup> taskGroups = new ArrayList<>();
		if (isIndexed(request)) {
			for (Integer index = 0; index < getAppCount(request); index++) {
				String indexedId = appId + "-" + index;
				TaskGroup taskGroup = buildTaskGroup(indexedId, request,
					deployerProperties, 1);
				Task task = buildTask(request, indexedId);

				Map<String, String> environmentVariables = task.getEnv();
				environmentVariables.putIfAbsent(AppDeployer.INSTANCE_INDEX_PROPERTY_KEY,
					index.toString());
				environmentVariables.putIfAbsent("SPRING_APPLICATION_INDEX",
					index.toString());
				environmentVariables.putIfAbsent("SPRING_CLOUD_APPLICATION_GROUP", request
					.getDeploymentProperties().get(AppDeployer.GROUP_PROPERTY_KEY));

				taskGroup.setTasks(Stream.of(task).collect(toList()));
				taskGroups.add(taskGroup);
			}
		} else {
			taskGroups.addAll(super.buildTaskGroups(appId, request, deployerProperties));
		}

		return taskGroups;
	}

	protected boolean isIndexed(AppDeploymentRequest request) {
		String indexedProperty = request.getDeploymentProperties()
			.get(INDEXED_PROPERTY_KEY);
		return (indexedProperty != null) ? Boolean.valueOf(indexedProperty) : false;
	}

}
