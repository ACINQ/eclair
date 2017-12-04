FROM openjdk:8u121-jdk-alpine as BUILD

# Setup maven, we don't use https://hub.docker.com/_/maven/ as it declare .m2 as volume, we loose all mvn cache
# We can alternatively do as proposed by https://github.com/carlossg/docker-maven#packaging-a-local-repository-with-the-image
# this was meant to make the image smaller, but we use multi-stage build so we don't care

# We can remove dependency on git by setting <failOnNoGitDirectory>false</failOnNoGitDirectory> is set on git-commit-id-plugin
RUN apk add --no-cache curl tar bash git

ARG MAVEN_VERSION=3.5.2
ARG USER_HOME_DIR="/root"
ARG SHA=707b1f6e390a65bde4af4cdaf2a24d45fc19a6ded00fff02e91626e3e42ceaff
ARG BASE_URL=https://apache.osuosl.org/maven/maven-3/${MAVEN_VERSION}/binaries

RUN mkdir -p /usr/share/maven /usr/share/maven/ref \
  && curl -fsSL -o /tmp/apache-maven.tar.gz ${BASE_URL}/apache-maven-${MAVEN_VERSION}-bin.tar.gz \
  && echo "${SHA}  /tmp/apache-maven.tar.gz" | sha256sum -c - \
  && tar -xzf /tmp/apache-maven.tar.gz -C /usr/share/maven --strip-components=1 \
  && rm -f /tmp/apache-maven.tar.gz \
  && ln -s /usr/share/maven/bin/mvn /usr/bin/mvn

ENV MAVEN_HOME /usr/share/maven
ENV MAVEN_CONFIG "$USER_HOME_DIR/.m2"

# Let's fetch eclair dependencies by copying and running poms without sources first. 
# Docker will not have to fetch dependencies again if only the source code change
WORKDIR /usr/src
COPY pom.xml pom.xml
COPY eclair-core/pom.xml eclair-core/pom.xml
COPY eclair-node/pom.xml eclair-node/pom.xml
COPY eclair-node-gui/pom.xml eclair-node-gui/pom.xml

# We can remove dependency on git by setting <failOnNoGitDirectory>false</failOnNoGitDirectory> is set on git-commit-id-plugin
RUN git init && \
    git config  user.email "you@example.com" && \
    git config user.name "Your Name" && \
    git commit --allow-empty-message -m "" --allow-empty && \
    # Get offline dependencies
    mvn dependency:go-offline --fail-never && \
    # For some reasons mvn miss dependencies which can be downloaded automatically if there is something to build
    mkdir eclair-core/src && mkdir eclair-core/src/main && mkdir eclair-core/src/main/scala && touch eclair-core/src/main/scala/dummy.scala && \
    mvn install -DskipTests -f eclair-core/pom.xml --fail-never && \
    rm -rf eclair-core/src && mvn clean && \
    # Same stuff with eclair-node
    mkdir eclair-node/src && mkdir eclair-node/src/main && mkdir eclair-node/src/main/scala && touch eclair-node/src/main/scala/dummy.scala && \
    mvn install -DskipTests -f eclair-node/pom.xml --fail-never && \
    rm -rf eclair-node/src && mvn clean && \
    # For some reasons, this fetch more dependencies
    mvn package -pl eclair-node -am -DskipTests && mvn clean && \
    rm -rf .git

# Phew, we have all the dependencies in local now. We can now copy sources and build offline
COPY . .
RUN mvn package -pl eclair-node -am -DskipTests -o
# It might be good idea to run the tests here, so that the docker build fail if the code is bugged

# Remove artifact unecessary to run
RUN cd eclair-node/target && ls -1 | grep -v 'node-'  | xargs rm -rf

# We are only interested into the target for running eclair
FROM openjdk:8u151-jre-slim
WORKDIR /app
COPY --from=BUILD /usr/src/eclair-node/target .
RUN ln `ls` eclair-node
ENTRYPOINT [ "java", "-jar", "eclair-node" ]