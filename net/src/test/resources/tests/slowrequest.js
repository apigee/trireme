var http = require('http');
var assert = require('assert');
var url = require('url');

var TIMEOUT = 1000;

console.log('slowrequest test...');

var svr = http.createServer(function(req, resp) {
  // Send content quickly but only after getting everything
  resp.setTimeout(TIMEOUT, function() {
    resp.writeHead(500);
    resp.end('Response timeout');
  });

  req.on('data', function() {
    console.log('Got chunk');
  });

  req.on('end', function() {
    console.log('Got end');
    resp.end('Successful');
  });
});

svr.listen(0, function() {
  var req = http.request({
    hostname: 'localhost',
    port: svr.address().port,
    path: '/',
    method: 'POST'
  }, function(resp) {
    var received = '';
    resp.setEncoding('utf8');
    assert.equal(resp.statusCode, 200);

    resp.on('data', function(chunk) {
      received += chunk;
    });

    resp.on('end', function() {
      console.log('Got: %s', received);
      assert.equal(received, 'Successful');
      svr.close();
    });
  });

  sendChunk(0);

  function sendChunk(count) {
    setTimeout(function() {
      console.log('Chunk %d', count);
      if (count === 5) {
        req.end('Done');
      } else {
        req.write('Chunk');
        sendChunk(count + 1);
      }
    }, 100);
  }
});


