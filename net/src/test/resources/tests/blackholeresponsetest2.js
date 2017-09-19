var assert = require('assert');
var http = require('http');
var util = require('util');

var TIMEOUT = 1000;

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
      assert.equal(received, 'Response timeout');
      svr.close();
    });
  });
});


