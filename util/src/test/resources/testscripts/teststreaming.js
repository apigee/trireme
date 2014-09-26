var assert = require('assert');

var jdbc = process.binding('trireme-jdbc-wrap');

var db;

function connect(done) {
  jdbc.createConnection('jdbc:hsqldb:mem:test', null,
    function(err, conn) {
      assert(!err);
      db = conn;
      done();
    });
}

function createTable(done) {
  db.execute('create table lots (ID integer)',
    null,
    function(err) {
      assert(!err);
      done();
    });
}

function dropTable(done) {
  db.execute('drop table lots',
    null,
    function(err) {
      assert(!err);
      done();
    });
}

function close(done) {
  db.close(function(err) {
    assert(!err);
    done();
  });
}

var numRows = 100;

function insertRow(id, done) {
  if (id < numRows) {
    db.executeStreaming('insert into lots (id) values (?)',
      [ id ],
      function(err, result) {
        assert(!err);
        assert.equal(result.updateCount, 1);
        insertRow(id + 1, done);
      });
  } else {
    console.log('Inserted %d rows', id);
    done();
  }
}

function populateRows(done) {
    insertRow(0, done);
}

function handleRows(count, handle, done) {
  var cumCount = count;
  handle.fetchRows(10, function(err, rows, eof) {
    assert(!err);
    assert(rows);
    assert(rows.length <= 10);
    if (rows) {
      rows.forEach(function(row) {
        assert.equal(row['ID'], cumCount);
        cumCount++;
      });
    }

    if (eof) {
      assert.equal(cumCount, numRows);
      done();
    } else {
      handleRows(cumCount, handle, done);
    }
  });
}

function queryAllRows(done) {
  db.executeStreaming('select * from lots',
    undefined,
    function(err, result, handle) {
      assert(!err);
      handleRows(0, handle, done);
    });
}

var success = false;

connect(function() {
  createTable(function() {
    populateRows(function() {
      queryAllRows(function() {
        dropTable(function() {
          close(function() {
            success = true;
          });
        });
      });
    });
  });
});

process.on('exit', function() {
  assert(success);
});

