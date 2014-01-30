// This test was adapted from the test suite for iconv-lite:
// https://github.com/ashtuchkin/iconv-lite

var assert = require('assert'),
    iconv = require('iconv-lite');

var testString = "Hello123!";
var testStringLatin1 = "Hello123!£Å÷×çþÿ¿®";
var testStringBase64 = "SGVsbG8xMjMh";

assert.ok(iconv.toEncoding(testString, "utf8") instanceof Buffer);
        
var s = iconv.fromEncoding(new Buffer(testString), "utf8");
assert.strictEqual(Object.prototype.toString.call(s), "[object String]");

['utf8', "UTF-8", "UCS2", ""].forEach(function(enc) {
            console.log(enc);
            assert.strictEqual(iconv.toEncoding(testStringLatin1, enc).toString(enc), testStringLatin1);
            assert.strictEqual(iconv.fromEncoding(new Buffer(testStringLatin1, enc), enc), testStringLatin1);
        });
        
assert.strictEqual(iconv.toEncoding(testStringBase64, "base64").toString("binary"), testString);
        assert.strictEqual(iconv.fromEncoding(new Buffer(testString, "binary"), "base64"), testStringBase64);


        var res = iconv.toEncoding(new Buffer(testStringLatin1, "utf8"));
        assert.ok(res instanceof Buffer);
        assert.strictEqual(res.toString("utf8"), testStringLatin1);

        assert.throws(function() { iconv.toEncoding("a", "xxx"); });
        assert.throws(function() { iconv.fromEncoding("a", "xxx"); });

        assert.strictEqual(iconv.toEncoding({}, "utf8").toString(), "[object Object]");
        assert.strictEqual(iconv.toEncoding(10, "utf8").toString(), "10");
        assert.strictEqual(iconv.toEncoding(undefined, "utf8").toString(), "");
        assert.strictEqual(iconv.fromEncoding({}, "utf8"), "[object Object]");
        assert.strictEqual(iconv.fromEncoding(10, "utf8"), "10");
        assert.strictEqual(iconv.fromEncoding(undefined, "utf8"), "");

        assert.strictEqual(iconv.fromEncoding(testStringLatin1, "latin1"), iconv.decode(testStringLatin1, "latin1"));
