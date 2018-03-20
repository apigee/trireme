var assert = require('assert');
var http = require('http');
var zlib = require('zlib');
var common = require('../common');

http.createServer(function(req, res) {
  res.setHeader('Content-Encoding', 'gzip');
  // gzip of "abc\n"
  var buf = new Buffer([0x1f, 0x8b, 0x08, 0x00, 0x9a, 0x2d, 0xb0, 0x5a, 0x00, 0x03, 0x4b, 0x4c, 0x4a, 0xe6, 0x02, 0x00, 0x4e, 0x81, 0x88, 0x47, 0x04, 0x00, 0x00, 0x00]);
  res.end(buf);
}).listen(common.PORT, function(err) {
  if (err) {
    throw err;
  }

  var opts = {
    host: 'localhost',
    port: common.PORT,
    path: '/test',
    headers: {
      'Accept-Encoding': 'gzip'
    }
  };

  var req = http.request(opts, function(res) {
    var unzip = zlib.createUnzip();
    unzip.on('data', function(data) {
        assert.equal(data.toString(), "abc\n");
    })
    unzip.on('end', function() {
        process.exit(0);
    })
    res.pipe(unzip);
  }).end();
});
