package com.jiyun.dbsplit;

import org.springframework.test.context.TestPropertySource;

/** Reuses real-MySQL capacity, credit, schedule, idempotency, rollback and cancellation tests. */
@TestPropertySource(properties={"experiment.atomic-capacity=true"})
class AtomicCapacitySafetyTest extends EnrollmentSafetyTest {}
