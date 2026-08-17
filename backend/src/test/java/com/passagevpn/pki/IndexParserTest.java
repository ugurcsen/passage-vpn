package com.passagevpn.pki;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndexParserTest {

  private final IndexParser parser = new IndexParser();

  private static final String SAMPLE =
      """
            V	270101000000Z		2A01	unknown	/CN=user-one
            V	280601000000Z		2A02	unknown	/CN=C5E6E1
            R	270101000000Z	271201000000Z	2A03	unknown	/CN=revoked-user
            """;

  @Test
  void parsesRows() {
    List<CertIndexEntry> entries = parser.parse(SAMPLE);
    assertThat(entries).hasSize(3);
    assertThat(entries.get(0).status()).isEqualTo(CertIndexEntry.Status.VALID);
    assertThat(entries.get(0).serial()).isEqualTo("2A01");
    assertThat(entries.get(0).expiry()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
    assertThat(entries.get(1).commonName()).isEqualTo("C5E6E1");
    assertThat(entries.get(2).status()).isEqualTo(CertIndexEntry.Status.REVOKED);
    assertThat(entries.get(2).serial()).isEqualTo("2A03");
    assertThat(entries.get(2).commonName()).isEqualTo("revoked-user");
  }

  @Test
  void parsesLegacyFiveColumnRows() {
    List<CertIndexEntry> entries =
        parser.parse("V\t280601000000Z\t2A04\tlegacy.crt\tlegacy-user\n");
    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).serial()).isEqualTo("2A04");
    assertThat(entries.get(0).commonName()).isEqualTo("legacy-user");
  }

  @Test
  void ignoresBlankAndComments() {
    assertThat(parser.parse("")).isEmpty();
    assertThat(parser.parse("# comment\n\n")).isEmpty();
    assertThat(parser.parse("malformed line without tabs")).isEmpty();
  }
}
