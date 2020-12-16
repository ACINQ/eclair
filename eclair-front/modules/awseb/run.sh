export LOCAL_IP=$(curl -s 169.254.169.254/latest/meta-data/local-ipv4)
export HOSTNAME=$(hostname)
exec java -javaagent:lib/kanela-agent-1.0.5.jar -jar application.jar