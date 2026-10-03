package com.seatres.repository;

import com.seatres.domain.ShowEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShowJpaRepository extends JpaRepository<ShowEntity, UUID> {
}
