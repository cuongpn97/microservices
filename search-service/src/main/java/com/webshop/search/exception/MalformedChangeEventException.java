package com.webshop.search.exception;

/** Message CDC không đọc được (JSON hỏng, thiếu trường bắt buộc). Retry vô ích → đi thẳng DLT. */
public class MalformedChangeEventException extends RuntimeException {

    public MalformedChangeEventException(String message) {
        super(message);
    }

    public MalformedChangeEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
