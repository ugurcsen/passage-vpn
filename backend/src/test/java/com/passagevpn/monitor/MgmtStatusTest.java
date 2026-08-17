package com.passagevpn.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MgmtStatusTest {

  @Test
  void parsesTitleClientsAndDco() {
    List<String> lines =
        List.of(
            "TITLE,OpenVPN 2.6.12 x86_64-pc-linux-gnu [SSL (OpenSSL)] [DCO] built on ...",
            "TIME,Tue Aug 11 12:00:00 2026,12345,2",
            "HEADER,CLIENT_LIST,Common Name,Real Address,Virtual Address,Virtual IPv6 Address,"
                + "Bytes Received,Bytes Sent,Connected Since,Connected Since (time),Username,Client ID,Peer ID,Data Channel Cipher",
            "CLIENT_LIST,alice,203.0.113.5:51920,10.8.0.2,,10240,20480,1712800000,10:00:00,alice,3,1,AES-256-GCM",
            "CLIENT_LIST,bob,203.0.113.6:44321,10.8.0.3,,512,256,1712800100,10:01:00,bob,7,4,AES-256-GCM",
            "HEADER,ROUTING_TABLE,Virtual Address,Common Name,Real Address,Last Ref",
            "ROUTING_TABLE,10.8.0.2,alice,203.0.113.5:51920,1712800100",
            "END");

    MgmtStatus status = MgmtStatus.parse(lines, Instant.parse("2026-01-01T00:00:00Z"));

    assertThat(status.title()).contains("OpenVPN 2.6.12");
    assertThat(status.dco()).isTrue();
    assertThat(status.numClients()).isEqualTo(2);
    assertThat(status.clients()).hasSize(2);

    MgmtStatus.MgmtClientStatus alice = status.clients().get(0);
    assertThat(alice.commonName()).isEqualTo("alice");
    assertThat(alice.realAddress()).isEqualTo("203.0.113.5:51920");
    assertThat(alice.virtualAddress()).isEqualTo("10.8.0.2");
    assertThat(alice.bytesIn()).isEqualTo(10240);
    assertThat(alice.bytesOut()).isEqualTo(20480);
    assertThat(alice.connectedSince()).isEqualTo(Instant.ofEpochSecond(1712800000));
    assertThat(alice.clientId()).isEqualTo(3);
  }

  @Test
  void parsesTabSeparatedStatusVersion3Lines() {
    // OpenVPN 2.7's `status 3` management command returns --status-version 3
    // (tab-separated) output; mirrors real daemon-0.status content.
    List<String> lines =
        List.of(
            "TITLE\tOpenVPN 2.7.5 x86_64-alpine-linux-musl [SSL (OpenSSL)] [LZO] [LZ4] [EPOLL] [MH/PKTINFO] [AEAD]",
            "TIME\t2026-08-11 08:26:25\t1786436785",
            "HEADER\tCLIENT_LIST\tCommon Name\tReal Address\tVirtual Address\tVirtual IPv6 Address\t"
                + "Bytes Received\tBytes Sent\tConnected Since\tConnected Since (time_t)\tUsername\tClient ID\tPeer ID\tData Channel Cipher",
            "CLIENT_LIST\tadmin\t31.223.13.8:27961\t10.8.0.2\t\t148702\t179132\t2026-08-11 08:28:23\t1786436903\tadmin\t0\t0\tAES-256-GCM",
            "HEADER\tROUTING_TABLE\tVirtual Address\tCommon Name\tReal Address\tLast Ref\tLast Ref (time_t)",
            "ROUTING_TABLE\t10.8.0.2\tadmin\t31.223.13.8:27961\t2026-08-11 08:28:37\t1786436917",
            "GLOBAL_STATS\tMax bcast/mcast queue length\t0",
            "GLOBAL_STATS\tdco_enabled\t0",
            "END");

    MgmtStatus status = MgmtStatus.parse(lines, Instant.parse("2026-01-01T00:00:00Z"));

    assertThat(status.title()).contains("OpenVPN 2.7.5");
    assertThat(status.dco()).isFalse();
    assertThat(status.clients()).hasSize(1);

    MgmtStatus.MgmtClientStatus admin = status.clients().get(0);
    assertThat(admin.commonName()).isEqualTo("admin");
    assertThat(admin.realAddress()).isEqualTo("31.223.13.8:27961");
    assertThat(admin.virtualAddress()).isEqualTo("10.8.0.2");
    assertThat(admin.bytesIn()).isEqualTo(148702);
    assertThat(admin.bytesOut()).isEqualTo(179132);
    // The `since` column is a display string, not an epoch; it must not crash parsing.
    assertThat(admin.connectedSince()).isNull();
    assertThat(admin.clientId()).isZero();
  }

  @Test
  void titleWithoutDcoReportsDcoFalse() {
    MgmtStatus status =
        MgmtStatus.parse(
            List.of("TITLE,OpenVPN 2.6.12 x86_64-pc-linux-gnu built on ...", "END"), Instant.now());
    assertThat(status.dco()).isFalse();
  }

  @Test
  void toleratesMalformedClientRows() {
    List<String> lines =
        List.of(
            "TITLE,OpenVPN 2.6.12",
            "CLIENT_LIST,alice,203.0.113.5,10.8.0.2,,100,200", // truncated row (no cipher)
            "CLIENT_LIST,,,", // blank common name
            "CLIENT_LIST,bob,203.0.113.6,10.8.0.3,,not-a-number,8,1712800000,10:00:00,bob,1,1,AES-256-GCM");

    MgmtStatus status = MgmtStatus.parse(lines, Instant.now());

    assertThat(status.clients()).hasSize(2);
    MgmtStatus.MgmtClientStatus bob = status.clients().get(1);
    assertThat(bob.bytesIn()).isZero(); // unparseable counter defaults to 0
  }

  @Test
  void emptyStatusHasNoClients() {
    MgmtStatus status = MgmtStatus.parse(List.of("TITLE,OpenVPN 2.6.12", "END"), Instant.now());
    assertThat(status.clients()).isEmpty();
    assertThat(status.numClients()).isZero();
  }
}
