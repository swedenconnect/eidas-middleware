package de.governikus.eumw.pkcs11;

import java.security.KeyStore;
import java.security.Provider;
import java.security.Security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import de.governikus.eumw.poseidas.server.idprovider.config.KeyPair;
import lombok.extern.slf4j.Slf4j;

/**
 * Provides a Restricted HSM bean component for a HSM that is restricted
 * to providing a SAML signing key.
 *
 */
@Component
@Slf4j
public class RestrictedHSMProvider {

  private KeyPair signingKeyPair = null;

  public RestrictedHSMProvider(
    @Value("${sc-hsm.p11-config-file:#{null}}") String p11ConfigFile,
    @Value("${sc-hsm.p11-pin:#{null}}") String p11Pin,
    @Value("${sc-hsm.p11-alias:#{null}}") String p11Alias
  ) throws Exception {
    if (p11ConfigFile != null) {
      log.info("Initializing Restricted HSM provider ...");
      initHSMKey(p11ConfigFile, p11Pin, p11Alias);
    } else {
      log.info("No restricted HSM provider configured");
    }
  }

  private void initHSMKey(final String pkcs11ConfigFilePath, String password,
    String alias) throws Exception {
    Provider createdPkcs11Provider = null;
    if (pkcs11ConfigFilePath != null) {
      createdPkcs11Provider = Security.getProvider("SunPKCS11");
      createdPkcs11Provider = createdPkcs11Provider.configure(pkcs11ConfigFilePath);
      Security.addProvider(createdPkcs11Provider);
    }

    KeyStore keyStore = KeyStore.getInstance("PKCS11", createdPkcs11Provider);
    keyStore.load(null, password.toCharArray());
    signingKeyPair = new KeyPair(keyStore, alias, password);

  }

  public KeyPair getHSMSigningKeyPair() {
    return signingKeyPair;
  }

}
