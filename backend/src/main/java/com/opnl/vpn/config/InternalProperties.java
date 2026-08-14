package com.opnl.vpn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Internal control-plane listener: the mTLS endpoint served by the backend for remote node agents.
 * Bound from {@code opnl.internal.*} ({@code OPNL_INTERNAL_MTLS_PORT}, {@code
 * OPNL_INTERNAL_TLS_DIR}).
 */
@ConfigurationProperties(prefix = "opnl.internal")
public record InternalProperties(int mtlsPort, String tlsDir) {}
