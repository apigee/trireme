var http = require('http');
var assert = require('assert');
var path = require('path');
var fs = require('fs');

var sampleName =  path.normalize(path.join(__dirname, '../sample.txt'));
var sample = fs.readFileSync(sampleName, {encoding: 'utf8'});

var svr = http.createServer(function(req, resp) {
  var body = '';
  req.setEncoding('utf8');
  req.on('readable', function() {
    var chunk;
    do {
      chunk = req.read();
      if (chunk != null) {
        body += chunk;
      }
    } while (chunk != null);
  });
  req.on('end', function() {
    assert.equal(sample, body);
    resp.writeHead(200, { 'Content-Type': 'text/plain' });

    var rs = fs.createReadStream(sampleName, { flags: 'r', bufferSize: 16, autoClose: true });
    rs.on('data', function(chunk) {
      resp.write(chunk);
    });
    rs.on('end', function() {
      resp.end();
    });
  });
});

svr.listen(0, function() {
  var req = http.request({host: 'localhost', port: svr.address().port,
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


  var rs = fs.createReadStream(sampleName, { flags: 'r', bufferSize: 16, autoClose: true });
  rs.setEncoding('utf8');
  rs.on('readable', function() {
    var chunk;
    do {
      chunk = rs.read(16);
      if (chunk != null) {
        req.write(chunk);
      }
    } while (chunk != null);
  });
  rs.on('end', function() {
    req.end();
  });
});
