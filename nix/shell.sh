#!/bin/sh

echo "starting nixos container..."
docker run -it --rm \
  -e NIXPKGS_ALLOW_BROKEN=1 \
  -v "$(pwd):/app" \
  -v "/var/run/docker.sock:/var/run/docker.sock" \
  -v "nix-ubuntu:/nix" \
  -v "nix-ubuntu-19.09-developer:/home/developer" \
  -w "/app" tkachuklabs/nix-ubuntu:2.3.10 sh -c "
  nix-shell ./nix/shell.nix --pure \
   --option sandbox false \
   -v --show-trace
  "
