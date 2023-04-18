/*
 * Copyright (c) 2021 Governikus KG. Licensed under the EUPL, Version 1.2 or as soon they will be approved by the
 * European Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work except in compliance
 * with the Licence. You may obtain a copy of the Licence at: http://joinup.ec.europa.eu/software/page/eupl Unless
 * required by applicable law or agreed to in writing, software distributed under the Licence is distributed on an
 * "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the Licence for the
 * specific language governing permissions and limitations under the Licence.
 */

package de.governikus.eumw.poseidas.server.idprovider.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.opensaml.core.config.InitializationException;
import org.opensaml.core.xml.io.UnmarshallingException;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import de.governikus.eumw.config.CertificateType;
import de.governikus.eumw.config.ConnectorMetadataType;
import de.governikus.eumw.config.DvcaConfigurationType;
import de.governikus.eumw.config.EidasMiddlewareConfig;
import de.governikus.eumw.config.KeyPairType;
import de.governikus.eumw.config.ServiceProviderType;
import de.governikus.eumw.eidascommon.ContextPaths;
import de.governikus.eumw.eidascommon.ErrorCodeException;
import de.governikus.eumw.eidasmiddleware.eid.RequestingServiceProvider;
import de.governikus.eumw.eidasstarterkit.EidasMetadataNode;
import de.governikus.eumw.eidasstarterkit.EidasSaml;
import de.governikus.eumw.utils.key.KeyReader;
import de.governikus.eumw.utils.key.KeyStoreSupporter;
import de.governikus.eumw.utils.xml.XmlException;
import de.governikus.eumw.utils.xml.XmlHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.shibboleth.utilities.java.support.component.ComponentInitializationException;
import net.shibboleth.utilities.java.support.xml.XMLParserException;


