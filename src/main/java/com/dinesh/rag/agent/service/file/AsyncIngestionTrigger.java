package com.dinesh.rag.agent.service.file;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Production {@link IngestionTrigger}. Defers {@link FileIngestionService#ingest(UUID)}
 * until the surrounding upload transaction has committed so the async worker
 * is guaranteed to see the new {@code files} row.
 *
 * <p>If the call happens outside any transaction (shouldn't, but defensive),
 * the worker is invoked immediately.</p>
 */
@Component
@Profile("!test")
public class AsyncIngestionTrigger implements IngestionTrigger {

    private final FileIngestionService ingestionService;

    public AsyncIngestionTrigger(FileIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @Override
    public void trigger(UUID fileId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    ingestionService.ingest(fileId);
                }
            });
        } else {
            ingestionService.ingest(fileId);
        }
    }
}
