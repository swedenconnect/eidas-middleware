# Migrate to 3.0.1 with Restricted HSM provider from version 2.x


This is a guide on how to migrate DE middleware with Sweden Connect HSM support from version 2.x to version 3.0.1

## Background

The German middleware application provides native support HSM key storage using PKCS#11 API. 
The native HSM support works quite well, but provides deployment challenges that prevents its use in the Sweden Connect
service infrastructure.
The primary cause is that the HSM is used, not only for pre-generated
and pre-stored keys that are added as part of a secure setup of the service, but also stores and deletes ephemeral keys with 
shorter lifespan that are continuously generated and deleted by the middleware application.

This provides challenges mainly because:

1) It requires that the middleware application has extensive rights to generate and erase arbitrary keys in the HSM. In theory this gives the software complete power over the HSM in a way that partly defeats the protective goals of an HSM, for example if a pre-loaded key is replaced by the application.
2) It makes it very challenging to maintain a test environment using SoftHSM where an image with the middleware is spun up with a preconfigured HSM. Doing so would alter the state of the temporary key storage and bring the middleware out of sync wihth the German eID system, requiring CVC reset each time through Governikus help desk.

For this reason, the Sweden Connect extension to the Middleware application provides a restricted HSM provider that restricts
middleware access to the HSM to just usage of a pre-stored HSM key for SAML signing. All other keys are stored
and maintained in software and/or using disk storage.


## Architecture

The base for setting up a German Middleware application with this restricted HSM provider is to use a standard configuration with soft keys as
the base configuration which includes a soft dummy key for SAML signing that later will be replaced by the HSM key. 
The dummy key provided in this setup is necessary for the middleware application to accept the setup as valid.

**NOTE**: The use of a dummy key for setup before attaching an HSM key to be used in its place allows minimum changes to the code base and
allows HSM configuration to be completed in a completely separate step once the application has successfully configured.

Once the base configuration works and pass all tests, additional configuration properties can be applied to swap in the use of a pre-configured
HSM stored key for SAML signing.

## Migration

The migration is done in 2 steps:

1) Migrate the 2.x configuration to a functioning 3.0.1 configuration
2) Add configuration for the restricted HSM provider for SAML signing


### Migrate configuration from 2.x to 3.0.1

The major change from 2.x to 3.0.1 of the German middleware is that the following configuration files are removed and their 2.x content is 
moved into the H2 database of the service.

1) `POSeIDAS.xml`
2) `eidasmiddleware.properties`

Another major difference is that parameters previously stored in these files are managed and through the admin GUI, 
rather than through editing the configuration files.

This admin GUI is provided via a separate admin port at:

> https://{local-service-host-name}:{admin-port}/admin-interface

The middleware application is available for configuration by starting it with just an `application.properties` file in the configuration folder. 
This `application.properties` file can be a copy of the file used in version 2.x. This folder should not contain the files `POSeIDAS.xml` or
`eidasmiddleware.properties`.

When migrating from version 2.x, there are two options:

1) Rebuild the complete configuration from scratch using the configuration API on the location above. For instruction on how to provide configuration data using the admin GUI, see [eIDASMiddleware.pdf](https://github.com/Governikus/eidas-middleware/releases/download/3.0.1/eIDASMiddleware.pdf)
2) Extract configuration from a version 2.x setup using the configuration migration tool [configuration migration tool](https://github.com/Governikus/eidas-middleware/tree/master/configuration-migration)

Once these steps are completed, the eIDAS middleware application should be operational using software based keys. If the old database
file is being used, the CVC should be in sync and the service should work as intended, except for the fact that SAML signing is made by the
dummy key stored on disk.


### Add configuration for using a Restricted HSM provider for SAML signing

A pre-stored SAML signing key is attached to the eIDAS middleware by adding the following property settings:

| Property               | Value                                                                                                                                                          |
|------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| sc-hsm.p11-config-file | absolute path to a PKCS#11 configuration file (see [PKCS#11 Reference Guide](https://docs.oracle.com/en/java/javase/11/security/pkcs11-reference-guide1.html)) |
| sc-hsm.p11-pin         | HSM slot pin                                                                                                                                                   |
| sc-hsm.p11-alias       | HSM key alias                                                                                                                                                  |

As the middleware application is a Spring Boot application these property values can be added using several strategies. Two examples are shown below:

Example: Properties values

```
logging.level.de.governikus.eumw.poseidas.server.idprovider.config=DEBUG
sc-hsm.p11-config-file=/opt/SoftHsmProvider-p11
sc-hsm.p11-pin=S3cr3t
sc-hsm.p11-alias=demw-sign
```

Example: Docker run environment variables

```
-e "LOGGING_LEVEL_DE_GOVERNIKUS_EUMW_POSEIDAS_SERVER_IDPROVIDER_CONFIG=DEBUG" \
-e "SC_HSM_P11_CONFIG_FILE=/opt/SoftHsmProvider-p11" \
-e "SC_HSM.P11_PIN=${hsm_pin}" \
-e "SC_HSM.P11_ALIAS=${key_alias}" \
```

NOTE: The logging setting included here outputs a DEBUG log event everytime a key is selected from the HSM.

Even without the log setting above, it is possible to detect that the HSM setup is active by observing the following process log message at
startup:

> Initializing Restricted HSM provider ...

