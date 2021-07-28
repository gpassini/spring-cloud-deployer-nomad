package org.springframework.cloud.deployer.spi.nomad;

import com.hashicorp.nomad.apimodel.Job;
import com.hashicorp.nomad.apimodel.Task;
import org.junit.Before;
import org.junit.Test;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.core.io.Resource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class AbstractNomadDeployerTest {

	private AbstractNomadDeployer deployer;

	@Before
	public void setup() {
		deployer = new AbstractNomadDeployer(null, new NomadDeployerProperties()) {

			@Override
			protected Integer getAppCount(final AppDeploymentRequest request) {
				return null;
			}

			@Override
			protected Task buildTask(final AppDeploymentRequest request,
									 final String deploymentId) {
				return null;
			}
		};
	}

	@Test
	public void testBuildJobSpec() {
		Map<String, String> deploymentProperties = new HashMap<>();
		deploymentProperties.put("spring.cloud.deployer.nomad.meta", "test=value");
		AppDeploymentRequest request = new AppDeploymentRequest(
			new AppDefinition("test-app", null), mock(Resource.class),
			deploymentProperties);

		Job jobSpec = deployer.buildJobSpec("1", new NomadDeployerProperties(),
			request, JobTypes.SERVICE);

		assertThat(jobSpec.getMeta()).containsEntry("test", "value");
	}

}
