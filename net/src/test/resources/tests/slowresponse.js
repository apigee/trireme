var http = require('http');
var assert = require('assert');

var TIMEOUT = 5000;

console.log('slowresponse test...');

var svr = http.createServer(function(req, resp) {
  // Write some content but not all of it
  resp.writeHead(200);
  resp.write('Partial output');

  setTimeout(function() {
    resp.write('End of output');
  }, TIMEOUT * 2);

  resp.setTimeout(TIMEOUT, function() {
    resp.end();
  });
});

svr.listen(33340, function() {
  http.get('http://localhost:33340/', function(resp) {
    var received = '';
    resp.setEncoding('utf8');
    assert.equal(resp.statusCode, 200);

    resp.on('data', function(chunk) {
      received += chunk;
    });

    resp.on('end', function() {
      console.log('Got: %s', received);
      assert.equal(received, 'Partial output');
      svr.close();
    });
  });
});


