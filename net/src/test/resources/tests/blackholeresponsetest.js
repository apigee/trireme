var assert = require('assert');
var http = require('http');
var util = require('util');

var TIMEOUT = 1000;

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

svr.listen(0, function() {
  var url = util.format('http://localhost:%d', svr.address().port);
  http.get(url, function(resp) {
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



