FROM eclipse-temurin:21-jdk-alpine as BUILD

# Let's fetch eclair dependencies, so that Docker can cache them
# This way we won't have to fetch dependencies again if only the source code changes
# The easiest way to reliably get dependencies is to build the project with no sources
WORKDIR /usr/src
COPY mvnw mvnw
COPY .mvn .mvn
COPY pom.xml pom.xml
COPY eclair-core/pom.xml eclair-core/pom.xml
COPY eclair-front/pom.xml eclair-front/pom.xml
COPY eclair-node/pom.xml eclair-node/pom.xml
COPY eclair-node/modules/assembly.xml eclair-node/modules/assembly.xml
RUN mkdir -p eclair-core/src/main/scala && touch eclair-core/src/main/scala/empty.scala
# Blank build. We only care about eclair-node, and we use install because eclair-node depends on eclair-core
RUN ./mvnw install -pl eclair-node -am
RUN ./mvnw clean

# Only then do we copy the sources
COPY . .

# And this time we can build in offline mode, specifying 'notag' instead of git commit
RUN ./mvnw package -pl eclair-node -am -DskipTests -Dgit.commit.id=notag -Dgit.commit.id.abbrev=notag -o
# It might be good idea to run the tests here, so that the docker build fail if the code is bugged

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# install jq for eclair-cli
RUN apk add bash jq curl unzip

# copy and install eclair-cli executable
COPY --from=BUILD /usr/src/eclair-core/eclair-cli .
RUN chmod +x eclair-cli && mv eclair-cli /sbin/eclair-cli

# we only need the eclair-node.zip to run
COPY --from=BUILD /usr/src/eclair-node/target/eclair-node-*.zip ./eclair-node.zip
RUN unzip eclair-node.zip && mv eclair-node-* eclair-node && chmod +x eclair-node/bin/eclair-node.sh

ENV ECLAIR_DATADIR=/data
ENV JAVA_OPTS=

RUN mkdir -p "$ECLAIR_DATADIR"
VOLUME [ "/data" ]

ENTRYPOINT JAVA_OPTS="${JAVA_OPTS}" eclair-node/bin/eclair-node.sh "-Declair.datadir=${ECLAIR_DATADIR}"
