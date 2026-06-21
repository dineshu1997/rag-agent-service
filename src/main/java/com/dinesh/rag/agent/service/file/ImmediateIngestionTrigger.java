package com.dinesh.rag.agent.service.file;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Test-only {@link IngestionTrigger} that calls {@link FileIngestionService#ingest(UUID)}
 * synchronously. Needed because integration tests run inside a {@code @Transactional}
 * method that rolls back at the end — there is no commit for an "afterCommit"
 * hook to fire on, and Mockito {@code verify(...)} would otherwise miss the call.
 */
@Component
@Profile("test")
public class ImmediateIngestionTrigger implements IngestionTrigger {

    private final FileIngestionService ingestionService;

    public ImmediateIngestionTrigger(FileIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @Override
    public void trigger(UUID fileId) {
        ingestionService.ingest(fileId);
    }
}
