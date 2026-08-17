package com.passagevpn.system;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Runs {@code openvpn --config <file> --dev null --route-noexec --ifconfig-noexec --verb 0} so
 * every directive is parsed without opening a real tun or touching routes. An {@code Options error}
 * means the config is broken (FAIL); a process that survives the window or exits cleanly parses
 * fine (PASS). Any other non-zero exit is a runtime/environment issue and reported as WARN so a
 * healthy config is never blocked on a false positive.
 */
@Slf4j
@Component
public class OpenvpnConfigSmokeTester implements ConfigSmokeTester {

  private static final long TIMEOUT_SECONDS = 3;

  @Override
  public Result test(Path configPath) {
    List<String> command =
        List.of(
            "openvpn",
            "--config",
            configPath.toString(),
            "--dev",
            "null",
            "--route-noexec",
            "--ifconfig-noexec",
            "--verb",
            "0");
    try {
      ProcessBuilder builder = new ProcessBuilder(command);
      builder.redirectErrorStream(true);
      Process process = builder.start();
      AtomicReference<String> output = new AtomicReference<>("");
      Thread reader =
          new Thread(
              () -> output.set(readAll(process)), "openvpn-smoke-" + configPath.getFileName());
      reader.setDaemon(true);
      reader.start();
      try {
        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
          process.destroy();
          return new Result(Result.Status.PASS, "Config parsed; daemon was ready to start");
        }
        int exit = process.exitValue();
        if (exit == 0) {
          return new Result(Result.Status.PASS, "Config accepted (exit 0)");
        }
        String text = output.get();
        String optionsError = firstLineContaining(text, "Options error");
        if (optionsError != null) {
          return new Result(Result.Status.FAIL, optionsError);
        }
        return new Result(
            Result.Status.WARN, "Process exited " + exit + ": " + lastNonBlankLine(text));
      } finally {
        process.destroy();
        reader.interrupt();
      }
    } catch (IOException e) {
      return new Result(Result.Status.WARN, "Cannot run openvpn smoke test: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Result(Result.Status.WARN, "Smoke test interrupted");
    }
  }

  private static String readAll(Process process) {
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        sb.append(line).append('\n');
      }
      return sb.toString();
    } catch (IOException e) {
      return "";
    }
  }

  private static String firstLineContaining(String text, String needle) {
    for (String line : text.split("\\n")) {
      if (line.contains(needle)) {
        return line.trim();
      }
    }
    return null;
  }

  private static String lastNonBlankLine(String text) {
    String[] lines = text.split("\\n");
    for (int i = lines.length - 1; i >= 0; i--) {
      if (!lines[i].isBlank()) {
        return lines[i].trim();
      }
    }
    return "no output";
  }
}
