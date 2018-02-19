# Importing bitcoin.org SSL cetrificate

1. Open https://bitcoin.org/ in Firefox.
2. Click on the _Site information button_ (a little letter "i" in a circle).
3. Click on the _Show connection details_ button (it looks like a ">" sign).
4. Click on the _More information_ button, a _Page info_ window will appear.
5. Select the _Security_ tab and click on the _View Certificate_ button.
6. In the _Certificate viewer_ window select the _Details_ tab, then click on the _Export..._ button.
7. Save the certificate as `~/bitcoinorg.crt`
8. Locate your JVM `cacerts` file. For JDK 1.8.0_73 on Mac OS X it's located at
`/Library/Java/JavaVirtualMachines/jdk1.8.0_73.jdk/Contents/Home/jre/lib/security/cacerts`
9. Import the certificate using `keytool`. You will be asked for the keystore password wich is `changeit` by default.
```shell
$ sudo keytool -import -alias bitcoin.org -keystore /Library/Java/JavaVirtualMachines/jdk1.8.0_73.jdk/Contents/Home/jre/lib/security/cacerts -file ~/bitcoinorg.crt`
```
