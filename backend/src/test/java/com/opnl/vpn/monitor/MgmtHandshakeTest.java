package com.opnl.vpn.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Exercises the management password handshake against a real fake-daemon socket that mimics
 * OpenVPN: the {@code ENTER PASSWORD:} prompt is sent without a trailing newline and the client
 * must answer with the bare password on a line.
 */
class MgmtHandshakeTest {

  private static final String BANNER =
      ">INFO:OpenVPN Management Interface Version 5 -- type 'help' for more info\r\n";

  private static BufferedReader reader(Socket s) throws IOException {
    return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
  }

  private static BufferedWriter writer(Socket s) throws IOException {
    return new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
  }

  @Test
  void authenticatesWithCorrectPassword() throws Exception {
    int[] portHolder = new int[1];
    AtomicReference<String> received = new AtomicReference<>();
    try (ServerSocket listener = new ServerSocket(0)) {
      portHolder[0] = listener.getLocalPort();
      Thread daemon =
          new Thread(
              () -> {
                try (Socket s = listener.accept()) {
                  BufferedWriter w = writer(s);
                  BufferedReader r = reader(s);
                  w.write("ENTER PASSWORD:");
                  w.flush();
                  String line = r.readLine();
                  received.set(line);
                  w.write("SUCCESS: password is correct\r\n" + BANNER);
                  w.flush();
                } catch (IOException ignored) {
                  // test teardown
                }
              });
      daemon.start();

      MgmtClient client = new MgmtClient("127.0.0.1", portHolder[0], 0, null, "correct-horse");
      assertThat(client.connect()).isTrue();
      daemon.join(3_000);
      assertThat(received.get()).isEqualTo("correct-horse");
      client.close();
    }
  }

  @Test
  void rejectsWrongPassword() throws Exception {
    int[] portHolder = new int[1];
    try (ServerSocket listener = new ServerSocket(0)) {
      portHolder[0] = listener.getLocalPort();
      Thread daemon =
          new Thread(
              () -> {
                try (Socket s = listener.accept()) {
                  BufferedWriter w = writer(s);
                  BufferedReader r = reader(s);
                  w.write("ENTER PASSWORD:");
                  w.flush();
                  r.readLine();
                  w.write("ERROR: bad password\r\n");
                  w.flush();
                } catch (IOException ignored) {
                  // test teardown
                }
              });
      daemon.start();

      MgmtClient client = new MgmtClient("127.0.0.1", portHolder[0], 0, null, "wrong");
      assertThat(client.connect()).isFalse();
      assertThat(client.isConnected()).isFalse();
      daemon.join(3_000);
      client.close();
    }
  }

  @Test
  void failsWhenDaemonRequiresPasswordButNoneConfigured() throws Exception {
    int[] portHolder = new int[1];
    try (ServerSocket listener = new ServerSocket(0)) {
      portHolder[0] = listener.getLocalPort();
      Thread daemon =
          new Thread(
              () -> {
                try (Socket s = listener.accept()) {
                  BufferedWriter w = writer(s);
                  w.write("ENTER PASSWORD:");
                  w.flush();
                } catch (IOException ignored) {
                  // test teardown
                }
              });
      daemon.start();

      MgmtClient client = new MgmtClient("127.0.0.1", portHolder[0], 0);
      assertThat(client.connect()).isFalse();
      daemon.join(3_000);
      client.close();
    }
  }

  @Test
  void failsWhenPasswordConfiguredButDaemonDoesNotPrompt() throws Exception {
    int[] portHolder = new int[1];
    try (ServerSocket listener = new ServerSocket(0)) {
      portHolder[0] = listener.getLocalPort();
      Thread daemon =
          new Thread(
              () -> {
                try (Socket s = listener.accept()) {
                  BufferedWriter w = writer(s);
                  w.write(BANNER);
                  w.flush();
                  Thread.sleep(3_000);
                } catch (IOException | InterruptedException ignored) {
                  // test teardown
                }
              });
      daemon.start();

      MgmtClient client = new MgmtClient("127.0.0.1", portHolder[0], 0, null, "unexpected");
      assertThat(client.connect()).isFalse();
      daemon.join(5_000);
      client.close();
    }
  }

  @Test
  void acceptsPasswordlessDaemonWithoutConfiguredPassword() throws Exception {
    int[] portHolder = new int[1];
    try (ServerSocket listener = new ServerSocket(0)) {
      portHolder[0] = listener.getLocalPort();
      Thread daemon =
          new Thread(
              () -> {
                try (Socket s = listener.accept()) {
                  BufferedWriter w = writer(s);
                  w.write(BANNER);
                  w.flush();
                  Thread.sleep(3_000);
                } catch (IOException | InterruptedException ignored) {
                  // test teardown
                }
              });
      daemon.start();

      MgmtClient client = new MgmtClient("127.0.0.1", portHolder[0], 0);
      assertThat(client.connect()).isTrue();
      daemon.join(5_000);
      client.close();
    }
  }
}
