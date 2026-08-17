package com.passagevpn.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.passagevpn.config.InternalProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;

class TomcatTlsConfigTest {

  @TempDir Path tempDir;

  private void writeTlsMaterial(Path tlsDir) throws Exception {
    Files.createDirectories(tlsDir);
    Files.writeString(tlsDir.resolve(InternalTlsBootstrap.KEYSTORE), "keystore");
    Files.writeString(tlsDir.resolve(InternalTlsBootstrap.TRUSTSTORE), "truststore");
    Files.writeString(tlsDir.resolve(InternalTlsBootstrap.KEYSTORE_PASS_FILE), "secret-pass\n");
  }

  @Test
  void addsMtlsConnectorWithSslEnabledWhenMaterialExists() throws Exception {
    Path tlsDir = tempDir.resolve("internal-tls");
    writeTlsMaterial(tlsDir);

    TomcatTlsConfig config = new TomcatTlsConfig(new InternalProperties(9443, tlsDir.toString()));
    TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();

    config.customize(factory);

    assertThat(factory.getAdditionalTomcatConnectors()).hasSize(1);
    Connector connector = factory.getAdditionalTomcatConnectors().get(0);
    assertThat(connector.getPort()).isEqualTo(9443);
    assertThat(connector.getSecure()).isTrue();
    assertThat(connector.getScheme()).isEqualTo("https");
    assertThat(connector.getProperty("SSLEnabled")).isEqualTo(Boolean.TRUE);
  }

  @Test
  void skipsConnectorWhenTlsMaterialMissing() {
    TomcatTlsConfig config =
        new TomcatTlsConfig(new InternalProperties(9443, tempDir.resolve("absent").toString()));
    TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();

    config.customize(factory);

    assertThat(factory.getAdditionalTomcatConnectors()).isEmpty();
  }
}
