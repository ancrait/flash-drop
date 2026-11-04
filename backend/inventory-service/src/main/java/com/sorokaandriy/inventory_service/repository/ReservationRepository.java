package com.sorokaandriy.inventory_service.repository;

import com.sorokaandriy.inventory_service.entity.Reservation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, UUID> {
    boolean existsByOrderId(UUID orderId);

    @Modifying
    @Query(value = """
            INSERT INTO reservations (
                id,
                order_id,
                status,
                request_hash
            )
            VALUES (
                :id,
                :orderId,
                'RESERVED',
                :requestHash
            )
            ON CONFLICT (order_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("orderId") UUID orderId,
            @Param("requestHash") String requestHash
    );

    @Modifying
    @Query(value = """
            INSERT INTO reservations (id, order_id, status, updated_at)
            VALUES (:id, :orderId, 'RELEASED', NOW())
            ON CONFLICT (order_id) DO NOTHING
            """, nativeQuery = true)
    int insertReleasedIfAbsent(@Param("id") UUID id, @Param("orderId") UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Reservation> findByOrderId(UUID orderId);
}
