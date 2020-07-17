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
    # utils
    nix-prefetch-scripts
    openssh
    cacert
    git
  ];

  TERM="xterm-256color";
  GIT_SSL_CAINFO="${cacert}/etc/ssl/certs/ca-bundle.crt";
  NIX_SSL_CERT_FILE="${cacert}/etc/ssl/certs/ca-bundle.crt";
  NIX_PATH="/nix/var/nix/profiles/per-user/root/channels";
  shellHook = ''
    mvn  -Dmaven.repo.local=$(mktemp -d -t maven)  org.nixos.mvn2nix:mvn2nix-maven-plugin:mvn2nix
  '';
}
