package com.github.lechandonga.monoreporeleasetrainjava.releasetrain.web;

import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.exception.ReleaseErrorCode;
import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.exception.ReleaseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 把领域错误映射成带明确错误码的 HTTP 响应，客户端可区分具体问题类型。 */
@RestControllerAdvice
public class ReleaseExceptionHandler {

    @ExceptionHandler(ReleaseException.class)
    public ResponseEntity<Map<String, Object>> handle(ReleaseException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case BATCH_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case BATCH_CONFLICT -> HttpStatus.CONFLICT;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).body(body(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        String message = ex.getMostSpecificCause().getMessage();
        ReleaseErrorCode code = message != null && message.contains("Duplicate field")
                ? ReleaseErrorCode.DUPLICATE_DEPENDENCY
                : ReleaseErrorCode.INVALID_CHANGE;
        return ResponseEntity.unprocessableEntity().body(body(code,
                code == ReleaseErrorCode.DUPLICATE_DEPENDENCY
                        ? "请求体中存在重复声明的字段/依赖: " + message
                        : "请求体无法解析: " + message));
    }

    private Map<String, Object> body(ReleaseErrorCode code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code.name());
        body.put("message", message);
        body.put("description", code.description());
        body.put("timestamp", Instant.now().toString());
        return body;
    }
}
