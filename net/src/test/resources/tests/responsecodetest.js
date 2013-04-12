var http = require('http');
var assert = require('assert');

var svr = http.createServer(function(req, resp) {
  resp.writeHead(305, { 'X-Apigee-Hello' : 'World' });
  resp.end();
});

svr.listen(33333, function() {
  http.get('http://localhost:33333/', function(resp) {
    resp.on('readable', function() {
      resp.read();
    });
    svr.close();

    assert.equal(305, resp.statusCode);
    assert.equal('World', resp.headers['x-apigee-hello']);
  });
});
