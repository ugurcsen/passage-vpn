package com.passagevpn.internal;

import com.passagevpn.config.InternalProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;

/**
 * Adds the internal control-plane HTTPS connector with mandatory client authentication (mTLS) on
 * {@code opnl.internal.mtls-port} (default 9443). Only the backend itself accepts it; a remote
 * agent's client certificate (CN {@code agent-<nodeName>}) signed by the internal CA is required.
 *
 * <p>The connector is added only when the bootstrap keystore/truststore already exist (i.e. the app
 * was started through {@code main()}, where {@link InternalTlsBootstrap#ensure()} ran). In
 * unit/integration tests the context starts without the bootstrap, so the connector is skipped and
 * the default HTTP server on 8080 stays in charge.
 */
@Configuration
public class TomcatTlsConfig implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

  private final InternalProperties internalProperties;

  public TomcatTlsConfig(InternalProperties internalProperties) {
    this.internalProperties = internalProperties;
  }

  @Override
  public void customize(TomcatServletWebServerFactory factory) {
    Path tlsDir = InternalTlsBootstrap.tlsDir(internalProperties.tlsDir());
    Path keystore = tlsDir.resolve(InternalTlsBootstrap.KEYSTORE);
    Path truststore = tlsDir.resolve(InternalTlsBootstrap.TRUSTSTORE);
    if (!Files.exists(keystore) || !Files.exists(truststore)) {
      return;
    }
    String password = InternalTlsBootstrap.keystorePassword(tlsDir);

    SSLHostConfig sslHostConfig = new SSLHostConfig();
    sslHostConfig.setProtocols("TLSv1.3,TLSv1.2");
    sslHostConfig.setSslProtocol("TLS");
    sslHostConfig.setTruststoreFile(truststore.toAbsolutePath().toString());
    sslHostConfig.setTruststorePassword(password);
    sslHostConfig.setTruststoreType("PKCS12");
    sslHostConfig.setCertificateVerification("required");

    SSLHostConfigCertificate certificate =
        new SSLHostConfigCertificate(sslHostConfig, SSLHostConfigCertificate.Type.RSA);
    certificate.setCertificateKeystoreFile(keystore.toAbsolutePath().toString());
    certificate.setCertificateKeystorePassword(password);
    certificate.setCertificateKeystoreType("PKCS12");
    sslHostConfig.addCertificate(certificate);

    Connector connector = new Connector(Http11NioProtocol.class.getName());
    connector.setPort(internalProperties.mtlsPort());
    connector.setSecure(true);
    connector.setScheme("https");
    // Adding an SSLHostConfig alone does not flip the connector into TLS mode; without this the
    // connector would serve plaintext HTTP on the mTLS port.
    connector.setProperty("SSLEnabled", "true");
    connector.addSslHostConfig(sslHostConfig);
    factory.addAdditionalTomcatConnectors(connector);
  }
}
