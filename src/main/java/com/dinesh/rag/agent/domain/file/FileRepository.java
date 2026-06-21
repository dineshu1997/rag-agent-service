package com.dinesh.rag.agent.domain.file;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface FileRepository extends JpaRepository<FileEntity, UUID> {

    Optional<FileEntity> findBySha256Hash(String hash);

    /**
     * Case-insensitive search over {@code display_name}. {@code search} may
     * be null/empty (then all files are returned, paginated).
     */
    @Query("""
        SELECT f FROM FileEntity f
        WHERE (:search IS NULL OR :search = ''
               OR LOWER(f.displayName) LIKE LOWER(CONCAT('%', :search, '%')))
    """)
    Page<FileEntity> search(@Param("search") String search, Pageable pageable);
}
