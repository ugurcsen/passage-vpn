package com.opnl.vpn.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.stereotype.Component;

/**
 * Safe subprocess runner used for shelling out to Easy-RSA, OpenSSL and other host tools. Enforces
 * timeouts, captures stdout/stderr, and exposes both the exit code and output so callers can parse
 * tool output (e.g. index.txt).
 */
@Slf4j
@Component
public class ProcessRunner {

  public record Result(int exitCode, String stdout, String stderr) {
    public boolean ok() {
      return exitCode == 0;
    }
  }

  public Result run(List<String> command) {
    return run(command, Map.of(), Duration.ofMinutes(2));
  }

  public Result run(List<String> command, Map<String, String> environment) {
    return run(command, environment, Duration.ofMinutes(2));
  }

  public Result run(List<String> command, Map<String, String> environment, Duration timeout) {
    try {
      ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(command));
      pb.environment().putAll(environment);
      Process process = pb.start();

      // Drain stdout/stderr concurrently to avoid deadlock on full pipes.
      List<String> stdout = new ArrayList<>();
      List<String> stderr = new ArrayList<>();
      Thread outThread =
          Thread.startVirtualThread(
              () ->
                  IOUtils.lineIterator(process.getInputStream(), StandardCharsets.UTF_8)
                      .forEachRemaining(stdout::add));
      Thread errThread =
          Thread.startVirtualThread(
              () ->
                  IOUtils.lineIterator(process.getErrorStream(), StandardCharsets.UTF_8)
                      .forEachRemaining(stderr::add));

      boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        process.destroyForcibly();
        throw ApiException.internal(
            "process_timeout",
            "Command timed out after " + timeout + ": " + String.join(" ", command));
      }
      outThread.join(timeout.toMillis());
      errThread.join(timeout.toMillis());

      String out = String.join("\n", stdout);
      String err = String.join("\n", stderr);
      log.debug(
          "cmd={} exit={} out={} err={}", String.join(" ", command), process.exitValue(), out, err);
      return new Result(process.exitValue(), out, err);
    } catch (IOException e) {
      throw ApiException.internal("process_io", "Failed to run command: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ApiException.internal("process_interrupted", "Command interrupted");
    }
  }
}
