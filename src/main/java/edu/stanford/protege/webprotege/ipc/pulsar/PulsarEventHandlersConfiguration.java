package edu.stanford.protege.webprotege.ipc.pulsar;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.stanford.protege.webprotege.common.Event;
import edu.stanford.protege.webprotege.ipc.EventHandler;
import org.apache.pulsar.client.api.PulsarClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * Matthew Horridge
 * Stanford Center for Biomedical Informatics Research
 * 2022-02-03
 */
@Configuration
public class PulsarEventHandlersConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(PulsarEventHandlersConfiguration.class);

    @Autowired(required = false)
    private List<EventHandler<? extends Event>> eventHandlers = new ArrayList<>();

    @Lazy
    @Autowired
    private PulsarEventHandlerWrapperFactory wrapperFactory;

    @EventListener
    public void onApplicationEvent(ContextRefreshedEvent event) {
        logger.info("Event handlers configuration:");
        eventHandlers.forEach(handler -> {
            logger.info("Auto-detected event handler {} for channel {}",
                        handler.getHandlerName(),
                        handler.getChannelName());
                        var wrapper = wrapperFactory.create(handler);
                        wrapper.subscribe();
        });

    }

    @Bean
    PulsarEventHandlerWrapperFactory pulsarEventHandlerWrapperFactory(@Value("${spring.application.name}") String applicationName,
                                                                      ObjectMapper objectMapper,
                                                                      PulsarClient pulsarClient,
                                                                      @Value("${webprotege.pulsar.tenant}") String tenant) {
        return handler -> pulsarEventHandlerWrapper(handler, applicationName, objectMapper, pulsarClient, tenant);
    }

    public PulsarEventHandlerWrapper<?> pulsarEventHandlerWrapper(EventHandler<?> handler,
                                                                  String applicationName,
                                                                  ObjectMapper objectMapper, PulsarClient pulsarClient,
                                                                  @Value("${webprotege.pulsar.tenant}") String tenant) {
        return new PulsarEventHandlerWrapper<>(applicationName, tenant, handler, objectMapper, pulsarClient);
    }
}
