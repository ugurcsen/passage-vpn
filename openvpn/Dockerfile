# OpenVPN Community Edition + tooling for the management panel.
# Requires NET_ADMIN (or --privileged) for iptables-based access control.
FROM alpine:3.24

# OpenVPN 2.7 (pinned to the latest Alpine 3.24 build; bump to 2.7.6-r0 when
# it lands in the Alpine repos), Easy-RSA 3.1, OpenSSL, iptables (nft backend),
# dnsmasq (domain control), curl (backend callbacks)
RUN apk add --no-cache \
        openvpn=2.7.5-r0 \
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

# Runtime layout (mirrors PASSAGE_DATA_DIR inside the container)
ENV PASSAGE_CONFIG_DIR=/etc/passage/config \
    PASSAGE_CCD_DIR=/etc/passage/ccd \
    PASSAGE_PKI_DIR=/etc/passage/pki \
    PASSAGE_LOG_DIR=/var/log/passage

RUN mkdir -p "$PASSAGE_CONFIG_DIR" "$PASSAGE_CCD_DIR" "$PASSAGE_PKI_DIR" "$PASSAGE_LOG_DIR" \
    && mkdir -p /etc/openvpn/scripts

COPY scripts/ /etc/openvpn/scripts/
RUN chmod +x /etc/openvpn/scripts/*.sh

COPY dnsmasq.conf /etc/dnsmasq.conf
COPY entrypoint.sh /usr/local/bin/passage-entrypoint
RUN chmod +x /usr/local/bin/passage-entrypoint

EXPOSE 1194/udp 1194/tcp 7505
HEALTHCHECK --interval=15s --timeout=5s --retries=5 \
    CMD bash -c 'set -- "$PASSAGE_CONFIG_DIR"/daemon-*.conf; \
      if [ "$1" = "$PASSAGE_CONFIG_DIR/daemon-*.conf" ]; then exit 0; fi; \
      for conf in "$PASSAGE_CONFIG_DIR"/daemon-*.conf; do \
        name="$(basename "$conf" .conf)"; \
        pidfile="$PASSAGE_LOG_DIR/$name.pid"; \
        if [ ! -f "$pidfile" ] || ! kill -0 "$(cat "$pidfile")" 2>/dev/null \
          || ! grep -qa "$name" "/proc/$(cat "$pidfile")/cmdline" 2>/dev/null; then \
          echo "daemon $name not running"; exit 1; \
        fi; \
      done; \
      exit 0'

ENTRYPOINT ["/sbin/tini", "--", "/usr/local/bin/passage-entrypoint"]
