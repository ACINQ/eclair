{
  pkgs ? null
}:
let nixpkgs = import ./nixpkgs.nix;
    overlays = [];
    pkgs' = if pkgs == null
            then import nixpkgs {inherit overlays;}
            else pkgs;
    vim-ide = import (
      fetchTarball "https://github.com/tim2CF/ultimate-haskell-ide/tarball/a6c2ca747a8b226b9fac51b33600347df61d549f"
    ) {deps = [];};
    mavenix = import (
      fetchTarball "https://github.com/nix-community/mavenix/tarball/master"
    ) {};
in
with pkgs';

stdenv.mkDerivation {
  name = "eclair-env";
  buildInputs = [
    # ide
    vim-ide
    # java
    jdk11
    maven
    mavenix.cli
    # test deps
    docker
    bitcoin
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
