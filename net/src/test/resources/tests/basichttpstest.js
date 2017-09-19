var https = require('https');
var assert = require('assert');
var path = require('path');

var finished = false;

var svr = https.createServer({
    keystore: path.normalize(path.join(__dirname, '../agent1.jks')),
    passphrase: 'secure'
  }, function(req, resp) {
  console.log('Got an HTTPS request');
  resp.on('finish', function() {
    finished = true;
  });
  req.on('data', function(chunk) {
    console.log('Server got data: ' + chunk);
  });
  req.on('end', function() {
    console.log('Server got end');
    resp.end('Hello, World!');
  });
});

console.log('Server starting to listen');
svr.listen(0, function() {
  console.log('Server listening on %j', svr.address());
  https.get({host: 'localhost', port: svr.address().port,
             path: '/', rejectUnauthorized: false}, function(resp) {
    var received = '';
    console.log('Got a response with status code ' + resp.statusCode);
    resp.setEncoding('utf8');
    if (resp.statusCode != 200) {
      process.exit(1);
    }
    resp.on('data', function(chunk) {
      console.log(chunk);
      received += chunk;
    });
    resp.on('end', function() {
      console.log('Got the whole response');
      svr.close();
      assert.equal('Hello, World!', received);
      assert(finished);
    });
  });
});
