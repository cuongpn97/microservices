package com.webshop.common.http;

/**
 * Remote dependency không dùng được (circuit OPEN, timeout, 5xx, lỗi kết nối) sau khi đã hết retry.
 * GlobalExceptionHandler chuyển thành HTTP 503 để client biết đây là lỗi tạm thời.
 */
public class DependencyUnavailableException extends RuntimeException {

    private final String dependency;

    public DependencyUnavailableException(String dependency, String message, Throwable cause) {
        super(dependency + " unavailable: " + message, cause);
        this.dependency = dependency;
    }

    public String dependency() {
        return dependency;
    }
}
