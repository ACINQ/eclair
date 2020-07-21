#!/bin/sh

export IP=$(ifconfig en0 | grep inet | awk '$1=="inet" {print $2}')
echo "host is $IP"
echo "starting nixos container..."
# enable for GUI apps
# xhost + $IP
docker run -it --rm \
  -e DISPLAY=$IP:0 \
  -e NIXPKGS_ALLOW_BROKEN=1 \
  -v "$(pwd):/app" \
  -v "nix:/nix" \
  -v "nix-19.09-root:/root" \
  -w "/app" nixos/nix:2.3 sh -c "
  nix-shell ./nix/shell.nix --pure \
   --option sandbox false \
   -vvvvv --show-trace
  "
