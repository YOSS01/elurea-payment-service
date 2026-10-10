package com.elurea.payment_service.repository;

import com.elurea.payment_service.entity.Payment;
import com.elurea.payment_service.enums.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /** Paged listing; all filters are optional. */
    @Query("""
            SELECT p FROM Payment p
            WHERE (:userId IS NULL OR p.userId = :userId)
              AND (:orderId IS NULL OR p.orderId = :orderId)
              AND (:status IS NULL OR p.status = :status)
            """)
    Page<Payment> search(@Param("userId") UUID userId, @Param("orderId") UUID orderId,
                         @Param("status") PaymentStatus status, Pageable pageable);

    /** Only the order id (immutable), so the payment itself is first loaded under lock (lockAllByOrderId). */
    @Query("SELECT p.orderId FROM Payment p WHERE p.providerPaymentId = :providerPaymentId")
    Optional<UUID> findOrderIdByProviderPaymentId(@Param("providerPaymentId") String providerPaymentId);

    Optional<Payment> findFirstByOrderIdAndStatusOrderByCreatedAtDesc(UUID orderId, PaymentStatus status);

    /**
     * Locks every attempt of one order (SELECT ... FOR UPDATE). Success and refund handling for the same
     * order therefore run one at a time, which is what guarantees "at most one successful payment".
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.orderId = :orderId")
    List<Payment> lockAllByOrderId(@Param("orderId") UUID orderId);
}
