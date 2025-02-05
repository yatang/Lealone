/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.transaction.aote;

import java.sql.Connection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.lealone.common.exceptions.DbException;
import org.lealone.common.util.DataUtils;
import org.lealone.db.DataBuffer;
import org.lealone.db.RunMode;
import org.lealone.db.api.ErrorCode;
import org.lealone.db.session.Session;
import org.lealone.db.session.SessionStatus;
import org.lealone.storage.Storage;
import org.lealone.storage.StorageMap;
import org.lealone.storage.type.ObjectDataType;
import org.lealone.storage.type.StorageDataType;
import org.lealone.transaction.Transaction;
import org.lealone.transaction.aote.log.LogSyncService;
import org.lealone.transaction.aote.log.RedoLogRecord;
import org.lealone.transaction.aote.log.UndoLog;

public class AMTransaction implements Transaction {

    private static final LinkedList<WaitingTransaction> EMPTY_LINKED_LIST = new LinkedList<>();

    // 以下几个public或包级别的字段是在其他地方频繁使用的，
    // 为了使用方便或节省一点点性能开销就不通过getter方法访问了
    final AMTransactionEngine transactionEngine;
    final long transactionId;
    final String transactionName;
    final LogSyncService logSyncService;
    long commitTimestamp;

    UndoLog undoLog = new UndoLog();
    RunMode runMode;
    Runnable asyncTask;

    private HashMap<String, Integer> savepoints;
    private Session session;
    private volatile int status;
    private int isolationLevel = Connection.TRANSACTION_READ_COMMITTED; // 默认是读已提交级别
    private boolean autoCommit;

    // 被哪个事务锁住记录了
    private volatile AMTransaction lockedBy;
    private long lockStartTime;
    // 有哪些事务在等待我释放锁
    private final AtomicReference<LinkedList<WaitingTransaction>> waitingTransactionsRef = new AtomicReference<>(
            EMPTY_LINKED_LIST);

    private final ConcurrentHashMap<TransactionalValue, TransactionalValue.LockOwner> tValues = new ConcurrentHashMap<>();

    public AMTransaction(AMTransactionEngine engine, long tid) {
        this(engine, tid, null);
    }

    public AMTransaction(AMTransactionEngine engine, long tid, String hostAndPort) {
        transactionEngine = engine;
        transactionId = tid;
        transactionName = getTransactionName(hostAndPort, tid);
        logSyncService = engine.getLogSyncService();
        status = Transaction.STATUS_OPEN;
    }

    public AMTransactionEngine getTransactionEngine() {
        return transactionEngine;
    }

    public UndoLog getUndoLog() {
        return undoLog;
    }

    @Override
    public String getTransactionName() {
        return transactionName;
    }

    @Override
    public void setSession(Session session) {
        this.session = session;
    }

    @Override
    public Session getSession() {
        return session;
    }

    public void setRunMode(RunMode runMode) {
        this.runMode = runMode;
    }

    public boolean isCommitted() {
        return status == Transaction.STATUS_CLOSED;
    }

    @Override
    public int getStatus() {
        return status;
    }

    @Override
    public void setStatus(int status) {
        this.status = status;
        // setStatus这个方法会被lockedBy对应的事务调用，所以不能把lockedBy置null，必须由这个被阻塞的事务结束时自己置null
        // if (lockedBy != null && status == STATUS_OPEN) {
        // lockedBy = null;
        // lockStartTime = 0;
        // }
    }

    @Override
    public void setIsolationLevel(int level) {
        isolationLevel = level;
    }

    @Override
    public int getIsolationLevel() {
        return isolationLevel;
    }

    @Override
    public long getTransactionId() {
        return transactionId;
    }

    @Override
    public boolean isAutoCommit() {
        return autoCommit;
    }

    @Override
    public void setAutoCommit(boolean autoCommit) {
        this.autoCommit = autoCommit;
    }

    @Override
    public boolean isLocal() {
        return true;
    }

    @Override
    public <K, V> AMTransactionMap<K, V> openMap(String name, Storage storage) {
        return openMap(name, null, null, storage);
    }

