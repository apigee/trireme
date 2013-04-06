var https = require('https');
var assert = require('assert');
var path = require('path');
var fs = require('fs');

var sampleName =  path.normalize(path.join(__dirname, '../sample.txt'));
var sample = fs.readFileSync(sampleName, {encoding: 'utf8'});
var keystore = path.normalize(path.join(__dirname, '../agent1.jks'));

var svr = https.createServer({
    keystore: keystore, passphrase: 'secure'
  }, function(req, resp) {
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
    rs.setEncoding('utf8');
    rs.on('readable', function() {
      resp.write(rs.read());
    });
    rs.on('end', function() {
      resp.end();
    });
  });
});

svr.listen(33333, function() {
  var req = https.request({host: 'localhost', port: 33333,
                path: '/', method: 'POST',
                headers: { 'Content-Type': 'text/plain' },
                rejectUnauthorized: false},
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
