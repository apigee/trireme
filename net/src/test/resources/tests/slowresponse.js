var http = require('http');
var assert = require('assert');

var TIMEOUT = 1000;

console.log('slowresponse test...');

var svr = http.createServer(function(req, resp) {
  // Write some content but not all of it
  resp.writeHead(200);
  resp.write('Partial output');

  setTimeout(function() {
    try {
      resp.write('End of output');
    } catch (e) {
      console.log('Got expected exception %s on late write', e);
    }
  }, TIMEOUT * 2);
  resp.on('error', function(e) {
    // THIS is OK -- should get a write after end error, actually!
    console.log('Got response error %s', e);
  });

  resp.setTimeout(TIMEOUT, function() {
    resp.end();
  });
});

svr.listen(33343, function() {
  http.get('http://localhost:33343', function(resp) {
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


