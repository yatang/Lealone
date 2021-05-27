/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lealone.test.client;

import java.sql.Connection;
import java.sql.ParameterMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.lealone.client.jdbc.JdbcPreparedStatement;
import org.lealone.client.jdbc.JdbcResultSet;
import org.lealone.db.SysProperties;
import org.lealone.test.sql.SqlTestBase;

public class JdbcPreparedStatementTest extends SqlTestBase {

    private JdbcPreparedStatement ps;

    @Test
    public void run() throws Exception {
        testException();
        testMetaData();
        testFetchSize();
        testBatch();
        testAsync();
    }

    void testException() throws Exception {
        createTable();
        executeUpdate("INSERT INTO test(f1, f2) VALUES(1, 2)");

        testSyncExecuteUpdateException();
        testAsyncExecuteUpdateException();

        testSyncExecuteQueryException();
        testAsyncExecuteQueryException();
    }

    private JdbcPreparedStatement prepareStatement(String sql) throws Exception {
        return prepareStatement(conn, sql);
    }

    private JdbcPreparedStatement prepareStatement(Connection conn, String sql) throws Exception {
        return (JdbcPreparedStatement) conn.prepareStatement(sql);
    }

    void testSyncExecuteUpdateException() throws Exception {
        try {
            ps = prepareStatement("INSERT INTO test(f1, f2) VALUES(1, 2)");
            // 主键重复，抛异常
            ps.executeUpdate();
            fail();
        } catch (SQLException e) {
        }

        Connection conn = getConnection();
        ps = prepareStatement(conn, "INSERT INTO test(f1, f2) VALUES(2, 3)");
        conn.close();
        try {
            // 连接已经关闭，抛异常
            ps.executeUpdate();
            fail();
        } catch (SQLException e) {
        }
    }

    void testAsyncExecuteUpdateException() throws Exception {
        // 主键重复，抛异常
        testAsyncExecuteUpdateException(conn, "INSERT INTO test(f1, f2) VALUES(1, 2)", false);

        Connection conn = getConnection();
        // 连接已经关闭，抛异常
        testAsyncExecuteUpdateException(conn, "INSERT INTO test(f1, f2) VALUES(2, 3)", true);
    }

