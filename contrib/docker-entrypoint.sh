#!/bin/sh
set -e
ECLAIR_RESOLVED_IP_OPT=""
if ! [ -z "$PUBLIC_HOST" ]; then
    RESOLVED_IP="$(dig +short $PUBLIC_HOST | grep -Eo '[0-9\.]{7,15}' | head -1)"
	if ! [ -z "$RESOLVED_IP" ]; then
		ECLAIR_RESOLVED_IP_OPT=" -Declair.server.public-ips.0=$RESOLVED_IP"
	else
	    ECLAIR_RESOLVED_IP_OPT=""
	fi
fi

java $JAVA_OPTS $ECLAIR_RESOLVED_IP_OPT -Declair.datadir=$ECLAIR_DATADIR -jar eclair-node.jar