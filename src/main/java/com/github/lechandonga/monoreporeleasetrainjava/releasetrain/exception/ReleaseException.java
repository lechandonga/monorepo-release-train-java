package com.github.lechandonga.monoreporeleasetrainjava.releasetrain.exception;

/** 发布列车领域异常，携带可区分的错误码。 */
public class ReleaseException extends RuntimeException {

    private final ReleaseErrorCode code;

    public ReleaseException(ReleaseErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ReleaseErrorCode getCode() {
        return code;
    }
}
