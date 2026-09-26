package dev.mars.qraft.controller.http;

import dev.mars.qraft.catalog.HealthCheckState;
import dev.mars.qraft.catalog.ServiceHealth;
import dev.mars.qraft.catalog.ServiceInstance;

import java.util.Comparator;
import java.util.List;

/** One service instance and its replicated checks, as returned by health discovery. */
record HealthServiceEntry(ServiceInstance service, List<Check> checks) {

    static HealthServiceEntry from(ServiceInstance service, List<HealthCheckState> states) {
        return new HealthServiceEntry(service, states.stream()
                .sorted(Comparator.comparing((HealthCheckState state) -> state.checkId().checkId()))
                .map(Check::from)
                .toList());
    }

    record Check(
            String checkId,
            ServiceHealth status,
            boolean required,
            long sequenceNumber,
            String observedAt,
            String acceptedAt,
            String deadline,
            boolean expired,
            String output) {

        static Check from(HealthCheckState state) {
            var observation = state.observation();
            return new Check(state.checkId().checkId(), observation.status(), observation.required(),
                    observation.sequenceNumber(), observation.observedAt().toString(),
                    state.acceptedAt().toString(), state.deadline().toString(), state.expired(),
                    observation.output());
        }
    }
}
