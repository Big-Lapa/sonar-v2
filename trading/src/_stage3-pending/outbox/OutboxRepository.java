package com.sonar.trading.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEntry, Long> {

    @Query("SELECT e FROM OutboxEntry e WHERE e.publishedAt IS NULL ORDER BY e.id ASC")
    List<OutboxEntry> findUnpublishedOrderById(Pageable pageable);

    @Query("SELECT COUNT(e) FROM OutboxEntry e WHERE e.publishedAt IS NULL")
    long countUnpublished();

    @Modifying
    @Query("DELETE FROM OutboxEntry e WHERE e.publishedAt IS NOT NULL AND e.createdAt < :cutoff")
    int deletePublishedOlderThan(long cutoff);

    @Modifying
    @Query("DELETE FROM OutboxEntry e WHERE e.createdAt < :cutoff")
    int deleteOlderThan(long cutoff);
}
