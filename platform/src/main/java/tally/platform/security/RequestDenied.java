package tally.platform.security;

import tally.platform.error.ErrorCode;

/** Published for every request answered with 401 or 403. */
public record RequestDenied(
    String clientId,
    String role,
    String scopes,
    String method,
    String path,
    String requestId,
    ErrorCode code) {}
