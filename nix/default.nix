{
  pkgs ? null
}:
let overlays = [];
    nixpkgs = import ./nixpkgs.nix;
    pkgs' = if pkgs == null
            then import nixpkgs {inherit overlays;}
            else pkgs;
in
with pkgs';

let jar = buildMaven ./project-info.json;
in stdenv.mkDerivation rec {
  buildInputs = [ makeWrapper ];
  name = "my-app";
  version = "0.1.0";
  builder = writeText "install.sh" ''
    source $stdenv/setup
    mkdir -p $out/share/java $out/bin
    cp ${jar.build} $out/share/java/
    makeWrapper ${jre_headless}/bin/java $out/bin/${name} \
      --add-flags "-jar $out/share/java/${jar.build}"
  '';
}
