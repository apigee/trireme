var http = require('http');
var assert = require('assert');

var TIMEOUT = 5000;

console.log('blackholeresponsetest...');

var svr = http.createServer(function(req, resp) {
  // Don't ever return anything
  if (!process.env.TIMEOUT_SET) {
    resp.setTimeout(TIMEOUT, function() {
      resp.writeHead(500);
      resp.end('Request timed out');
    });
  }
});

svr.listen(33340, function() {
  http.get('http://localhost:33340/', function(resp) {
    var received = '';
    resp.setEncoding('utf8');
    assert.equal(resp.statusCode, 500);

    resp.on('data', function(chunk) {
      received += chunk;
    });

    resp.on('end', function() {
      console.log('Got: %s', received);
      assert.equal(received, 'Request timed out');
      svr.close();
    });
  });
});



