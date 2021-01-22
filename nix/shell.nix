{
  pkgs ? null
}:
let nixpkgs = import ./nixpkgs.nix;
    overlays = [];
    pkgs' = if pkgs == null
            then import nixpkgs {inherit overlays;}
            else pkgs;
    haskell-ide = import (
      fetchTarball "https://github.com/tim2CF/ultimate-haskell-ide/tarball/54af8e0483b38457bf067086c3f214e90cb2e0f1"
    ) {};
    mavenix = import (
      fetchTarball "https://github.com/nix-community/mavenix/tarball/master"
    ) {};
in
with pkgs';

stdenv.mkDerivation {
  name = "eclair-env";
  buildInputs = [
    # ide
    haskell-ide
    # java
    jdk11
    maven
    mavenix.cli
    # test deps
    bitcoin
    docker
    # utils
    nix-prefetch-scripts
    openssh
    cacert
    unzip
    perl
    git
    jq
  ];

  TERM="xterm-256color";
  GIT_SSL_CAINFO="${cacert}/etc/ssl/certs/ca-bundle.crt";
  NIX_SSL_CERT_FILE="${cacert}/etc/ssl/certs/ca-bundle.crt";
  NIX_PATH="/nix/var/nix/profiles/per-user/root/channels";
}
