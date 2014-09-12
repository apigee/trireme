This directory is being used to validate the code in Trireme
that we use to turn X.509 certificates into printed-out 
certificate descriptions in a way that is compatible with
OpenSSL and Node.js. This is important in order to make certificate
validation work.

For each cert, we have a ".pem" and a ".json" file for the same cert.

We got the ".pem" file as follows:

    openssl s_client -connect host:443 -servername host

We then cut the base64 certificate part from that and put it in the
file.

We got the ".json" file by running the "getcert.js" file in this
directory.

The tests will parse the cert in Java, turn it into a JSON
object, and verify that the JSON fields match those of the
corresponding Node.js output.
