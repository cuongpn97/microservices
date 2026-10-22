package com.webshop.messaging.testapp;

import com.webshop.messaging.EventEnvelope;
import com.webshop.messaging.PermanentMessageException;
import com.webshop.messaging.consumer.MessageHandler;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Handler có thể lập trình hành vi theo từng eventId để test retry/DLQ/dedup. */
public class TestHandler implements MessageHandler {

    public enum Mode { SUCCEED, TRANSIENT_ALWAYS, PERMANENT, TRANSIENT_ONCE }

    private final Map<UUID, AtomicInteger> invocations = new ConcurrentHashMap<>();
    private final Map<UUID, Mode> modes = new ConcurrentHashMap<>();
    private final Set<UUID> succeeded = ConcurrentHashMap.newKeySet();

    public void mode(UUID eventId, Mode mode) {
        modes.put(eventId, mode);
    }

    public int invocations(UUID eventId) {
        AtomicInteger count = invocations.get(eventId);
        return count == null ? 0 : count.get();
    }

    public boolean succeeded(UUID eventId) {
        return succeeded.contains(eventId);
    }

    @Override
    public void handle(EventEnvelope envelope) {
        int n = invocations.computeIfAbsent(envelope.eventId(), k -> new AtomicInteger()).incrementAndGet();
        Mode mode = modes.getOrDefault(envelope.eventId(), Mode.SUCCEED);
        switch (mode) {
            case TRANSIENT_ALWAYS -> throw new IllegalStateException("simulated transient failure #" + n);
            case PERMANENT -> throw new PermanentMessageException("simulated poison message");
            case TRANSIENT_ONCE -> {
                if (n == 1) {
                    throw new IllegalStateException("simulated transient failure once");
                }
            }
            default -> {
            }
        }
        succeeded.add(envelope.eventId());
    }
}