/**
 * This class is a service providing methods to load and save the {@link EidasMiddlewareConfig}
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConfigurationService
{

  private static final long CONFIGURATION_ID = 1L;

  private final ConfigurationRepository configurationRepository;

  /**
   * Get the current configuration from the database
   *
   * @return The current configuration from the database if present or <code>Optional.empty()</code>
   */
  public Optional<EidasMiddlewareConfig> getConfiguration()
  {
    var optionalConfiguration = configurationRepository.findById(CONFIGURATION_ID);

    if (optionalConfiguration.isEmpty() || optionalConfiguration.get().getXmlConfigBlob() == null)
    {
      log.debug("No configuration stored in the database");
      return Optional.empty();
    }

    String configurationData = new String(optionalConfiguration.get().getXmlConfigBlob(), StandardCharsets.UTF_8);
    try
    {
      var eidasMiddlewareConfig = XmlHelper.unmarshal(configurationData, EidasMiddlewareConfig.class);
      log.trace("Read configuration from database:\n{}", configurationData);
      return Optional.of(eidasMiddlewareConfig);
    }
    catch (XmlException e)
    {
      log.debug("Cannot unmarshal configuration from database", e);
      return Optional.empty();
    }
  }

  /**
   * This method returns the current configuration without any keys. If no configuration is present this might be empty.
   *
   * @return Optional<EidasMiddlewareConfig>
   */
  public Optional<EidasMiddlewareConfig> downloadConfigWithoutKeys()
  {
    Optional<EidasMiddlewareConfig> configuration = getConfiguration();
    if (configuration.isEmpty())
    {
      return configuration;
    }
    EidasMiddlewareConfig eidasMiddlewareConfig = configuration.get();
    eidasMiddlewareConfig.getKeyData().getKeyPair().forEach(kp -> kp.setPassword(null));
    eidasMiddlewareConfig.getKeyData().getKeyStore().forEach(ks -> {
      ks.setKeyStore(null);
      ks.setPassword(null);
    });
    return Optional.of(eidasMiddlewareConfig);
  }

  /**
   * Save the configuration as an XML string. The XML string may be validated before saving.
   *
   * @param config The configuration to be saved as an XML string
   * @param verify If true, the configuration will be validated
   * @return The saved configuration, if saving was successful
   * @throws XmlException if the configuration could not be validated
   */
  public String saveConfiguration(String config, boolean verify)
  {
    if (verify)
    {
      XmlHelper.validateWithSchema(config,
                                   ConfigurationService.class.getResource("/configuration/eumw-configuration.xsd"));
    }
    var entity = new Configuration();
    entity.setId(CONFIGURATION_ID);
    entity.setXmlConfigBlob(config.getBytes(StandardCharsets.UTF_8));
    return new String(configurationRepository.save(entity).getXmlConfigBlob(), StandardCharsets.UTF_8);
  }

  /**
   * Save the configuration. The configuration may be validated before saving.
   *
   * @param config The configuration to be saved
   * @return The saved configuration, if saving was successful
   * @throws XmlException if the configuration could not be validated
   */
  public EidasMiddlewareConfig saveConfiguration(EidasMiddlewareConfig config, boolean verify)
  {
    var configuration = saveConfiguration(XmlHelper.marshalObject(config), verify);
    return XmlHelper.unmarshal(configuration, EidasMiddlewareConfig.class);
  }

  /**
   * Return the value for server URL with the eidas context path
   *
   * @return The URL with the eidas context path or <code>Optional.empty()</code> if the configuration is empty, is
   *         missing the server URL or contains an invalid server URL.
   */
  public String getServerURLWithEidasContextPath()
  {
    var configuration = getConfiguration();
    if (configuration.isEmpty() || StringUtils.isBlank(configuration.get().getServerUrl()))
    {
      throw new ConfigurationException("Configuration is empty or does not contain a server URL");
    }

    try
    {
      var uriComponentsBuilder = UriComponentsBuilder.fromHttpUrl(configuration.get().getServerUrl());
      return uriComponentsBuilder.path(ContextPaths.EIDAS_CONTEXT_PATH).build().toUriString();
    }
    catch (Exception e)
    {
      throw new ConfigurationException("Cannot create the Server URL with the eIDAS context path", e);
    }
  }

  /**
   * Get a wrapper for the initialized {@link KeyStore} with the given key pair name
   *
   * @param keyPairName The name for this key pair
   * @return The key pair wrapped in a {@link KeyPair} class to provide easier access to the
   *         {@link java.security.PrivateKey} and {@link java.security.cert.Certificate}
   */
  public KeyPair getKeyPair(String keyPairName)
  {
    var configuration = getConfiguration();
    if (configuration.isEmpty())
    {
      throw new ConfigurationException("No configuration present");
    }

    try
    {
      var keyPairType = configuration.get()
                                     .getKeyData()
                                     .getKeyPair()
                                     .stream()
                                     .filter(k -> k.getName().equals(keyPairName))
                                     .findFirst()
                                     .orElseThrow(() -> new ConfigurationException("No key pair available with name "
                                                                                   + keyPairName));

      return configuration.get()
                          .getKeyData()
                          .getKeyStore()
                          .stream()
                          .filter(k -> k.getName().equals(keyPairType.getKeyStoreName()))
                          .findFirst()
                          .map(keyStoreType -> new KeyPair(KeyStoreSupporter.readKeyStore(keyStoreType.getKeyStore(),
                                                                                          KeyStoreSupporter.KeyStoreType.valueOf(keyStoreType.getType()
                                                                                                                                             .value()),
                                                                                          keyStoreType.getPassword()),
                                                           keyPairType.getAlias(), keyPairType.getPassword()))
                          .orElseThrow(() -> new ConfigurationException("No key store available with name "
                                                                        + keyPairName));
    }
    catch (Exception e)
    {
      throw new ConfigurationException("Cannot get the key pair from the configuration with the key pair name "
                                       + keyPairName, e);
    }
  }

  /**
   * Get the certificate with the given name
   */
  public X509Certificate getCertificate(String certificateName)
  {
    var configuration = getConfiguration();
    if (configuration.isEmpty())
    {
      throw new ConfigurationException("No configuration present");
    }

    return configuration.get()
                        .getKeyData()
                        .getCertificate()
                        .stream()
                        .filter(c -> certificateName.equals(c.getName()))
                        .findFirst()
                        .map(c -> KeyReader.readX509Certificate(c.getCertificate()))
                        .orElseThrow(() -> new ConfigurationException("No certificate available with name "
                                                                      + certificateName));
  }

  /**
   * Get the service provider based on the entityID from the configuration. The metadata files of the connectors are
   * parsed and validated and the service provider matching the entityID is returned. Throws a
   * {@link ConfigurationException} if no provider was found with the given entityID.
   *
   * @param entityID The entityID of the service provider to be returned
   * @return The {@link RequestingServiceProvider} representation of the service provider
   */
  public RequestingServiceProvider getProviderByEntityID(String entityID)
  {
    var metadataSignatureVerificationCertificateName = getConfiguration().map(EidasMiddlewareConfig::getEidasConfiguration)
                                                                         .map(EidasMiddlewareConfig.EidasConfiguration::getMetadataSignatureVerificationCertificateName)
                                                                         .orElse(null);
    if (StringUtils.isBlank(metadataSignatureVerificationCertificateName))
    {
      throw new ConfigurationException("No metadata verification certificate present in the configuration");
    }

    var metadataSignatureVerificationCertificate = getCertificate(metadataSignatureVerificationCertificateName);

    ConnectorMetadataType metadata = getConfiguration().orElseThrow(() -> new ConfigurationException("No configuration present"))
                                                       .getEidasConfiguration()
                                                       .getConnectorMetadata()
                                                       .stream()
                                                       .filter(meta -> entityID.equals(meta.getEntityID()))
                                                       .findFirst()
                                                       .orElseThrow(() -> new ConfigurationException("No connector metadata available with entityID "
                                                                                                     + entityID));
    try (ByteArrayInputStream is = new ByteArrayInputStream(metadata.getValue()))
    {
      EidasMetadataNode parsedMetadata = EidasSaml.parseMetaDataNode(is, metadataSignatureVerificationCertificate);
      RequestingServiceProvider rsp = new RequestingServiceProvider(parsedMetadata.getEntityId());
      rsp.setAssertionConsumerURL(parsedMetadata.getPostEndpoint());
      rsp.setEncryptionCert(parsedMetadata.getEncCert());
      rsp.setSignatureCert(parsedMetadata.getSigCert());
      rsp.setSectorType(parsedMetadata.getSpType());
      return rsp;
    }
    catch (IOException | CertificateException | XMLParserException | UnmarshallingException | InitializationException
      | ComponentInitializationException | ErrorCodeException e)
    {
      log.trace("Cannot parse metadata file", e);
      throw new ConfigurationException("No valid connector metadata available with entityID " + entityID, e);
    }
  }

  /**
   * Get the DVCA configuration for a service provider
   *
   * @param serviceProvider the servcie provider for which a dvca configuration should fetch
   * @return the dvca configuration for the service provider
   * @throws ConfigurationException if the DVCA configuration could not be found
   */
  public DvcaConfigurationType getDvcaConfiguration(ServiceProviderType serviceProvider)
  {
    return getConfiguration().orElseThrow(() -> new ConfigurationException("No configuration present"))
                             .getEidConfiguration()
                             .getDvcaConfiguration()
                             .stream()
                             .filter(dvcaConfigurationType -> dvcaConfigurationType.getName()
                                                                                   .equals(serviceProvider.getDvcaConfigurationName()))
                             .findFirst()
                             .orElseThrow(() -> new ConfigurationException("No dvca configuration present"));
  }

  /**
   * Get all configured keypairs
   *
   * @return a list containing all configured keypairs
   */
  public List<KeyPairType> getKeyPairTypes()
  {
    return getConfiguration().map(EidasMiddlewareConfig::getKeyData)
                             .map(EidasMiddlewareConfig.KeyData::getKeyPair)
                             .orElse(List.of());
  }

  /**
   * Get all configured certificates
   *
   * @return a list containing all configured certificates
   */
  public List<CertificateType> getCertificateTypes()
  {
    return getConfiguration().map(EidasMiddlewareConfig::getKeyData)
                             .map(EidasMiddlewareConfig.KeyData::getCertificate)
                             .orElse(List.of());
  }
}
