var assert = require('assert');
var fs = require('fs');
var path = require('path');

var javaFile = require('java-file');

function testFile(readName, writeName, cb) {
  var contents = fs.readFileSync(readName, { encoding: 'utf8' });

  var writer = new javaFile.WriteStream(writeName);

  writer.end(contents, function(err) {
    assert(!err);
    var newContents = fs.readFileSync(writeName, { encoding: 'utf8' });
    fs.unlinkSync(writeName);
    assert.deepEqual(contents, newContents);

    if (cb) {
      cb();
    }
  });
}

testFile(path.join(__dirname, '../fixtures/hello.txt'), path.join(__dirname, '../fixtures/testout.txt'));
