FROM openjdk:17-jdk-slim
#FROM docker.sunet.se/openjdk-jre-luna:luna7.4-jre11
#FROM docker.sunet.se/openjdk-jre-luna:ubuntu-luna7.4-jre11

# Define the spring boot configuration directory
ENV CONFIG_DIR=/opt/eidas-middleware/configuration

# Define the location of the configuration directory inside of the container
ENV SPRING_CONFIG_ADDITIONAL_LOCATION=file:${CONFIG_DIR}/

# Expose the ports we're interested in
EXPOSE 8443
EXPOSE 10000

RUN mkdir -p /opt/eidas-middleware
WORKDIR /opt/eidas-middleware

# Add built eidas middleware application
ADD eidas-middleware/target/eidas-middleware.jar eidas-middleware.jar
RUN mkdir -p ${CONFIG_DIR}

#ENTRYPOINT ["java", "-jar", "./eidas-middleware.jar"]
ENTRYPOINT ["java","-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:8218","-jar","eidas-middleware.jar"]
