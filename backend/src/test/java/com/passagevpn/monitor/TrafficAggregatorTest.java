package com.passagevpn.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import com.passagevpn.monitor.MgmtStatus.MgmtClientStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrafficAggregatorTest {

  private static MgmtClientStatus client(
      String commonName, long bytesIn, long bytesOut, Instant at) {
    return new MgmtClientStatus(
        commonName, "203.0.113.5", "10.8.0.2", null, bytesIn, bytesOut, at, 1);
  }

  @Test
  void firstPollRecordsCountersWithZeroRates() {
    TrafficAggregator aggregator = new TrafficAggregator();
    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");

    aggregator.update(List.of(client("alice", 1000, 500, t0)), t0);

    TrafficAggregator.SessionTraffic traffic = aggregator.trafficFor("alice").orElseThrow();
    assertThat(traffic.bytesIn()).isEqualTo(1000);
    assertThat(traffic.bytesOut()).isEqualTo(500);
    assertThat(traffic.bytesInPerSec()).isZero();
    assertThat(traffic.bytesOutPerSec()).isZero();
  }

  @Test
  void computesRatesFromDeltaBetweenPolls() {
    TrafficAggregator aggregator = new TrafficAggregator();
    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    aggregator.update(List.of(client("alice", 1000, 500, t0)), t0);

    aggregator.update(List.of(client("alice", 5000, 2500, t0.plusSeconds(5))), t0.plusSeconds(5));

    TrafficAggregator.SessionTraffic traffic = aggregator.trafficFor("alice").orElseThrow();
    assertThat(traffic.bytesIn()).isEqualTo(5000);
    assertThat(traffic.bytesInPerSec()).isEqualTo(800); // (5000-1000)/5
    assertThat(traffic.bytesOutPerSec()).isEqualTo(400); // (2500-500)/5
  }

  @Test
  void aggregatesTotalsAcrossSessions() {
    TrafficAggregator aggregator = new TrafficAggregator();
    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    aggregator.update(List.of(client("alice", 0, 0, t0), client("bob", 0, 0, t0)), t0);

    aggregator.update(
        List.of(
            client("alice", 4000, 1000, t0.plusSeconds(5)),
            client("bob", 2000, 3000, t0.plusSeconds(5))),
        t0.plusSeconds(5));

    List<TrafficAggregator.TrafficPoint> history = aggregator.history();
    TrafficAggregator.TrafficPoint last = history.get(history.size() - 1);
    assertThat(last.bytesInPerSec()).isEqualTo(1200); // (4000+2000)/5
    assertThat(last.bytesOutPerSec()).isEqualTo(800); // (1000+3000)/5
    assertThat(last.activeConnections()).isEqualTo(2);
  }

  @Test
  void prunesSessionsNoLongerReported() {
    TrafficAggregator aggregator = new TrafficAggregator();
    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    aggregator.update(List.of(client("alice", 1000, 500, t0)), t0);

    aggregator.update(List.of(), t0.plusSeconds(31));

    assertThat(aggregator.trafficFor("alice")).isEmpty();
  }

  @Test
  void identicalConsecutivePollsDoNotGrowHistory() {
    TrafficAggregator aggregator = new TrafficAggregator();
    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    aggregator.update(List.of(), t0);
    aggregator.update(List.of(), t0.plusSeconds(5));
    aggregator.update(List.of(), t0.plusSeconds(10));

    assertThat(aggregator.history()).hasSize(1);
  }

  @Test
  void historyIsCappedAtMaxPoints() {
    TrafficAggregator aggregator = new TrafficAggregator();
    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    for (int i = 0; i < 730; i++) {
      // Distinct rates each poll so the sparse ring still fills to its cap.
      aggregator.update(
          List.of(client("alice", (long) i * i * 100, (long) i * i * 50, t0.plusSeconds(i))),
          t0.plusSeconds(i));
    }
    assertThat(aggregator.history()).hasSize(720);
  }
}
