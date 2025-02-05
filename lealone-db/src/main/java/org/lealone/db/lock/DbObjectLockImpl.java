/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.db.lock;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.lealone.db.DbObjectType;
import org.lealone.db.async.AsyncHandler;
import org.lealone.db.async.AsyncResult;
import org.lealone.db.session.ServerSession;
import org.lealone.transaction.Transaction;

//数据库对象模型已经支持多版本，所以对象锁只需要像行锁一样实现即可
public class DbObjectLockImpl implements DbObjectLock {

    private final AtomicReference<ServerSession> ref = new AtomicReference<>();
    private final DbObjectType type;
    private ArrayList<AsyncHandler<AsyncResult<Boolean>>> handlers;

    public DbObjectLockImpl(DbObjectType type) {
        this.type = type;
    }

    @Override
    public DbObjectType getDbObjectType() {
        return type;
    }

    @Override
    public void addHandler(AsyncHandler<AsyncResult<Boolean>> handler) {
        if (handlers == null)
            handlers = new ArrayList<>(1);
        handlers.add(handler);
    }

    @Override
    public boolean lock(ServerSession session, boolean exclusive) {
        if (exclusive)
            return tryExclusiveLock(session);
        else
            return trySharedLock(session);
    }

    private void addWaitingTransaction(ServerSession lockOwner, ServerSession session) {
        if (lockOwner != null) {
            Transaction transaction = lockOwner.getTransaction();
            if (transaction != null) {
                transaction.addWaitingTransaction(this, session.getTransaction(),
                        Transaction.getTransactionListener());
            }
        }
    }

    @Override
    public boolean trySharedLock(ServerSession session) {
        return true;
    }

    @Override
    public boolean tryExclusiveLock(ServerSession session) {
        if (ref.get() == session)
            return true;
        if (ref.compareAndSet(null, session)) {
            session.addLock(this);
            return true;
        } else {
            ServerSession oldSession = ref.get();
            if (oldSession != session) {
                addWaitingTransaction(oldSession, session);
                return false;
            } else {
                return true;
            }
        }
    }

    @Override
    public void unlock(ServerSession session) {
        unlock(session, true, null);
    }

    @Override
    public void unlock(ServerSession session, boolean succeeded) {
        unlock(session, succeeded, null);
    }

    @Override
    public void unlock(ServerSession oldSession, boolean succeeded, ServerSession newSession) {
        if (ref.compareAndSet(oldSession, newSession)) {
            if (handlers != null) {
                handlers.forEach(h -> {
                    h.handle(new AsyncResult<>(succeeded));
                });
                handlers = null;
            }

            if (newSession != null) {
                newSession.addLock(this);
                addWaitingTransaction(newSession, oldSession);
            }
        }
    }

    @Override
    public boolean isLockedExclusively() {
        return ref.get() != null;
    }

    @Override
    public boolean isLockedExclusivelyBy(ServerSession session) {
        return ref.get() == session;
    }
}
