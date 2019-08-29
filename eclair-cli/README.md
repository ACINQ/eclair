native-image
-cp "picocli-4.0.3.jar;eclair-cli_2.11-0.3.2-SNAPSHOT.jar" 
-H:ReflectionConfigurationFiles=target/classes/META-INF/native-image/picocli-generated/reflect-config.json 
-H:+ReportUnsupportedElementsAtRuntime 
-H:+ReportExceptionStackTraces 
--static 
fr.acinq.eclair.cli.EclairCli



native-image -cp picocli-4.0.3.jar:eclair-cli_2.11-0.3.2-SNAPSHOT.jar -H:ReflectionConfigurationFiles=reflect-config.json -H:+ReportUnsupportedElementsAtRuntime -H:+ReportExceptionStackTraces --static fr.acinq.eclair.cli.EclairCli