package com.opnl.vpn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for the agent Spring profile. An agent is a lightweight opnl-vpn instance
 * running next to its own OpenVPN gateway; it registers and heartbeats to the central backend via
 * {@code /internal/node/*} over the internal mTLS endpoint. Bound from {@code opnl.agent.*}. Values
 * are validated at runtime by the agent profile service so the properties stay optional in other
 * profiles.
 */
@ConfigurationProperties(prefix = "opnl.agent")
public record AgentProperties(
    String centralBaseUrl,
    String nodeName,
    String mgmtHost,
    int mgmtPortBase,
    String adminIp,
    String mgmtPassword,
    long heartbeatSeconds,
    String tlsCa,
    String tlsCert,
    String tlsKey) {}
