package com.opnl.vpn.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.cert.X509Certificate;
import javax.security.auth.x500.X500Principal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientCertReaderTest {

  private static final String CERT_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

  private final ClientCertReader reader = new ClientCertReader();

  private static final String SELF_SIGNED_PEM =
      """
      -----BEGIN CERTIFICATE-----
      MIIDJzCCAg+gAwIBAgIUGEMdRuMJq3zIjeJiScWpXSdGn6swDQYJKoZIhvcNAQEL
      BQAwIzEOMAwGA1UEAwwFYWxpY2UxETAPBgNVBAoMCFRlc3QgT3JnMB4XDTI2MDgx
      NTE5MjMyOFoXDTM2MDgxMjE5MjMyOFowIzEOMAwGA1UEAwwFYWxpY2UxETAPBgNV
      BAoMCFRlc3QgT3JnMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEArmvR
      N/cTAo/G3Wyk0PzZiUA8abtkjCCjgSNbCVRUWkiIdAFgIHXwFZQEm0t5dLavd0z1
      6HgR0cNsGRC+2II6Dme6qcZZ1rM3kef3aPmbcS9RQeNNI/vBxC/BAXvrZ0izwzhm
      Y28p9CtF9xAYnlcJFHDCPhjytIbtzBs5A+qehFbZH/s0tK87+s9L43xl8iF/GhIG
      h+lu93PaCfAqbxWi4c0mhE6udRFtzWFBEoClS3qtkhpUtjmbO17DnIsRsA1qTRct
      V4qPm/4UaSDaTRrrER9BTGYf7rCJwNurFja9hx2FqdAk2PlbFq7j7+/3njKLna0T
      ESZUga5OO82q4gjV/wIDAQABo1MwUTAdBgNVHQ4EFgQUSinseXd8tVMDkfnXbWY0
      avUqqBYwHwYDVR0jBBgwFoAUSinseXd8tVMDkfnXbWY0avUqqBYwDwYDVR0TAQH/
      BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEAAfyuGDRUi1D1Ese+1ZhajH/rycHS
      OvwUphXBxGJ5y/9Gnvryc7JApvhbqE80Q3rE74RxQgiYeIHjcY/yZSYAW4LNS3eI
      uf6pTW2W7MCboZXYP90Y913mZ5TaJDtzqJS20kxFNED+V8ecmWUdLJ2CSYe17ujO
      gf8w0f/pCgY4Y8nbB32A7lbdIHgFnviUIx5MtUHLl74j1QDcansn+dYDOQRZY1It
      XuYoxbr4D9bvOvqQDpPX7faD3vxpmwzwRrNMClw1KicfPaNxwyHJRUEoeZD9zel2
      EHiFsra8kzKg+YGD+goQVrJgO4hg1HsEmnbYqVCdmCB5cYMkCQLD5JSscA==
      -----END CERTIFICATE-----
      """;

  private X509Certificate certificate(String subjectDn) {
    X509Certificate cert = mock(X509Certificate.class);
    when(cert.getSubjectX500Principal()).thenReturn(new X500Principal(subjectDn));
    return cert;
  }

  private MockHttpServletRequest requestWith(X509Certificate... chain) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(CERT_ATTRIBUTE, chain);
    return request;
  }

  @Test
  void subjectCnReturnsPeerCertificateCn() {
    assertThat(reader.subjectCn(requestWith(certificate("CN=alice,O=Test Org"))))
        .isEqualTo("alice");
  }

  @Test
  void subjectCnFindsCnWhenItIsNotTheFirstAttribute() {
    assertThat(reader.subjectCn(requestWith(certificate("O=Test Org,CN=bob")))).isEqualTo("bob");
  }

  @Test
  void subjectCnReturnsNullWhenAttributeMissing() {
    assertThat(reader.subjectCn(new MockHttpServletRequest())).isNull();
  }

  @Test
  void subjectCnReturnsNullWhenChainEmpty() {
    assertThat(reader.subjectCn(requestWith())).isNull();
  }

  @Test
  void subjectCnReturnsNullWhenAttributeIsNotCertificateArray() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(CERT_ATTRIBUTE, "not-a-chain");
    assertThat(reader.subjectCn(request)).isNull();
  }

  @Test
  void subjectCnReturnsNullWhenDnHasNoCn() {
    assertThat(reader.subjectCn(requestWith(certificate("O=Test Org,C=US")))).isNull();
  }

  @Test
  void subjectCnTrimsWhitespaceAroundDnParts() {
    X500Principal principal = mock(X500Principal.class);
    when(principal.getName()).thenReturn(" CN=alice , O=Test Org");
    X509Certificate cert = mock(X509Certificate.class);
    when(cert.getSubjectX500Principal()).thenReturn(principal);

    assertThat(reader.subjectCn(requestWith(cert))).isEqualTo("alice");
  }

  @Test
  void parsePemParsesSelfSignedCertificate() {
    X509Certificate cert = reader.parsePem(SELF_SIGNED_PEM);
    assertThat(cert.getSubjectX500Principal().getName()).isEqualTo("O=Test Org,CN=alice");
    assertThat(reader.subjectCn(requestWith(cert))).isEqualTo("alice");
  }

  @Test
  void parsePemRejectsInvalidInput() {
    assertThatThrownBy(() -> reader.parsePem("this is not a certificate"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
