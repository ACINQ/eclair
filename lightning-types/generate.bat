@echo off

call mvn -f pom_generate_scalapb.xml package

call mkdir target\generated-sources\scala

protoc --plugin=protoc-gen-scala=protoc-gen-scala.bat --scala_out=target\generated-sources\scala src\main\protobuf\lightning.proto