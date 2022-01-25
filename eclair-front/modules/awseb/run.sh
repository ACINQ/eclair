# see https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/instancedata-data-retrieval.html
TOKEN_IMDSV2=$(curl -s -X PUT "http://169.254.169.254/latest/api/token" -H "X-aws-ec2-metadata-token-ttl-seconds: 21600")
export LOCAL_IP=$(curl -s -H "X-aws-ec2-metadata-token: $TOKEN_IMDSV2" http://169.254.169.254/latest/meta-data/local-ipv4)
export HOSTNAME=$(hostname)

# make the eclair home directory
mkdir -p /home/webapp/.eclair

# if provided, copy the certificate to the proper directory
[[ -e akka-cluster-tls.jks ]] && cp -v akka-cluster-tls.jks /home/webapp/.eclair/

unzip -o eclair-front-*-bin.zip
exec ./eclair-front-*/bin/eclair-front.sh -with-kanela -Dlogback.configurationFile=logback_eb.xml
