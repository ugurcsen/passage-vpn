package com.passagevpn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Internal control-plane listener: the mTLS endpoint served by the backend for remote node agents.
 * Bound from {@code opnl.internal.*} ({@code PASSAGE_INTERNAL_MTLS_PORT}, {@code
 * PASSAGE_INTERNAL_TLS_DIR}).
 */
@ConfigurationProperties(prefix = "passage.internal")
public record InternalProperties(int mtlsPort, String tlsDir) {}
