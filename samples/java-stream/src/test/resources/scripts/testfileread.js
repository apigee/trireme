var assert = require('assert');
var fs = require('fs');
var path = require('path');

var javaFile = require('java-file');

function testFile(fileName, cb) {
  var contents = fs.readFileSync(fileName, { encoding: 'utf8' });

  var reader = new javaFile.ReadStream(fileName);
  var readContents = '';

  reader.setEncoding('utf8');
  reader.on('data', function(s) {
    readContents += s;
  });
  reader.on('end', function() {
    assert.deepEqual(readContents, contents);
    if (cb) {
      cb();
    }
  });
}

testFile(path.join(__dirname, '../fixtures/hello.txt'));
