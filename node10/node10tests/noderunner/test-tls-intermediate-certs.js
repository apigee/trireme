var assert = require('assert');
var fs = require('fs');
var https = require('https');
var path = require('path');

var port = 33337;

function loadKey(name) {
  return fs.readFileSync(path.join(__dirname, 'keys', name));
}

var rootCa = loadKey('root.cert.pem');
// Client cert is signed using "inter1"
var inter1 = loadKey('inter1.cert.pem');
// Server cert is signed using "inter2"
var inter2 = loadKey('inter2.cert.pem');

var clientKey = loadKey('client.key.pem');
var clientCert = loadKey('client.cert.pem');
var serverKey = loadKey('server.key.pem');
var serverCert = loadKey('server.cert.pem');

// Create an HTTPS server that uses a cert represented by an intermediate cert.
var svr = https.createServer({
  key: serverKey,
  cert: serverCert,
  ca: [ rootCa, inter2 ],
  requestCert: true,
  rejectUnauthorized: true

}, function(req, resp) {
  resp.end('success');
});

var response = '';
var ended = false;

process.on('exit', function() {
  assert(ended);
  assert.equal('success', response);
});


// Make an HTTP request that uses a key signed by a different intermediate cert of the same root CA.
svr.listen(port, function() {
  var req = https.request({
    hostname: 'localhost',
    port: port,
    path: '/',
    method: 'GET',
    key: clientKey,
    cert: clientCert,
    ca: [ rootCa, inter1 ],
    rejectUnauthorized: true

  }, function(resp) {
    resp.setEncoding('utf8');
    resp.on('data', function(chunk) {
      response += chunk;
    });
    resp.on('end', function() {
      ended = true;
      svr.close();
    });
  });
  req.end('Hello!');
});
