package com.passagevpn.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Exercises the management protocol against a real fake-daemon socket. */
class MgmtClientTest {

  @Test
  void statusParsesResponseFromSocket() throws Exception {
    try (ServerSocket listener = new ServerSocket(0)) {
      int port = listener.getLocalPort();
      Thread daemon =
          new Thread(
              () -> {
                try (Socket s = listener.accept()) {
                  BufferedReader r =
                      new BufferedReader(
                          new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                  r.readLine(); // expect "status 3"
                  BufferedWriter w =
                      new BufferedWriter(
                          new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
                  w.write("TITLE,OpenVPN 2.6.12 x86_64 [DCO] built on ...\n");
                  w.write(
                      "CLIENT_LIST,alice,203.0.113.5:51920,10.8.0.2,,1024,2048,1712800000,10:00:00,alice,3,1,AES-256-GCM\n");
                  w.write("END\n");
                  w.flush();
                } catch (IOException ignored) {
                  // test teardown
                }
              });
      daemon.start();

      MgmtClient client = new MgmtClient("127.0.0.1", port, 0);
      MgmtStatus status = client.status();
      daemon.join(3_000);

      assertThat(status).isNotNull();
      assertThat(status.dco()).isTrue();
      assertThat(status.clients()).hasSize(1);
      MgmtStatus.MgmtClientStatus alice = status.clients().get(0);
      assertThat(alice.commonName()).isEqualTo("alice");
      assertThat(alice.bytesIn()).isEqualTo(1024);
      assertThat(alice.bytesOut()).isEqualTo(2048);
      client.close();
    }
  }

  @Test
  void statusParsesTabSeparatedVersion3Response() throws Exception {
    try (ServerSocket listener = new ServerSocket(0)) {
      int port = listener.getLocalPort();
      Thread daemon =
          new Thread(
              () -> {
                try (Socket s = listener.accept()) {
                  BufferedReader r =
                      new BufferedReader(
                          new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                  r.readLine(); // expect "status 3"
                  BufferedWriter w =
                      new BufferedWriter(
                          new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
                  w.write(
                      "TITLE\tOpenVPN 2.7.5 x86_64-alpine-linux-musl [SSL (OpenSSL)] built on ...\n");
                  w.write(
                      "CLIENT_LIST\tadmin\t31.223.13.8:27961\t10.8.0.2\t\t148702\t179132\t2026-08-11 08:28:23\t1786436903\tadmin\t0\t0\tAES-256-GCM\n");
                  w.write("END\n");
                  w.flush();
                } catch (IOException ignored) {
                  // test teardown
                }
              });
      daemon.start();

      MgmtClient client = new MgmtClient("127.0.0.1", port, 0);
      MgmtStatus status = client.status();
      daemon.join(3_000);

      assertThat(status).isNotNull();
      assertThat(status.title()).contains("OpenVPN 2.7.5");
      assertThat(status.dco()).isFalse();
      assertThat(status.clients()).hasSize(1);
      MgmtStatus.MgmtClientStatus admin = status.clients().get(0);
      assertThat(admin.commonName()).isEqualTo("admin");
      assertThat(admin.bytesIn()).isEqualTo(148702);
      assertThat(admin.bytesOut()).isEqualTo(179132);
      client.close();
    }
  }

  @Test
  void killAcknowledgesSuccess() throws Exception {
    try (ServerSocket listener = new ServerSocket(0)) {
      int port = listener.getLocalPort();
      Thread daemon =
          new Thread(
              () -> {
                try (Socket s = listener.accept()) {
                  BufferedReader r =
                      new BufferedReader(
                          new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                  String command = r.readLine();
                  BufferedWriter w =
                      new BufferedWriter(
                          new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
                  if ("kill alice".equals(command)) {
                    w.write("SUCCESS: common name 'alice' found, client disconnected\n");
                  } else {
                    w.write("ERROR: common name 'alice' not found\n");
                  }
                  w.flush();
                } catch (IOException ignored) {
                  // test teardown
                }
              });
      daemon.start();

      MgmtClient client = new MgmtClient("127.0.0.1", port, 0);
      assertThat(client.kill("alice")).isTrue();
      daemon.join(3_000);
      client.close();
    }
  }

  @Test
  void killReportsFailureForUnknownClient() throws Exception {
    try (ServerSocket listener = new ServerSocket(0)) {
      int port = listener.getLocalPort();
      Thread daemon =
          new Thread(
              () -> {
                try (Socket s = listener.accept()) {
                  BufferedReader r =
                      new BufferedReader(
                          new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                  r.readLine();
                  BufferedWriter w =
                      new BufferedWriter(
                          new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
                  w.write("ERROR: common name 'ghost' not found\n");
                  w.flush();
                } catch (IOException ignored) {
                  // test teardown
                }
              });
      daemon.start();

      MgmtClient client = new MgmtClient("127.0.0.1", port, 0);
      assertThat(client.kill("ghost")).isFalse();
      daemon.join(3_000);
      client.close();
    }
  }

  @Test
  void signalAcknowledgesSuccess() throws Exception {
    try (ServerSocket listener = new ServerSocket(0)) {
      int port = listener.getLocalPort();
      Thread daemon =
          new Thread(
              () -> {
                try (Socket s = listener.accept()) {
                  BufferedReader r =
                      new BufferedReader(
                          new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                  String command = r.readLine();
                  BufferedWriter w =
                      new BufferedWriter(
                          new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
                  if ("signal SIGHUP".equals(command)) {
                    w.write("SUCCESS: signal SIGHUP sent\n");
                  } else {
                    w.write("ERROR: unknown command\n");
                  }
                  w.flush();
                } catch (IOException ignored) {
                  // test teardown
                }
              });
      daemon.start();

      MgmtClient client = new MgmtClient("127.0.0.1", port, 0);
      assertThat(client.signal("SIGHUP")).isTrue();
      daemon.join(3_000);
      client.close();
    }
  }

  @Test
  void signalReportsFailureForUnknownSignal() throws Exception {
    try (ServerSocket listener = new ServerSocket(0)) {
      int port = listener.getLocalPort();
      Thread daemon =
          new Thread(
              () -> {
                try (Socket s = listener.accept()) {
                  BufferedReader r =
                      new BufferedReader(
                          new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                  r.readLine();
                  BufferedWriter w =
                      new BufferedWriter(
                          new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
                  w.write("ERROR: signal SIGHUP failed\n");
                  w.flush();
                } catch (IOException ignored) {
                  // test teardown
                }
              });
      daemon.start();

      MgmtClient client = new MgmtClient("127.0.0.1", port, 0);
      assertThat(client.signal("SIGHUP")).isFalse();
      daemon.join(3_000);
      client.close();
    }
  }

  @Test
  void statusReturnsNullWhenUnreachable() {
    // Find a port that is certainly closed.
    int closedPort;
    try (ServerSocket listener = new ServerSocket(0)) {
      closedPort = listener.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException("Cannot find a free port", e);
    }
    MgmtClient client = new MgmtClient("127.0.0.1", closedPort, 0);
    assertThat(client.status()).isNull();
    assertThat(client.isConnected()).isFalse();
    client.close();
  }

  @Test
  void statusDropsStaleConnectionWhenDaemonCloses() throws Exception {
    try (ServerSocket listener = new ServerSocket(0)) {
      int port = listener.getLocalPort();
      Thread daemon =
          new Thread(
              () -> {
                try (Socket s = listener.accept()) {
                  BufferedReader r =
                      new BufferedReader(
                          new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                  r.readLine(); // expect "status 3"
                  // Simulate a daemon restart: close the connection without a reply.
                } catch (IOException ignored) {
                  // test teardown
                }
              });
      daemon.start();

      MgmtClient client = new MgmtClient("127.0.0.1", port, 0);
      assertThat(client.status()).isNull();
      // The stale socket must be dropped so the next poll can reconnect.
      assertThat(client.isConnected()).isFalse();
      daemon.join(3_000);
      client.close();
    }
  }

  @Test
  void killReportsFailureWhenDaemonClosesWithoutReply() throws Exception {
    try (ServerSocket listener = new ServerSocket(0)) {
      int port = listener.getLocalPort();
      Thread daemon =
          new Thread(
              () -> {
                try (Socket s = listener.accept()) {
                  BufferedReader r =
                      new BufferedReader(
                          new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                  r.readLine(); // expect "kill ..."
                  // Daemon restarts without replying.
                } catch (IOException ignored) {
                  // test teardown
                }
              });
      daemon.start();

      MgmtClient client = new MgmtClient("127.0.0.1", port, 0);
      assertThat(client.kill("alice")).isFalse();
      assertThat(client.isConnected()).isFalse();
      daemon.join(3_000);
      client.close();
    }
  }
}
