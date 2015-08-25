var assert = require('assert');
var fs = require('fs');
var path = require('path');
var stringDecoder = require('string_decoder');

// This file has German ISO-8859-1 characters that are not valid in UTF-8
var data = fs.readFileSync(path.join(__dirname, 'google-de.html'));

// Each character should be replaced by a single replacement character in UTF-8 and ASCII
var decodedUtf8 = data.toString('utf8');
assert.equal(data.length, decodedUtf8.length);

var decodedAscii = data.toString('ascii');
assert.equal(data.length, decodedAscii.length);

// string_decoder should do the same thing

var decoder = new stringDecoder.StringDecoder('utf8');
var sdUtf8 = decoder.write(data);
sdUtf8 += decoder.end();
assert.equal(data.length, sdUtf8.length);
assert.equal(sdUtf8, decodedUtf8);

// string_decoder should even do the same thing if we loop it
process.exit(0);
for (var i = 0; i < data.length - 1; i++) {
  var part1 = data.slice(0, i);
  var part2 = data.slice(i, data.length);
  assert.equal((part1.length + part2.length), data.length);

  var decoder = new stringDecoder.StringDecoder('utf8');
  var str = decoder.write(part1);
  str += decoder.write(part2);
  str += decoder.end();
  assert.equal(data.length, str.length);
  assert.equal(str, decodedUtf8);
}