    @Override
    public <K, V> AMTransactionMap<K, V> openMap(String name, StorageDataType keyType,
            StorageDataType valueType, Storage storage) {
        return openMap(name, keyType, valueType, storage, null);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <K, V> AMTransactionMap<K, V> openMap(String name, StorageDataType keyType,
            StorageDataType valueType, Storage storage, Map<String, String> parameters) {
        checkNotClosed();
        if (keyType == null)
            keyType = new ObjectDataType();
        if (valueType == null)
            valueType = new ObjectDataType();
        valueType = new TransactionalValueType(valueType);

        if (parameters == null)
            parameters = new HashMap<>(1);

        StorageMap<K, TransactionalValue> map = storage.openMap(name, keyType, valueType, parameters);
        if (!map.isInMemory()) {
            logSyncService.getRedoLog().redo(map);
        }
        transactionEngine.addStorageMap((StorageMap<Object, TransactionalValue>) map);
        return createTransactionMap(map, parameters);
    }

    protected <K, V> AMTransactionMap<K, V> createTransactionMap(StorageMap<K, TransactionalValue> map,
            Map<String, String> parameters) {
        return new AMTransactionMap<>(this, map);
    }

    @Override
    public void addSavepoint(String name) {
        if (savepoints == null)
            savepoints = new HashMap<>();

        savepoints.put(name, getSavepointId());
    }

    @Override
    public int getSavepointId() {
        return undoLog.getLogId();
    }

    private RedoLogRecord createLocalTransactionRedoLogRecord() {
        DataBuffer operations = undoLog.toRedoLogRecordBuffer(transactionEngine);
        if (operations == null || operations.limit() == 0)
            return null;
        return RedoLogRecord.createLocalTransactionRedoLogRecord(transactionId, operations);
    }

    @Override
    public void asyncCommit(Runnable asyncTask) {
        checkNotClosed();
        this.asyncTask = asyncTask;
        if (writeRedoLog(true)) {
            asyncCommitComplete();
        }
    }

    // 如果不需要事务日志同步或者不需要立即做事务日志同步那么返回true，这时可以直接提交事务了。
    // 如果需要立即做事务日志，当需要异步提交事务时返回false，当需要同步提交时需要等待
    private boolean writeRedoLog(boolean asyncCommit) {
        if (logSyncService.needSync() && undoLog.isNotEmpty()) {
            // 如果需要立即做事务日志同步，那么把redo log的生成工作放在当前线程，减轻日志同步线程的工作量
            if (logSyncService.isInstantSync()) {
                RedoLogRecord r = createLocalTransactionRedoLogRecord();
                if (asyncCommit) {
                    logSyncService.asyncCommit(r, this, null);
                    return false;
                } else {
                    logSyncService.addAndMaybeWaitForSync(r);
                    return true;
                }
            } else {
                // 对于其他日志同步场景，当前线程不需要等待，只需要把事务日志移交到后台日志同步线程的队列中即可
                // 此时当前线程也不需要自己去做redo log的生成工作，也由后台处理，能尽快结束事务
                // RedoLogRecord r = RedoLogRecord.createLazyTransactionRedoLogRecord(transactionEngine, transactionId,
                // undoLog);
                RedoLogRecord r = createLocalTransactionRedoLogRecord();
                logSyncService.addRedoLogRecord(r);
                return true;
            }
        } else {
            // 如果不需要事务日志同步，那么什么都不做，可以直接提交事务了
            return true;
        }
    }

    @Override
    public void asyncCommitComplete() {
        commitFinal();
        if (session != null) {
            session.asyncCommitComplete();
        }
        if (asyncTask != null) {
            try {
                asyncTask.run();
            } catch (Exception e) {
                throw DbException.convert(e);
            }
        }
    }

    @Override
    public void commit() {
        commitLocal();
    }

    @Override
    public void commit(String globalTransactionName) {
        commitLocal();
    }

    private void commitLocal() {
        checkNotClosed();
        writeRedoLog(false);
        commitFinal();
    }

    @Override
    public void commitFinal() {
        commitFinal(transactionId);
    }

    // tid在分布式场景下可能是其他事务的tid
    protected void commitFinal(long tid) {
        // 避免并发提交(TransactionValidator线程和其他读写线程都有可能在检查到分布式事务有效后帮助提交最终事务)
        AMTransaction t = transactionEngine.removeTransaction(tid);
        if (t == null)
            return;
        t.commitTimestamp = t.transactionEngine.nextEvenTransactionId(); // 生成新的
        // 先提交，事务变成结束状态再解锁
        UndoLog undoLog = t.undoLog;
        undoLog.commit(transactionEngine, tid);
        t.endTransaction(false);
        undoLog.unlock();
        wakeUpWaitingTransactions();
        undoLog.gc();
    }

    private void endTransaction(boolean remove) {
        savepoints = null;
        undoLog = null;
        status = STATUS_CLOSED;
        if (remove)
            transactionEngine.removeTransaction(transactionId);
    }

    private void wakeUpWaitingTransactions() {
        LinkedList<WaitingTransaction> waitingTransactions = waitingTransactionsRef.get();
        while (true) {
            if (waitingTransactions != null && waitingTransactions != EMPTY_LINKED_LIST) {
                for (WaitingTransaction waitingTransaction : waitingTransactions) {
                    waitingTransaction.wakeUp();
                }
            }
            if (waitingTransactionsRef.compareAndSet(waitingTransactions, null))
                break;
            waitingTransactions = waitingTransactionsRef.get();
        }
        lockedBy = null;
    }

    @Override
    public void wakeUpWaitingTransaction(Transaction transaction) {
        wakeUpWaitingTransaction((AMTransaction) transaction);
    }

    @Override
    public int addWaitingTransaction(Object key, Transaction transaction, Listener listener) {
        return addWaitingTransaction(key, (AMTransaction) transaction, listener);
    }

    void wakeUpWaitingTransaction(AMTransaction transaction) {
        while (true) {
            LinkedList<WaitingTransaction> waitingTransactions = waitingTransactionsRef.get();
            LinkedList<WaitingTransaction> newWaitingTransactions = new LinkedList<>(
                    waitingTransactions);
            WaitingTransaction target = null;
            for (WaitingTransaction wt : newWaitingTransactions) {
                if (wt.getTransaction() == transaction) {
                    target = wt;
                    break;
                }
            }
            if (target == null)
                return;
            newWaitingTransactions.remove(target);
            if (waitingTransactionsRef.compareAndSet(waitingTransactions, newWaitingTransactions)) {
                target.wakeUp();
                return;
            }
        }
    }

    int addWaitingTransaction(Object key, AMTransaction transaction, Listener listener) {
        Session session = transaction.getSession();
        SessionStatus oldSessionStatus = session.getStatus();
        session.setStatus(SessionStatus.WAITING);
        transaction.setStatus(STATUS_WAITING);
        transaction.waitFor(this);
        WaitingTransaction wt = new WaitingTransaction(key, transaction, listener);
        LinkedList<WaitingTransaction> waitingTransactions = waitingTransactionsRef.get();
        while (true) {
            // 如果已经提交了，通知重试
            if (status == STATUS_CLOSED) {
                transaction.setStatus(STATUS_OPEN);
                transaction.waitFor(null);
                session.setStatus(oldSessionStatus);
                return OPERATION_NEED_RETRY;
            }
            LinkedList<WaitingTransaction> newWaitingTransactions = new LinkedList<>(
                    waitingTransactions);
            newWaitingTransactions.add(wt);
            if (waitingTransactionsRef.compareAndSet(waitingTransactions, newWaitingTransactions)) {
                return OPERATION_NEED_WAIT;
            }
            waitingTransactions = waitingTransactionsRef.get();
        }
    }

    private void waitFor(AMTransaction transaction) {
        lockedBy = transaction;
        lockStartTime = System.currentTimeMillis();
    }

    @Override
    public void checkTimeout() {
        if (lockedBy != null)
            return;
        if (lockedBy != null && lockStartTime != 0
                && System.currentTimeMillis() - lockStartTime > session.getLockTimeout()) {
            boolean isDeadlock = false;
            WaitingTransaction waitingTransaction = null;
            LinkedList<WaitingTransaction> waitingTransactions = waitingTransactionsRef.get();
            if (waitingTransactions == null)
                return;
            for (WaitingTransaction wt : waitingTransactions) {
                if (wt.getTransaction() == lockedBy) {
                    isDeadlock = true;
                    waitingTransaction = wt;
                    break;
                }
            }
            waitingTransactions = lockedBy.waitingTransactionsRef.get();
            if (waitingTransactions == null)
                return;
            WaitingTransaction waitingTransaction2 = null;
            for (WaitingTransaction wt : waitingTransactions) {
                if (wt.getTransaction() == this) {
                    waitingTransaction2 = wt;
                    break;
                }
            }
            if (isDeadlock) {
                String msg = getMsg(transactionId, session, lockedBy, waitingTransaction2);
                msg += "\r\n"
                        + getMsg(lockedBy.transactionId, lockedBy.session, this, waitingTransaction);
                throw DbException.get(ErrorCode.DEADLOCK_1, msg);
            } else {
                String msg = getMsg(transactionId, session, lockedBy, waitingTransaction2);
                throw DbException.get(ErrorCode.LOCK_TIMEOUT_1, msg);
            }
        }
    }

    private static String getMsg(long tid, Session session, AMTransaction transaction,
            WaitingTransaction waitingTransaction) {
        return "transaction #" + tid + " in session " + session + " wait for transaction #"
                + transaction.transactionId + " in session " + transaction.session + ", key: "
                + waitingTransaction.getKey();
    }

    @Override
    public void rollback() {
        try {
            checkNotClosed();
            rollbackTo(0);
        } finally {
            UndoLog undoLog = this.undoLog;
            endTransaction(true);
            undoLog.unlock();
            wakeUpWaitingTransactions();
        }
    }

    @Override
    public void rollbackToSavepoint(String name) {
        if (savepoints == null) {
            throw DbException.get(ErrorCode.SAVEPOINT_IS_INVALID_1, name);
        }

        Integer savepointId = savepoints.get(name);
        if (savepointId == null) {
            throw DbException.get(ErrorCode.SAVEPOINT_IS_INVALID_1, name);
        }
        int i = savepointId.intValue();
        rollbackToSavepoint(i);

        if (savepoints != null) {
            String[] names = new String[savepoints.size()];
            savepoints.keySet().toArray(names);
            for (String n : names) {
                savepointId = savepoints.get(n);
                if (savepointId.longValue() >= i) {
                    savepoints.remove(n);
                }
            }
        }
    }

    @Override
    public void rollbackToSavepoint(int savepointId) {
        checkNotClosed();
        rollbackTo(savepointId);
    }

    private void rollbackTo(int toLogId) {
        undoLog.rollbackTo(transactionEngine, toLogId);
    }

    protected void checkNotClosed() {
        if (status == STATUS_CLOSED) {
            throw DataUtils.newIllegalStateException(DataUtils.ERROR_CLOSED, "Transaction is closed");
        }
    }

    @Override
    public String toString() {
        return "t[" + transactionName + ", " + autoCommit + "]";
    }

    public static String getTransactionName(String hostAndPort, long tid) {
        if (hostAndPort == null)
            hostAndPort = "0:0";
        StringBuilder buff = new StringBuilder(hostAndPort);
        buff.append(':');
        buff.append(tid);
        return buff.toString();
    }

    void addTransactionalValue(TransactionalValue tv, TransactionalValue.LockOwner lo) {
        tValues.put(tv, lo);
    }

    TransactionalValue.LockOwner removeTransactionalValue(TransactionalValue tv) {
        return tValues.remove(tv);
    }

    TransactionalValue.LockOwner getLockOwner(TransactionalValue tv) {
        return tValues.get(tv);
    }
}
