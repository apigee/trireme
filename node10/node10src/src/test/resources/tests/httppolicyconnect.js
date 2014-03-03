var http = require('http');
var assert = require('assert');

var svr = http.createServer(function(req, resp) {
  console.log('Got an HTTP request');
  req.on('end', function() {
    resp.end('Hello, World!');
  });
});

var gotError = false;

process.on('exit', function() {
  assert(gotError);
});

console.log('Starting to listen and expecting it to work');
svr.listen(33333, function() {
  console.log('Server listening');
  var req = http.get('http://localhost:33333/', function(resp) {
      assert.fail('Got a response and should not have.');
    });
  req.on('error', function() {
    console.log('Got an expected HTTP client error');
    gotError = true;
  });
  req.end();
  svr.close();
});
