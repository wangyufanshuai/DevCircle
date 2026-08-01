package io.github.devcircle.auth.application;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;

/**
 * Marks security failures whose token-family revocation must commit even though the request fails.
 */
final class TokenRevokedException extends BusinessException {
    TokenRevokedException(String message) {
        super(ErrorCode.TOKEN_REVOKED, message);
    }
}
