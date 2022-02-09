package edu.stanford.protege.webprotege.ipc;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import edu.stanford.protege.webprotege.common.Request;
import edu.stanford.protege.webprotege.common.Response;
import edu.stanford.protege.webprotege.common.UserId;
import edu.stanford.protege.webprotege.ipc.pulsar.PulsarCommandExecutor;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.PulsarClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Matthew Horridge
 * Stanford Center for Biomedical Informatics Research
 * 2022-02-09
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public class CommandExecutor_CommandHandler_ExceptionThrowing_TestsCase {

    @Autowired
    CommandExecutor<TestRequest, TestResponse> executor;

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    PulsarClient pulsarClient;

    @Autowired
    private PulsarAdmin pulsarAdmin;

    @Value("${webprotege.pulsar.tenant}")
    private String tenant;

    @BeforeEach
    void setUp() {
    }

    @AfterEach
    void tearDown() throws PulsarAdminException {
        PulsarTestUtils.deleteTestTenant(pulsarAdmin, tenant);
    }

    @Test
    void shouldAutowireCommandExecutor() {
        assertThat(executor).isNotNull();
    }

    /**
     * Tests that if an uncaught exception leaks out from the handler then this is manifested as an internal server
     * error (HTTP 500) in the caller.
     */
    @Test
    void shouldSendRequestAndReceivedInternalServerErrorFromCommand() {
        var id = UUID.randomUUID().toString();
        assertThatThrownBy(() -> {
            var response = executor.execute(new TestRequest(id), new ExecutionContext(new UserId("JohnSmith"), ""));
            response.get(30, TimeUnit.SECONDS);
        }).isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(CommandExecutionException.class)
          .matches(throwable -> ((CommandExecutionException) throwable.getCause()).getStatusCode() == 500);

    }

    @TestConfiguration
    public static class CommandExecutorConfig {

        /**
         * The bean for the {@link CommandExecutor}.  This is part of the smoke test, checking
         * that everything can be instantiated
         */
        @Bean
        CommandExecutor<TestRequest, TestResponse> commandExecutor() {
            return new PulsarCommandExecutor<>(TestResponse.class);
        }

        @Bean
        CommandHandler<TestRequest, TestResponse> commandHandler() {
            return new TestCommandHandler();
        }

    }


    @JsonTypeName("TestRequest")
    private static class TestRequest implements Request<TestResponse> {

        private static final String CHANNEL = "webprotege-tests.test-request";

        private final String id;

        @JsonCreator
        public TestRequest(@JsonProperty("id") String id) {
            this.id = id;
        }

        @Override
        public String getChannel() {
            return CHANNEL;
        }

        public String getId() {
            return id;
        }
    }

    @JsonTypeName("TestResponse")
    private static class TestResponse implements Response {

        private final String id;

        @JsonCreator
        public TestResponse(@JsonProperty("id") String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }

    @WebProtegeHandler
    private static class TestCommandHandler implements CommandHandler<TestRequest, TestResponse> {

        @Nonnull
        @Override
        public String getChannelName() {
            return TestRequest.CHANNEL;
        }

        @Override
        public Class<TestRequest> getRequestClass() {
            return TestRequest.class;
        }

        @Override
        public Mono<TestResponse> handleRequest(TestRequest request, ExecutionContext executionContext) {
            // Deliberately throw an exception to simulate an uncaught exception
            throw new RuntimeException("Expected exception leaked by command handler");
        }
    }
}
