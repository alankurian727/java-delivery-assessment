package com.abnamro.delivery.order.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryOrderRepository extends JpaRepository<DeliveryOrderEntity, UUID> {
}
