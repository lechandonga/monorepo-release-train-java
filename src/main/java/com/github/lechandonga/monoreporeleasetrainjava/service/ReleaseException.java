package com.github.lechandonga.monoreporeleasetrainjava.service;

public class ReleaseException extends RuntimeException {
    private final ReleaseErrorCode code;

    public ReleaseException(ReleaseErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ReleaseErrorCode code() {
        return code;
    }
}