    private void testAsyncExecuteUpdateException(Connection conn, String sql, boolean closeConnection)
            throws Exception {
        JdbcPreparedStatement ps = prepareStatement(conn, sql);
        if (closeConnection)
            conn.close();
        AtomicReference<Throwable> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        ps.executeUpdateAsync().onFailure(t -> {
            ref.set(t);
            latch.countDown();
        });
        try {
            latch.await(3000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        assertTrue(ref.get() instanceof SQLException);
    }

    void testSyncExecuteQueryException() throws Exception {
        try {
            // 主键参数不合法，抛异常
            ps = prepareStatement("Select * From test Where f1=?");
            ps.setString(1, "abc");
            ps.executeQuery();
            fail();
        } catch (SQLException e) {
        }

        Connection conn = getConnection();
        JdbcPreparedStatement ps = prepareStatement(conn, "Select * FROM test");
        conn.close();
        try {
            // 连接已经关闭，抛异常
            ps.executeQuery();
            fail();
        } catch (SQLException e) {
        }
    }

    void testAsyncExecuteQueryException() throws Exception {
        // 主键参数不合法，抛异常
        testAsyncExecuteQueryException(conn, "Select * From test Where f1=?", false);

        Connection conn = getConnection();
        // 连接已经关闭，抛异常
        testAsyncExecuteQueryException(conn, "Select * FROM test", true);
    }

    private void testAsyncExecuteQueryException(Connection conn, String sql, boolean closeConnection) throws Exception {
        JdbcPreparedStatement ps = prepareStatement(conn, sql);
        if (closeConnection) {
            conn.close();
        } else {
            ps.setString(1, "abc");
        }
        AtomicReference<Throwable> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        ps.executeQueryAsync().onFailure(t -> {
            ref.set(t);
            latch.countDown();
        });
        try {
            latch.await(3000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        assertTrue(ref.get() instanceof SQLException);
    }

    void testMetaData() throws Exception {
        createTable();
        String sql = "INSERT INTO test(f1, f2) VALUES(?, ?)";
        JdbcPreparedStatement ps = (JdbcPreparedStatement) conn.prepareStatement(sql);
        ResultSetMetaData md = ps.getMetaData();
        assertNull(md);
        ParameterMetaData pmd = ps.getParameterMetaData();
        assertNotNull(pmd);
        assertEquals(2, pmd.getParameterCount());

        ps.setInt(1, 10);
        ps.setLong(2, 20);
        ps.executeUpdate();

        int count = 4;
        CountDownLatch latch = new CountDownLatch(count);
        for (int i = 1; i <= count; i++) {
            ps.setInt(1, i * 100);
            ps.setLong(2, 2 * i * 200);
            ps.executeUpdateAsync().onComplete(ar -> {
                latch.countDown();
            });
        }
        latch.await();

        ps = (JdbcPreparedStatement) conn.prepareStatement("SELECT * FROM test where f2 > ?");
        md = ps.getMetaData();
        assertNotNull(md);
        assertEquals(2, md.getColumnCount());

        CountDownLatch latch2 = new CountDownLatch(1);
        ps.setLong(1, 2);
        ps.executeQueryAsync().onComplete(ar -> {
            ResultSet rs = ar.getResult();
            try {
                while (rs.next()) {
                    System.out.println("f1=" + rs.getInt(1) + " f2=" + rs.getLong(2));
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
            latch2.countDown();
        });
        // ps.executeQueryAsync(null);
        latch2.await();
    }

    void testFetchSize() throws Exception {
        createTable();
        String sql = "INSERT INTO test(f1, f2) VALUES(?, ?)";
        JdbcPreparedStatement ps = (JdbcPreparedStatement) conn.prepareStatement(sql);
        int count = 200;
        for (int i = 1; i <= count; i++) {
            ps.setInt(1, i * 10);
            ps.setLong(2, i * 20);
            ps.addBatch();
        }
        ps.executeBatch();
        ps.close();

        sql = "SELECT * FROM test WHERE f1 >= ?";
        ps = (JdbcPreparedStatement) conn.prepareStatement(sql);
        ps.setInt(1, 1);
        JdbcResultSet rs = (JdbcResultSet) ps.executeQuery();
        assertEquals(count, rs.getRowCount());
        assertEquals(SysProperties.SERVER_RESULT_SET_FETCH_SIZE, rs.getCurrentRowCount());
        assertEquals(SysProperties.SERVER_RESULT_SET_FETCH_SIZE, rs.getFetchSize());
        rs.close();
        ps.close();

        int fetchSize = 2;
        ps = (JdbcPreparedStatement) conn.prepareStatement(sql);
        ps.setInt(1, 1);
        ps.setFetchSize(fetchSize); // 改变默认值
        rs = (JdbcResultSet) ps.executeQuery();
        assertEquals(count, rs.getRowCount());
        assertEquals(fetchSize, rs.getCurrentRowCount());
        assertEquals(fetchSize, rs.getFetchSize());
        rs.close();
        ps.close();
    }

    void testBatch() throws Exception {
        createTable();
        String sql = "INSERT INTO test(f1, f2) VALUES(?, ?)";
        JdbcPreparedStatement ps = (JdbcPreparedStatement) conn.prepareStatement(sql);
        ps.setInt(1, 1000);
        ps.setLong(2, 2000);
        ps.addBatch();
        ps.setInt(1, 8000);
        ps.setLong(2, 9000);
        ps.addBatch();
        int[] updateCounts = ps.executeBatch();
        assertEquals(2, updateCounts.length);
        assertEquals(1, updateCounts[0]);
        assertEquals(1, updateCounts[1]);
    }

    void testAsync() throws Exception {
        createTable();

        String sql = "INSERT INTO test(f1, f2) VALUES(?, ?)";
        JdbcPreparedStatement ps = (JdbcPreparedStatement) conn.prepareStatement(sql);
        ps.setInt(1, 1);
        ps.setLong(2, 2);
        ps.executeUpdate();

        CountDownLatch latch = new CountDownLatch(1);
        ps.setInt(1, 2);
        ps.setLong(2, 2);
        ps.executeUpdateAsync().onComplete(res -> {
            System.out.println("updateCount: " + res.getResult());
            latch.countDown();
        });
        latch.await();
        ps.close();

        ps = (JdbcPreparedStatement) conn.prepareStatement("SELECT * FROM test where f2 = ?");
        ps.setLong(1, 2);
        ResultSet rs = ps.executeQuery();
        while (rs.next()) {
            System.out.println("f1=" + rs.getInt(1) + " f2=" + rs.getLong(2));
        }
        rs.close();
        CountDownLatch latch2 = new CountDownLatch(1);
        JdbcPreparedStatement ps2 = ps;
        ps2.setLong(1, 2);
        ps2.executeQueryAsync().onComplete(res -> {
            ResultSet rs2 = res.getResult();
            try {
                while (rs2.next()) {
                    System.out.println("f1=" + rs2.getInt(1) + " f2=" + rs2.getLong(2));
                }
                ps2.close();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                latch2.countDown();
            }
        });
        latch2.await();
    }

    private void createTable() throws Exception {
        executeUpdate("DROP TABLE IF EXISTS test");
        executeUpdate("CREATE TABLE IF NOT EXISTS test (f1 int primary key, f2 long)");
    }
}
