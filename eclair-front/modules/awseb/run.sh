export LOCAL_IP=$(curl -s 169.254.169.254/latest/meta-data/local-ipv4)
export HOSTNAME=$(hostname)

# make the eclair home directory
mkdir -p /home/webapp/.eclair

# if provided, copy the certificate to the proper directory
[[ -e akka-cluster-tls.jks ]] && cp -v akka-cluster-tls.jks /home/webapp/.eclair/

unzip -o eclair-front-*-bin.zip
exec ./eclair-front-*/bin/eclair-front.sh -with-kanela -Dlogback.configurationFile=logback_eb.xml
