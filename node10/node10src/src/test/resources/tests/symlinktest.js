var fs = require('fs');
var path = require('path');
var assert = require('assert');

var base = './target/linktest';
var dir1 = path.join(base, 'dir1');
var dirLinkDest = path.resolve(dir1);
var dir2 = path.join(base, 'dir2');
var file1 = path.join(dir1, 'file1');
var fileLinkDest = path.resolve(file1);
var fileLink = path.join(dir1, 'file1link');

try { fs.unlinkSync(dir2); } catch (e) {}
try { fs.unlinkSync(fileLink); } catch (e) {}
try { fs.unlinkSync(file1); } catch (e) {}
try { fs.rmdirSync(dir1); } catch (e) {}
try { fs.rmdirSync(base); } catch (e) {}

fs.mkdirSync(base);
fs.mkdirSync(dir1);

TESTTEXT = 'Hello, World!';
fs.writeFileSync(file1, TESTTEXT);

// Test that we can symlink a file, read the link, and read the contents
fs.symlinkSync(fileLinkDest, fileLink);
linkDest = fs.readlinkSync(fileLink);
assert.equal(fileLinkDest, linkDest);
linkStuff = fs.readFileSync(fileLink);
assert.equal(TESTTEXT, linkStuff);

// Test that we can symlink a directory, read the link, and read the contents
fs.symlinkSync(dirLinkDest, dir2);
//linkDest = fs.readlinkSync(dir2);
//assert.equal(dirLinkDest, linkDest);

origDir = fs.readdirSync(dir1);
linkDir = fs.readdirSync(dir2);
assert.deepEqual(origDir, linkDir);
linkStuff = fs.readFileSync(path.join(dir2, 'file1'));
assert.equal(TESTTEXT, linkStuff);

