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
  db.execute('create table names (NAME varchar(128), ID integer)',
    null,
    function(err) {
      assert(!err);
      done();
    });
}

function dropTable(done) {
  db.execute('drop table names',
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

function insert1(done) {
  db.execute("insert into names (name, id) values ('Fred Jones', 1)",
    null,
    function(err, result) {
      assert(!err);
      assert.equal(result.updateCount, 1);

      db.execute('select * from names',
        null,
        function(err, result, rows) {
          assert(!err);
          assert.equal(rows.length, 1);
          assert.deepEqual(rows[0]['NAME'], 'Fred Jones');
          assert.deepEqual(rows[0]['ID'], 1);
          done();
        });
    });
}

function insert2(done) {
  db.execute("insert into names (name, id) values (?, ?)",
    [ 'Davey Jones', 2 ],
    function(err, result) {
      assert(!err);
      assert.equal(result.updateCount, 1);

      db.execute('select * from names where id = ?',
        [ 2 ],
        function(err, result, rows) {
          assert(!err);
          assert.equal(rows.length, 1);
          assert.deepEqual(rows[0]['NAME'], 'Davey Jones');
          assert.deepEqual(rows[0]['ID'], 2);
          done();
        });
    });
}

function selectNotFound(done) {
  db.execute('select * from names where id = 99999',
    null,
    function(err, result, rows) {
      assert(!err);
      assert.equal(rows.length, 0);
      done();
    });
}

function selectAs(done) {
  db.execute("select id as FOO, name as BAR from names where id = ?",
    [ 2 ],
    function(err, result, rows) {
      assert(!err);
      assert.equal(rows.length, 1);
      assert.deepEqual(rows[0]['BAR'], 'Davey Jones');
      assert.deepEqual(rows[0]['FOO'], 2);
      done();
    });
}

function selectInvalidTable(done) {
  db.execute('select * from nonexistent',
    null,
    function(err, result, rows) {
      assert(err);
      done();
    });
}

function transactionCommit(done) {
  db.setAutoCommit(false);
  db.execute("insert into names (name, id) values (?, ?)",
    [ 'Janey Jones', 3 ],
    function(err, result) {
      assert(!err);
      assert.equal(result.updateCount, 1);

      db.commit(function(err) {
        assert(!err);

        db.execute('select * from names where id = ?',
          [ 3 ],
          function(err, result, rows) {
            db.setAutoCommit(false);
            assert(!err);
            assert.equal(rows.length, 1);
            assert.deepEqual(rows[0]['NAME'], 'Janey Jones');
            assert.deepEqual(rows[0]['ID'], 3);
            done();
          });
      });
    });
}

function transactionRollback(done) {
  db.setAutoCommit(false);
  db.execute("insert into names (name, id) values (?, ?)",
    [ 'Jimmy Jones', 4 ],
    function(err, result) {
      assert(!err);
      assert.equal(result.updateCount, 1);

      db.rollback(function(err) {
        assert(!err);

        db.execute('select * from names where id = ?',
          [ 4 ],
          function(err, result, rows) {
            db.setAutoCommit(false);
            assert(!err);
            assert.equal(rows.length, 0);
            done();
          });
      });
    });
}

var success = false;

connect(function() {
  createTable(function() {
    insert1(function() {
      insert2(function() {
        selectNotFound(function() {
          selectAs(function() {
            selectInvalidTable(function() {
              transactionCommit(function() {
                transactionRollback(function() {
                  dropTable(function() {
                    close(function() {
                      success = true;
                    });
                  });
                });
              });
            });
          });
        });
      });
    });
  });
});

process.on('exit', function() {
  assert(success);
});


