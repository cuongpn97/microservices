package com.webshop.messaging;

/** Header AMQP dùng cho truy vết, retry và DLQ. */
public final class MessageHeaders {

    public static final String EVENT_TYPE = "x-event-type";
    public static final String SAGA_ID = "x-saga-id";
    /** Số lần đã xử lý (1 = lần đầu). */
    public static final String ATTEMPT = "x-attempt";
    public static final String ORIGINAL_ROUTING_KEY = "x-original-routing-key";
    public static final String ORIGINAL_EXCHANGE = "x-original-exchange";
    public static final String ORIGINAL_QUEUE = "x-original-queue";
    public static final String LAST_ERROR = "x-last-error";
    public static final String FAILURE_REASON = "x-failure-reason";
    public static final String FAILED_AT = "x-failed-at";
    public static final String REDRIVE_COUNT = "x-redrive-count";

    private MessageHeaders() {
    }
}
