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

svr.on('error', function(err) {
  console.error('Got a server error: ' + err);
  gotError = true;
});

console.log('Starting to listen but expecting to fail');
svr.listen(33333, function() {
  assert.fail('Server listening, but we should have failed here!');
});



