package com.dinesh.rag.agent;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

import static org.mockito.Mockito.mockStatic;

class RagAgentApplicationTest {

    @Test
    void main_invokesSpringApplicationRun() {
        try (MockedStatic<SpringApplication> mocked = mockStatic(SpringApplication.class)) {
            String[] args = {"--server.port=0"};
            RagAgentApplication.main(args);
            mocked.verify(() -> SpringApplication.run(RagAgentApplication.class, args));
        }
    }

    @Test
    void constructor_canBeInstantiated() {
        new RagAgentApplication();
    }
}
