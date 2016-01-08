# Keys for testing TLS with intermediate CA certs

* root.pem: Root CA certificate
* inter[1,2].pem: Intermediate CA certificates signed by root

* client.[key,cert].pem: client key signed by inter1 CA
* server.[key,cert].pem: server key signed by inter2 CA

CA keys and certs generated based on this very useful guide:

[https://jamielinux.com/docs/openssl-certificate-authority/index.html](https://jamielinux.com/docs/openssl-certificate-authority/index.html)
