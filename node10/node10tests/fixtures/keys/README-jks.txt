These are just test keys. The key store password is "secure".

How to generate the keys:
openssl pkcs12 -export -in agent1-cert.pem -inkey agent1-key.pem > agent1.p12
keytool -importkeystore -srckeystore agent1.p12 -destkeystore agent1.jks -srcstoretype pkcs12
