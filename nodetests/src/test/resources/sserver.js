var fs = require('fs');
var path = require('path');
var tls = require('tls');

if (process.argv.length !== 5) {
  console.error('Usage: node s_server.js <key> <cert> <port>');
  process.exit(2);
}

var key = fs.readFileSync(process.argv[2]);
var cert = fs.readFileSync(process.argv[3]);
var port = parseInt(process.argv[4]);

var svr = tls.createServer( {
  key: key,
  cert: cert
}, function(conn) {
  conn.setEncoding('utf8');
  conn.on('data', function(chunk) {
    console.log('Client said: "%s"', chunk);
    conn.write('Thanks!');
  });
  conn.on('error', function(err) {
    console.error('Client error: %s', err);
  });
  conn.on('end', function() {
    console.log('Client ended');
  });
});

svr.listen(port);
