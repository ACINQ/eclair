{
  pkgs ? null
}:
let nixpkgs = import ./nixpkgs.nix;
    overlays = [];
    pkgs' = if pkgs == null
            then import nixpkgs {inherit overlays;}
            else pkgs;
    haskell-ide = import (
      fetchTarball "https://github.com/tim2CF/ultimate-haskell-ide/tarball/master"
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
