# OpenVPN Community Edition + tooling for the management panel.
# Requires NET_ADMIN (or --privileged) for iptables-based access control.
FROM alpine:3.20

# OpenVPN 2.6, Easy-RSA 3.1, OpenSSL, iptables (nft backend), dnsmasq (domain control), curl (backend callbacks)
RUN apk add --no-cache \
        openvpn \
        easy-rsa \
        openssl \
        iptables \
        ip6tables \
        iproute2 \
        dnsmasq \
        curl \
        jq \
        bash \
        tini \
        coreutils

# Runtime layout (mirrors OPNL_DATA_DIR inside the container)
ENV OPNL_CONFIG_DIR=/etc/opnl/config \
    OPNL_CCD_DIR=/etc/opnl/ccd \
    OPNL_PKI_DIR=/etc/opnl/pki \
    OPNL_LOG_DIR=/var/log/opnl

RUN mkdir -p "$OPNL_CONFIG_DIR" "$OPNL_CCD_DIR" "$OPNL_PKI_DIR" "$OPNL_LOG_DIR" \
    && mkdir -p /etc/openvpn/scripts

COPY scripts/ /etc/openvpn/scripts/
RUN chmod +x /etc/openvpn/scripts/*.sh

COPY dnsmasq.conf /etc/dnsmasq.conf
COPY entrypoint.sh /usr/local/bin/opnl-entrypoint
RUN chmod +x /usr/local/bin/opnl-entrypoint

EXPOSE 1194/udp 1194/tcp 7505
HEALTHCHECK --interval=15s --timeout=5s --retries=5 \
    CMD bash -c 'set -- "$OPNL_CONFIG_DIR"/daemon-*.conf; \
      if [ "$1" = "$OPNL_CONFIG_DIR/daemon-*.conf" ]; then exit 0; fi; \
      for conf in "$OPNL_CONFIG_DIR"/daemon-*.conf; do \
        name="$(basename "$conf" .conf)"; \
        pidfile="$OPNL_LOG_DIR/$name.pid"; \
        if [ ! -f "$pidfile" ] || ! kill -0 "$(cat "$pidfile")" 2>/dev/null \
          || ! grep -qa "$name" "/proc/$(cat "$pidfile")/cmdline" 2>/dev/null; then \
          echo "daemon $name not running"; exit 1; \
        fi; \
      done; \
      exit 0'

ENTRYPOINT ["/sbin/tini", "--", "/usr/local/bin/opnl-entrypoint"]
