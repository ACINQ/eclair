#!/bin/sh

set -e

rm -rf ./project-info.json
rm -rf ./nix/project-info.json

#
# we need to run mvn2nix against empty directory
# because of this issue
# https://github.com/NixOS/mvn2nix-maven-plugin/issues/5
#
mvn -Dmaven.repo.local=$(mktemp -d -t maven.XXXXXXXXXX) \
  org.nixos.mvn2nix:mvn2nix-maven-plugin:mvn2nix

#
# need to modify output because of this
# https://ww.telent.net/2017/5/10/building_maven_packages_with_nix
#
cat ./project-info.json | jq . | \
  perl -pe 's,https://repo1.maven.org/maven2//,https://repo.maven.org/maven2/,g' > $$ \
    && mv $$ ./nix/project-info.json

rm -rf ./project-info.json
