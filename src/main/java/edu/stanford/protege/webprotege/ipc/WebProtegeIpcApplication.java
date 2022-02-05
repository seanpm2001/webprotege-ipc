package edu.stanford.protege.webprotege.ipc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import edu.stanford.protege.webprotege.authorization.GetAuthorizationStatusRequest;
import edu.stanford.protege.webprotege.authorization.GetAuthorizationStatusResponse;
import edu.stanford.protege.webprotege.common.Request;
import edu.stanford.protege.webprotege.common.Response;
import edu.stanford.protege.webprotege.common.WebProtegeCommonConfiguration;
import edu.stanford.protege.webprotege.ipc.kafka.KafkaCommandExecutor;
import edu.stanford.protege.webprotege.ipc.kafka.KafkaEventDispatcher;
import edu.stanford.protege.webprotege.ipc.kafka.ReplyingKafkaTemplateFactory;
import edu.stanford.protege.webprotege.ipc.kafka.ReplyingKafkaTemplateFactoryImpl;
import edu.stanford.protege.webprotege.ipc.pulsar.PulsarCommandHandlerWrapper;
import edu.stanford.protege.webprotege.ipc.pulsar.PulsarCommandHandlerWrapperFactory;
import edu.stanford.protege.webprotege.ipc.pulsar.PulsarEventDispatcher;
import edu.stanford.protege.webprotege.ipc.pulsar.PulsarProducersManager;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminBuilder;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.admin.internal.PulsarAdminBuilderImpl;
import org.apache.pulsar.client.admin.internal.PulsarAdminImpl;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.impl.conf.ClientConfigurationData;
import org.apache.pulsar.common.policies.data.TenantInfoImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Scope;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.io.UncheckedIOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
@Import(WebProtegeCommonConfiguration.class)
@EnableCaching
public class WebProtegeIpcApplication {

	@Autowired
	private PulsarAdmin pulsarAdmin;

	public static void main(String[] args) {
		SpringApplication.run(WebProtegeIpcApplication.class, args);
	}

	@Bean
	MessageChannelMapper messageChannelMapper(@Value("${spring.application.name}") String serviceName) {
		return new MessageChannelMapper(serviceName);
	}

	@Bean
	ReplyErrorChecker replyErrorChecker(ObjectMapper objectMapper) {
		return new ReplyErrorChecker(objectMapper);
	}

	@Bean
    ReplyingKafkaTemplateFactory replyingKafkaTemplateFactory(ConcurrentKafkaListenerContainerFactory<String, String> containerFactory,
                                                              ProducerFactory<String, String> producerFactory,
                                                              ReplyErrorChecker replyErrorChecker) {
		return new ReplyingKafkaTemplateFactoryImpl(containerFactory, producerFactory, replyErrorChecker);
	}

	@Bean
	EventDispatcher eventDispatcher(@Value("${spring.application.name}") String applicationName,
									PulsarProducersManager pulsarProducersManager, ObjectMapper objectMapper) {
		return new PulsarEventDispatcher(applicationName, pulsarProducersManager, objectMapper);
	}

	@Bean
	KafkaCommandExecutor<GetAuthorizationStatusRequest, GetAuthorizationStatusResponse> executorForGetAuthorizationStatusRequest() {
		return new KafkaCommandExecutor<>(GetAuthorizationStatusResponse.class);
	}

	@Bean
	PulsarAdmin pulsarAdmin() {
		try {
			var admin = new PulsarAdminBuilderImpl()
					.serviceHttpUrl("http://localhost:8080")
					.build();
			if(!admin.tenants().getTenants().contains("webprotege")) {
				admin.tenants().createTenant("webprotege", new TenantInfoImpl(Set.of("admin"), Set.of("standalone")));
				admin.namespaces().createNamespace("webprotege/events");
				admin.namespaces().createNamespace("webprotege/commands");
			}
			return admin;
		} catch (PulsarClientException | PulsarAdminException e) {
			throw new RuntimeException(e);
		}
	}

	@Bean
	PulsarClient pulsarClient() throws PulsarClientException {
		return PulsarClient.builder()
						   .serviceUrl("pulsar://localhost:6650").build();
	}

	@Bean
	PulsarProducersManager pulsarProducersManager(PulsarClient pulsarClient,
												  @Value("${spring.application.name}") String applicationName) {
		return new PulsarProducersManager(pulsarClient, applicationName);
	}

	@Bean
	PulsarCommandHandlerWrapperFactory pulsarCommandHandlerWrapperFactory(@Value("${spring.application.name}") String applicationName,
																		  ObjectMapper objectMapper,
																		  PulsarProducersManager producersManager,
																		  CommandExecutor<GetAuthorizationStatusRequest, GetAuthorizationStatusResponse> authorizationStatusExecutor,
																		  PulsarClient pulsarClient) {

		return new PulsarCommandHandlerWrapperFactory() {
			@Override
			public <Q extends Request<R>, R extends Response> PulsarCommandHandlerWrapper<Q, R> create(CommandHandler<Q, R> handler) {
				return pulsarCommandHandlerWrapper(handler,
														applicationName,
														pulsarClient,
														objectMapper,
														producersManager,
														authorizationStatusExecutor);
			}
		};
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public  <Q extends Request<R>, R extends Response> PulsarCommandHandlerWrapper<Q, R> pulsarCommandHandlerWrapper(
			CommandHandler<Q, R> handler,
			String applicationName,
			PulsarClient pulsarClient,
			ObjectMapper objectMapper,
			PulsarProducersManager producersManager,
			CommandExecutor<GetAuthorizationStatusRequest, GetAuthorizationStatusResponse> authorizationStatusExecutor) {
		return new PulsarCommandHandlerWrapper<>(applicationName,
												 pulsarClient,
												 handler,
												 objectMapper,
												 producersManager,
												 authorizationStatusExecutor);
	}

	@Bean
	Caffeine<Object, Object> pulsarProducerCaffeineConfig() {
		return Caffeine.newBuilder()
					   .expireAfterAccess(10, TimeUnit.MINUTES);
	}

	@Bean
	public CacheManager cacheManager(Caffeine<Object, Object> caffeine) {
		var caffeineCacheManager = new CaffeineCacheManager();
		caffeineCacheManager.setCaffeine(caffeine);
		return caffeineCacheManager;
	}
}
