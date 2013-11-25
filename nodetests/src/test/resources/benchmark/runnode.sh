#!/bin/sh

node buffers/buffer-base64-encode.js
node buffers/buffer-creation.js
node buffers/buffer-read.js
node buffers/buffer-write.js
node crypto/hash-stream-creation.js
node crypto/hash-stream-throughput.js
node fs/read-stream-throughput.js
node fs/readfile.js
node fs/write-stream-throughput.js
node http/client-request-body.js
node misc/next-tick-breadth.js
node misc/next-tick-depth.js
node misc/spawn-echo.js
node misc/startup.js
node misc/string-creation.js
node net/net-c2s.js
node net/net-pipe.js
node net/net-s2c.js
node net/tcp-raw-c2s.js
node net/tcp-raw-pipe.js
node net/tcp-raw-s2c.js
node tls/throughput.js
node tls/tls-connect.js

