#!/bin/sh

echo "starting nixos container..."
docker run -it --rm \
  -e NIXPKGS_ALLOW_BROKEN=1 \
  -v "$(pwd):/app" \
  -v "nix:/nix" \
  -v "nix-19.09-root:/root" \
  -w "/app" nixos/nix:2.3 sh -c "
  nix-shell ./nix/shell.nix --pure \
   --option sandbox false \
   -vvvvv --show-trace
  "
