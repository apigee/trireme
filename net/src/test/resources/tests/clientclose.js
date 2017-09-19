var assert = require('assert');
var http = require('http');
var util = require('util');

var TIMEOUT = 100;
var ITERATIONS = 10;

console.log('client close test...');

var gotFinish = false;
var gotClose = false;

function writeChunk(i, resp) {
  if (i < ITERATIONS) {
    resp.write('Chunk');
    setTimeout(function() {
      writeChunk(i + 1, resp);
    }, TIMEOUT);
  } else {
    resp.end();
  }
}

var svr = http.createServer(function(req, resp) {
  gotFinish = gotClose = false;

  resp.on('finish', function() {
    gotFinish = true;
  });
  resp.on('close', function() {
    gotClose = true;
  });

  // Write some content but not all of it
  resp.writeHead(200);
  writeChunk(0, resp);
});

function getGood(url, cb) {
  console.log('good request');
  http.get(url, function(resp) {
    resp.on('data', function() {});
    resp.on('end', function() {
      assert(gotFinish);
      assert(!gotClose);
      if (cb) {
        cb();
      }
    });
  });
}

function getClose(url, cb) {
  console.log('close request');
  http.get(url, function(resp) {
    resp.on('data', function() {});
    resp.connection.destroy();
    setTimeout(function() {
      assert(gotClose);
      if (cb) {
        cb();
      }
    }, 1000);
  });
}

svr.listen(0, function() {
  var url = util.format('http://localhost:%d', svr.address().port);
  getGood(url, function() {
    getClose(url, function() {
      console.log('Done');
      svr.close();
    });
  });
});


