var http = require('http');
var assert = require('assert');

var TIMEOUT = 5000;

console.log('blackholeresponsetest2...');

var svr = http.createServer(function(req, resp) {
  // writeHead but never send any content
  resp.writeHead(200);

  setTimeout(function() {
    resp.end('Too late');
  }, TIMEOUT * 2);

  resp.setTimeout(TIMEOUT, function() {
    resp.writeHead(500);
    resp.end('Response timeout');
  });
});

svr.listen(33341, function() {
  http.get('http://localhost:33341/', function(resp) {
    var received = '';
    resp.setEncoding('utf8');
    assert.equal(resp.statusCode, 500);

    resp.on('data', function(chunk) {
      received += chunk;
    });

    resp.on('end', function() {
      console.log('Got: %s', received);
      assert.equal(received, 'Response timeout');
      svr.close();
    });
  });
});


