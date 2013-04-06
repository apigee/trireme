var http = require('http');
var assert = require('assert');
var path = require('path');
var fs = require('fs');

var sample = fs.readFileSync(
  path.normalize(path.join(__dirname, '../sample.txt')),
  {encoding: 'utf8'});

var svr = http.createServer(function(req, resp) {
  var body = '';
  req.setEncoding('utf8');
  req.on('readable', function() {
    body += req.read();
  });
  req.on('end', function() {
    assert.equal(sample, body);
    resp.writeHead(200, { 'Content-Type': 'text/plain' });
    resp.end(sample);
  });
});

svr.listen(33333, function() {
  var req = http.request({host: 'localhost', port: 33333,
                path: '/', method: 'POST',
                headers: { 'Content-Type': 'text/plain' }},
    function(resp) {
      var received = '';
      assert.equal(200, resp.statusCode);
      resp.setEncoding('utf8');

      resp.on('data', function(chunk) {
        received += chunk;
      });
      resp.on('end', function() {
        assert.equal(sample, received);
        svr.close();
      });
  });

  req.end(sample);
});
